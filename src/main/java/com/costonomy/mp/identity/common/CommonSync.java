package com.costonomy.mp.identity.common;

import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import com.costonomy.security.client.CommonApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps MP's copies of costonomy-common-api data under common's ids: {@code users}, {@code restaurant} and
 * {@code supplier_organization} (common orgs of type RESTAURANT / SUPPLIER), {@code outlet} and
 * {@code supplier_store} (common outlets). Business tables keep pointing at these ids as before.
 *
 * <p>Identity fields (names, address, contact, status) follow common; MP-only columns (delivery instructions,
 * operating hours, SLAs, supplier verification and lifecycle) are MP's and are never overwritten here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommonSync {

    private final JdbcTemplate jdbc;
    private final CommonApiClient common;
    private final AccessClient access;

    /** Access version last checked per user: copies are only re-checked when common's access changed. */
    private final Map<Long, Long> checkedVersion = new ConcurrentHashMap<>();

    // ── users ────────────────────────────────────────────────────────────

    @Transactional
    public void ensureUser(long userId) {
        if (count("SELECT COUNT(*) FROM users WHERE id = ?", userId) == 0) {
            common.user(userId).ifPresentOrElse(this::upsertUser,
                    () -> { throw new IllegalStateException("costonomy-common-api has no user " + userId); });
        }
    }

    @Transactional
    public void syncUser(long userId) {
        common.user(userId).ifPresent(this::upsertUser);
    }

    private void upsertUser(CommonApiClient.CommonUser u) {
        jdbc.update("INSERT INTO users (id, phone, name, email, status, phone_verified_at) VALUES (?, ?, ?, ?, ?, "
                        + "IF(? IS NULL, NULL, CURRENT_TIMESTAMP(6))) ON DUPLICATE KEY UPDATE phone = VALUES(phone), "
                        + "name = VALUES(name), email = VALUES(email), status = VALUES(status)",
                u.id(), u.primaryPhone(), u.name(), u.primaryEmail(), userStatus(u.status()), u.primaryPhone());
    }

    private static String userStatus(String s) {
        return "SUSPENDED".equals(s) || "DELETED".equals(s) ? s : "ACTIVE";
    }

    // ── orgs and outlets ────────────────────────────────────────────────

    /** Copies (or refreshes) the org and, for each outlet the snapshot lists, the outlet or store. */
    public void ensureScopes(long userId, AccessSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Long last = checkedVersion.get(userId);
        if (last != null && last == snapshot.accessVersion()) {
            return;
        }
        for (AccessSnapshot.Org org : snapshot.orgs() == null ? List.<AccessSnapshot.Org>of() : snapshot.orgs()) {
            if (!exists(org.orgType(), org.orgId())) {
                syncOrg(org.orgId());
            }
            for (AccessSnapshot.Outlet o : org.outlets()) {
                if (!outletExists(org.orgType(), o.outletId())) {
                    syncOutlet(o.outletId());
                }
            }
        }
        checkedVersion.put(userId, snapshot.accessVersion());
    }

    public void invalidate(long userId) {
        checkedVersion.remove(userId);
    }

    /**
     * After Marketplace changed someone's access in common (a new business, outlet or role): drop the cached
     * snapshot so their next check, often in the same request, sees it.
     */
    public void accessChanged(long userId) {
        access.evictUser(userId, null);
        invalidate(userId);
    }

    @Transactional
    public void syncOrg(long orgId) {
        common.org(orgId).ifPresent(o -> upsertOrg(o.id(), o.orgType(), o.displayName(), o.legalName(), o.status()));
    }

    private void upsertOrg(long id, String orgType, String displayName, String legalName, String status) {
        String name = text(displayName, "Business " + id, 200);
        if ("SUPPLIER".equals(orgType)) {
            jdbc.update("INSERT INTO supplier_organization (id, legal_name, display_name) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), "
                            + "legal_name = COALESCE(NULLIF(VALUES(legal_name), ''), legal_name)",
                    id, text(legalName, name, 250), name);
        } else {
            jdbc.update("INSERT INTO restaurant (id, name, legal_name, status) VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE name = VALUES(name), legal_name = VALUES(legal_name), "
                            + "status = VALUES(status)",
                    id, name, legalName, "ACTIVE".equals(status) || status == null ? "ACTIVE" : "SUSPENDED");
        }
    }

    /** The outlet (restaurant org) or store (supplier org), with its org, from common. */
    @Transactional
    public void syncOutlet(long outletId) {
        common.outlet(outletId).ifPresent(d -> {
            CommonApiClient.CommonOutlet o = d.outlet();
            String orgType = d.org() == null ? "RESTAURANT" : d.org().orgType();
            if (!exists(orgType, o.orgId())) {
                upsertOrg(o.orgId(), orgType, d.org() == null ? null : d.org().displayName(), null,
                        d.org() == null ? null : d.org().status());
            }
            String status = "ACTIVE".equals(o.status()) ? "ACTIVE" : "INACTIVE";
            if ("SUPPLIER".equals(orgType)) {
                jdbc.update("INSERT INTO supplier_store (id, supplier_organization_id, name, address_line1, city, state, "
                                + "pincode, latitude, longitude, contact_phone, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE name = VALUES(name), address_line1 = VALUES(address_line1), "
                                + "city = VALUES(city), state = VALUES(state), pincode = VALUES(pincode), "
                                + "latitude = COALESCE(VALUES(latitude), latitude), longitude = COALESCE(VALUES(longitude), longitude), "
                                + "contact_phone = VALUES(contact_phone), status = VALUES(status)",
                        o.id(), o.orgId(), text(o.name(), "Store " + o.id(), 200), text(o.addressLine1(), "", 250),
                        text(o.city(), "", 120), text(o.stateCode(), "", 120), o.pincode(), o.latitude(), o.longitude(),
                        o.contactPhone(), status);
            } else {
                jdbc.update("INSERT INTO outlet (id, restaurant_id, name, address_line1, city, state, pincode, latitude, "
                                + "longitude, contact_phone, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE name = VALUES(name), address_line1 = VALUES(address_line1), "
                                + "city = VALUES(city), state = VALUES(state), pincode = VALUES(pincode), "
                                + "latitude = COALESCE(VALUES(latitude), latitude), longitude = COALESCE(VALUES(longitude), longitude), "
                                + "contact_phone = VALUES(contact_phone), status = VALUES(status)",
                        o.id(), o.orgId(), text(o.name(), "Outlet " + o.id(), 200), text(o.addressLine1(), "", 250),
                        text(o.city(), "", 120), text(o.stateCode(), "", 120), o.pincode(), o.latitude(), o.longitude(),
                        o.contactPhone(), status);
            }
        });
    }

    private boolean exists(String orgType, long orgId) {
        return count("SUPPLIER".equals(orgType) ? "SELECT COUNT(*) FROM supplier_organization WHERE id = ?"
                : "SELECT COUNT(*) FROM restaurant WHERE id = ?", orgId) > 0;
    }

    private boolean outletExists(String orgType, long outletId) {
        return count("SUPPLIER".equals(orgType) ? "SELECT COUNT(*) FROM supplier_store WHERE id = ?"
                : "SELECT COUNT(*) FROM outlet WHERE id = ?", outletId) > 0;
    }

    private static String text(String v, String fallback, int max) {
        String t = v == null || v.isBlank() ? fallback : v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
