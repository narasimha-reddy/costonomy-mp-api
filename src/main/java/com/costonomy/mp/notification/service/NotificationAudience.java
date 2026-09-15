package com.costonomy.mp.notification.service;

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

    /** Everyone who can act for this outlet. */
    @Transactional(readOnly = true)
    public List<Long> forOutlet(Long outletId) {
        List<Long> users = new ArrayList<>();
        jdbc.query("""
                select distinct ur.user_id
                  from user_role ur
                  join outlet o on ((ur.scope_type = 'OUTLET' and ur.scope_id = o.id)
                                 or (ur.scope_type = 'RESTAURANT' and ur.scope_id = o.restaurant_id))
                 where o.id = ? and ur.status = 'ACTIVE'
                """,
                rs -> {
                    users.add(rs.getLong(1));
                },
                outletId);
        return users;
    }

    /** Everyone who can act for this supplier store. */
    @Transactional(readOnly = true)
    public List<Long> forSupplierStore(Long supplierStoreId) {
        List<Long> users = new ArrayList<>();
        jdbc.query("""
                select distinct ur.user_id
                  from user_role ur
                  join supplier_store s on ((ur.scope_type = 'SUPPLIER_STORE' and ur.scope_id = s.id)
                        or (ur.scope_type = 'SUPPLIER' and ur.scope_id = s.supplier_organization_id))
                 where s.id = ? and ur.status = 'ACTIVE'
                """,
                rs -> {
                    users.add(rs.getLong(1));
                },
                supplierStoreId);
        return users;
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
