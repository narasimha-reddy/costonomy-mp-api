package com.costonomy.mp.notification.service;

import com.costonomy.security.client.CommonApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Who to tell.
 *
 * <p>Derived from live grants, the same way {@code RealtimeEntitlements} derives
 * channels — and expanded down the scope hierarchy for the same reason: a grant on
 * a restaurant covers its outlets, because that is what the grant means everywhere
 * else. Two notions of "who belongs to this outlet" would drift, and the drift
 * would show up as somebody not being told their order was rejected.
 *
 * <p><b>Notifications go to people, not to scopes.</b> Everyone with a grant is
 * told, not just whoever happened to place the order: an order rejected at 6am is
 * the shift manager's problem whether or not they were the one who ordered it.
 */
@Service
@RequiredArgsConstructor
public class NotificationAudience {

    private final JdbcTemplate jdbc;
    private final CommonApiClient common;

    /** Everyone who can act for this outlet: its team in costonomy-common-api, business-wide members included. */
    @Transactional(readOnly = true)
    public List<Long> forOutlet(Long outletId) {
        return members(orgOf("select restaurant_id from outlet where id = ?", outletId), outletId, null);
    }

    /** Everyone who can act for this supplier store. */
    @Transactional(readOnly = true)
    public List<Long> forSupplierStore(Long supplierStoreId) {
        return members(orgOf("select supplier_organization_id from supplier_store where id = ?", supplierStoreId),
                supplierStoreId, null);
    }

    /**
     * Everyone who can act for this supplier store AND holds {@code permission} there (D-173): a salesperson who may
     * not see credit is not sent the credit digest.
     */
    @Transactional(readOnly = true)
    public List<Long> forSupplierStoreWith(Long supplierStoreId, String permission) {
        return members(orgOf("select supplier_organization_id from supplier_store where id = ?", supplierStoreId),
                supplierStoreId, permission);
    }

    private Long orgOf(String sql, Long id) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, id);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<Long> members(Long orgId, Long outletId, String permission) {
        if (orgId == null) {
            return List.of();
        }
        return common.members(orgId, outletId, permission, null).stream()
                .map(CommonApiClient.CommonMember::userId).distinct().toList();
    }

    /** A user's active push tokens, newest first. */
    public record Device(Long id, String pushToken) {
    }

    @Transactional(readOnly = true)
    public List<Device> devicesOf(Long userId) {
        List<Device> devices = new ArrayList<>();
        jdbc.query("""
                select id, push_token from device
                 where user_id = ? and status = 'ACTIVE' and push_token is not null
                 order by last_seen_at desc, id desc
                """,
                rs -> {
                    devices.add(new Device(rs.getLong(1), rs.getString(2)));
                },
                userId);
        return devices;
    }

    @Transactional(readOnly = true)
    public String phoneOf(Long userId) {
        var rows = jdbc.queryForList(
                "select phone from users where id = ?", String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
