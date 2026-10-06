package com.costonomy.mp.common.retention;

import com.costonomy.mp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The nightly purge removes what only grows, in batches, and keeps what must stay (D-148). */
class RetentionPurgerIT extends AbstractIntegrationTest {

    @Autowired private RetentionPurger purger;
    @Autowired private JdbcTemplate jdbc;

    private static Timestamp ago(Duration age) {
        return Timestamp.from(Instant.now().minus(age));
    }

    private static String hash64() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private void idempotency(String marker, Timestamp expiresAt) {
        jdbc.update("""
                insert into idempotency_record (actor_id, operation, idempotency_key, request_hash, state, expires_at)
                values (1, ?, ?, ?, 'COMPLETED', ?)
                """, marker, UUID.randomUUID().toString(), hash64().substring(0, 64), expiresAt);
    }

    private int countIdempotency(String marker) {
        return jdbc.queryForObject("select count(*) from idempotency_record where operation = ?",
                Integer.class, marker);
    }

    private void outbox(String marker, String status, Timestamp publishedAt) {
        jdbc.update("""
                insert into outbox_event (event_id, event_type, aggregate_type, aggregate_id, payload, status,
                                          published_at, occurred_at)
                values (?, ?, 'RETENTION_TEST', 1, '{}', ?, ?, now(6))
                """, UUID.randomUUID().toString(), marker, status, publishedAt);
    }

    private int countOutbox(String marker, String status) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and status = ?",
                Integer.class, marker, status);
    }

    /** Rows whose parents the test does not need: foreign keys off on this one connection only. */
    private void withoutForeignKeys(String sql, Object... args) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (var off = con.createStatement()) {
                off.execute("set foreign_key_checks = 0");
            }
            try (var ps = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            } finally {
                try (var on = con.createStatement()) {
                    on.execute("set foreign_key_checks = 1");
                }
            }
            return null;
        });
    }

    @Test
    @DisplayName("expired idempotency records go, current ones stay, and a backlog is cleared across batches")
    void idempotency() {
        String marker = "retention-test-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            idempotency(marker, ago(Duration.ofHours(30)));
        }
        idempotency(marker, Timestamp.from(Instant.now().plus(Duration.ofHours(5))));

        // A batch of two forces three passes over the five expired rows.
        var deleted = purger.runAll(Instant.now(), 2);

        assertThat(countIdempotency(marker)).as("only the unexpired row is left").isEqualTo(1);
        assertThat(deleted.get("idempotency_record")).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("old published outbox rows go; recent, pending and failed ones stay")
    void outboxKeepsWhatMustStay() {
        String marker = "RetentionTest" + UUID.randomUUID();
        outbox(marker, "PUBLISHED", ago(Duration.ofDays(20)));
        outbox(marker, "PUBLISHED", ago(Duration.ofDays(2)));
        outbox(marker, "PENDING", null);
        outbox(marker, "FAILED", ago(Duration.ofDays(60)));

        purger.runAll(Instant.now());

        assertThat(countOutbox(marker, "PUBLISHED")).as("the recent one only").isEqualTo(1);
        assertThat(countOutbox(marker, "PENDING")).isEqualTo(1);
        assertThat(countOutbox(marker, "FAILED")).as("kept for a person to see").isEqualTo(1);
    }

    @Test
    @DisplayName("old live-location rows, expired refresh tokens and old OTP challenges go; recent ones stay")
    void otherTables() {
        String tag = UUID.randomUUID().toString().replace("-", "");
        withoutForeignKeys("insert into delivery_location (delivery_id, latitude, longitude, recorded_at) "
                + "values (999999001, 12.9, 77.6, ?)", ago(Duration.ofDays(45)));
        withoutForeignKeys("insert into delivery_location (delivery_id, latitude, longitude, recorded_at) "
                + "values (999999001, 12.9, 77.6, ?)", ago(Duration.ofDays(1)));
        withoutForeignKeys("insert into refresh_token (user_id, token_hash, status, expires_at) "
                + "values (999999001, ?, 'EXPIRED', ?)", hash64(), ago(Duration.ofDays(45)));
        withoutForeignKeys("insert into refresh_token (user_id, token_hash, status, expires_at) "
                + "values (999999001, ?, 'ACTIVE', ?)", hash64(), Timestamp.from(Instant.now().plus(Duration.ofDays(5))));
        jdbc.update("insert into otp_verification (phone, purpose, otp_hash, status, max_attempts, expires_at, provider) "
                + "values (?, 'LOGIN', 'x', 'EXPIRED', 5, ?, 'MOCK')", "+91" + tag.substring(0, 10), ago(Duration.ofDays(20)));
        jdbc.update("insert into otp_verification (phone, purpose, otp_hash, status, max_attempts, expires_at, provider) "
                + "values (?, 'LOGIN', 'x', 'PENDING', 5, ?, 'MOCK')", "+91" + tag.substring(10, 20),
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(5))));

        purger.runAll(Instant.now());

        assertThat(jdbc.queryForObject("select count(*) from delivery_location where delivery_id = 999999001",
                Integer.class)).as("only the day-old position").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id = 999999001",
                Integer.class)).as("only the live token").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from otp_verification where phone in (?, ?)",
                Integer.class, "+91" + tag.substring(0, 10), "+91" + tag.substring(10, 20)))
                .as("only the pending challenge").isEqualTo(1);
    }

    @Test
    @DisplayName("the migration's indexes are in place")
    void indexesExist() {
        for (String index : new String[] {
                "ix_offer_sku_status", "ix_supplier_order_status_updated", "ix_supplier_order_created",
                "ix_payment_created", "ix_payment_captured", "ix_dispute_created", "ix_dispute_resolved",
                "ix_refund_late_success", "ix_refund_reversed", "ix_outbox_status_id",
                "ix_wallet_top_up_outlet_created", "ix_sku_review_sku_created", "ix_canonical_status_name"}) {
            assertThat(jdbc.queryForObject("""
                    select count(distinct table_name) from information_schema.statistics
                     where table_schema = database() and index_name = ?
                    """, Integer.class, index)).as(index).isEqualTo(1);
        }
    }
}
