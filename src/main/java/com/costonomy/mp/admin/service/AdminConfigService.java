package com.costonomy.mp.admin.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Operational configuration. Doc 09 §10.
 *
 * <p>Three requirements, and the third is the one that would be easy to skip:
 * configuration changes must be <b>versioned</b>, <b>audited</b>, and
 * <b>effective-dated where financially relevant</b>.
 *
 * <p><b>A change supersedes; it never overwrites.</b> The current row is closed
 * with an {@code effective_to} and a new version is inserted. Doc 09 §11 requires
 * settlement to be reproducible and commission to be snapshotted into each
 * calculation — both of which are impossible if the rate that applied in March can
 * be edited in June. It is the same rule as D-012's "a price is never edited, only
 * superseded", for the same reason.
 *
 * <p>The cache is refreshed after a change, because a configuration value nobody
 * reads until the next restart is a configuration value that did not change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminConfigService {

    private final JdbcTemplate jdbc;
    private final AppConfigService appConfig;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<AdminDtos.ConfigEntry> list(Long actorId, String prefix, boolean includeHistory) {
        accessControl.require(actorId, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);

        String like = prefix == null || prefix.isBlank() ? null : prefix.trim() + "%";
        List<AdminDtos.ConfigEntry> entries = new ArrayList<>();

        jdbc.query("""
                select config_key, config_value, value_type, config_version, description,
                       effective_from, effective_to, status, updated_by
                  from app_config
                 where (? is null or config_key like ?)
                   and (? = true or status = 'ACTIVE')
                 order by config_key, config_version desc
                """,
                rs -> {
                    var to = rs.getTimestamp(7);
                    entries.add(new AdminDtos.ConfigEntry(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getString(5),
                            rs.getTimestamp(6).toInstant(),
                            to == null ? null : to.toInstant(),
                            rs.getString(8), (Long) rs.getObject(9)));
                },
                like, like, includeHistory);

        return entries;
    }

    @Transactional
    public AdminDtos.ConfigEntry update(Long actorId, AdminDtos.UpdateConfigRequest request) {
        accessControl.require(actorId, Permissions.CONFIG_MANAGE, ScopeType.PLATFORM, null);

        var current = jdbc.query("""
                select config_value, value_type, config_version, description
                  from app_config
                 where config_key = ? and status = 'ACTIVE'
                 order by config_version desc limit 1
                """,
                (rs, row) -> new Object[] {rs.getString(1), rs.getString(2),
                        rs.getInt(3), rs.getString(4)},
                request.key());

        if (current.isEmpty()) {
            // Refused rather than created. A typo'd key would otherwise become a
            // configuration value that nothing reads and nobody notices — and the
            // setting the operator meant to change stays as it was.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "There is no configuration key called " + request.key() + ".");
        }

        String previousValue = (String) current.get(0)[0];
        String valueType = (String) current.get(0)[1];
        int previousVersion = (Integer) current.get(0)[2];
        String description = (String) current.get(0)[3];

        Instant effectiveFrom = request.effectiveFrom() == null
                ? Instant.now() : request.effectiveFrom();

        // Close the old version at the moment the new one starts, so the two never
        // overlap and a lookup at any instant has exactly one answer.
        jdbc.update("""
                update app_config
                   set status = 'SUPERSEDED', effective_to = ?,
                       version = version + 1, updated_at = now(6)
                 where config_key = ? and status = 'ACTIVE'
                """, java.sql.Timestamp.from(effectiveFrom), request.key());

        jdbc.update("""
                insert into app_config (config_key, config_value, value_type, config_version,
                                        description, effective_from, status, updated_by,
                                        created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, now(6), now(6), 0)
                """, request.key(), request.value(), valueType, previousVersion + 1,
                description, java.sql.Timestamp.from(effectiveFrom), actorId);

        auditService.record(actorId, null, "CONFIG_CHANGED", "APP_CONFIG", null,
                previousValue, request.value(),
                "%s: %s".formatted(request.key(), request.reason()), "ADMIN");

        // Otherwise the change takes effect at the next restart, which is not what
        // anyone means by changing a configuration value.
        appConfig.refresh();

        log.info("Configuration {} changed by operator {} (v{} → v{})",
                request.key(), actorId, previousVersion, previousVersion + 1);

        return new AdminDtos.ConfigEntry(request.key(), request.value(), valueType,
                previousVersion + 1, description, effectiveFrom, null, "ACTIVE", actorId);
    }
}
