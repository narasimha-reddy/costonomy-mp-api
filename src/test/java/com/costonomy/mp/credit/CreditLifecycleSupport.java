package com.costonomy.mp.credit;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.ApiClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the line-lifecycle suites (close, offer expiry, due-date extension, request context) share: staff users,
 * notification relaying and a few state readers. Beside {@link CreditEdgeSupport}, which it does not edit.
 */
final class CreditLifecycleSupport {

    private final JdbcTemplate jdbc;
    private final NotificationRelayAccess relay;
    final CreditEdgeSupport e;

    CreditLifecycleSupport(CreditEdgeSupport e, JdbcTemplate jdbc, NotificationRelayAccess relay) {
        this.e = e;
        this.jdbc = jdbc;
        this.relay = relay;
    }

    /** A user of the seller's own organisation holding {@code roleCode} on the line's store. */
    String staff(Line line, String roleCode) throws Exception {
        long orgId = e.count("select supplier_organization_id from supplier_store where id = ?",
                line.seller().storeId());
        String phone = ApiClient.freshPhone();
        e.s.api.post(line.seller().token(), "/api/v1/suppliers/" + orgId + "/users",
                Map.of("phone", phone, "roleCode", roleCode, "storeId", line.seller().storeId()));
        return e.s.api.login(phone);
    }

    void registerDevice(String token) throws Exception {
        e.s.api.post(token, "/api/v1/devices", Map.of("platform", "ANDROID", "pushToken", "tok-" + UUID.randomUUID(),
                "appVersion", "1.0.0", "deviceModel", "Pixel"));
    }

    int events(String type, long aggregateId) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and aggregate_id = ?",
                Integer.class, type, aggregateId);
    }

    int audits(String action, long entityId) {
        return jdbc.queryForObject("select count(*) from audit_log where action = ? and entity_id = ?",
                Integer.class, action, entityId);
    }

    String payload(String type, long aggregateId) {
        return jdbc.queryForObject("select payload from outbox_event where event_type = ? and aggregate_id = ? "
                + "order by id desc limit 1", String.class, type, aggregateId);
    }

    /** This aggregate's outbox events of one type, handed to the relay as the outbox would. */
    void relayEvents(String type, long aggregateId) {
        for (var event : jdbc.queryForList("select event_id, event_type, aggregate_type, aggregate_id, "
                + "payload_version, payload, actor_id, correlation_id, occurred_at from outbox_event "
                + "where event_type = ? and aggregate_id = ? order by id", type, aggregateId)) {
            relay.publish(new OutboxPublisher.DomainEventEnvelope(
                    (String) event.get("event_id"), (String) event.get("event_type"),
                    (String) event.get("aggregate_type"), ((Number) event.get("aggregate_id")).longValue(),
                    ((Number) event.get("payload_version")).intValue(), String.valueOf(event.get("payload")),
                    null, (String) event.get("correlation_id"), Instant.now()));
        }
    }

    List<Map<String, Object>> notificationsFor(String type, long targetId) {
        return jdbc.queryForList("select id, title, body, critical, audience from notification "
                + "where event_type = ? and target_id = ?", type, targetId);
    }

    int deliveries(String type, long targetId, String channel) {
        return jdbc.queryForObject("select count(*) from notification_delivery d "
                + "join notification n on n.id = d.notification_id "
                + "where n.event_type = ? and n.target_id = ? and d.channel = ?",
                Integer.class, type, targetId, channel);
    }

    String status(long agreementId) {
        return jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, agreementId);
    }

    Object column(long agreementId, String column) {
        return jdbc.queryForMap("select " + column + " from credit_agreement where id = ?", agreementId).get(column);
    }
}
