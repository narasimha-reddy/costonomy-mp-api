package com.costonomy.mp.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the schema Flyway produces is the schema the code expects.
 *
 * <p>Doc 10 §8: "Every migration must run from a clean database in CI."
 * Testcontainers gives a genuinely empty MySQL 8, so reaching this class at all
 * means every migration applied in order without error. The assertions below
 * then check the properties that are easy to get wrong in a hand-written
 * migration and expensive to discover in production.
 *
 * <p>Note that Hibernate's {@code ddl-auto=validate} has already run by the time
 * any test method executes: if an entity and its table had drifted, the Spring
 * context would have failed to start.
 */
class MigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration applies to a clean database")
    void migrationsApplyCleanly() {
        List<String> applied = jdbc.queryForList(
                "select script from flyway_schema_history where success = true order by installed_rank",
                String.class);

        assertThat(applied)
                .describedAs("Flyway should have applied the identity and platform migrations")
                .contains("V1__identity_access.sql", "V2__platform.sql");

        Integer failures = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    @DisplayName("every table is InnoDB with the utf8mb4 collation production uses")
    void tablesUseExpectedEngineAndCollation() {
        // A table created with the server default rather than an explicit clause
        // works locally and then behaves differently on a server configured
        // otherwise. Checking it here makes the convention enforceable.
        var offenders = jdbc.queryForList("""
                select table_name, engine, table_collation
                from information_schema.tables
                where table_schema = database()
                  and table_name <> 'flyway_schema_history'
                  and (engine <> 'InnoDB' or table_collation <> 'utf8mb4_0900_ai_ci')
                """);

        assertThat(offenders)
                .describedAs("tables must be InnoDB / utf8mb4_0900_ai_ci (doc 02 §1)")
                .isEmpty();
    }

    @Test
    @DisplayName("idempotency keys are unique per actor, operation and key")
    void idempotencyUniqueConstraintExists() {
        // This constraint is the mechanism that decides the race between two
        // concurrent retries. If it were ever dropped, IdempotencyStore.claim()
        // would still compile and still look correct, and duplicate payments
        // would start getting through.
        Integer count = jdbc.queryForObject("""
                select count(*)
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'idempotency_record'
                  and index_name = 'uk_idempotency_actor_operation_key'
                  and non_unique = 0
                """, Integer.class);

        assertThat(count)
                .describedAs("uk_idempotency_actor_operation_key must exist and be unique")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a phone number can only be registered once")
    void userPhoneIsUnique() {
        jdbc.update("insert into users (phone, status) values (?, 'ACTIVE')", "+919999000001");

        assertThat(
                org.assertj.core.api.Assertions.catchThrowable(() ->
                        jdbc.update("insert into users (phone, status) values (?, 'ACTIVE')", "+919999000001")))
                .describedAs("uk_users_phone must reject a duplicate")
                .isNotNull();
    }

    @Test
    @DisplayName("every mutable table carries a version column for optimistic locking")
    void mutableTablesHaveVersion() {
        // Stated as a rule rather than a list of exemptions, so it keeps working
        // as tables are added: a table that can be updated has an `updated_at`,
        // and anything that can be updated concurrently needs `version` for the
        // optimistic locking doc 02 §6 requires.
        //
        // Tables with only `created_at` are append-only by design — audit_log,
        // and the pure join tables — and are correctly exempt.
        var missing = jdbc.queryForList("""
                select t.table_name
                from information_schema.tables t
                where t.table_schema = database()
                  and t.table_name <> 'flyway_schema_history'
                  -- Counters, not aggregates. They serialise on a row lock
                  -- taken by INSERT … ON DUPLICATE KEY UPDATE; optimistic
                  -- locking on a hot counter would mean constant conflicts on
                  -- the one row every submission touches.
                  and t.table_name not in ('order_number_sequence', 'credit_invoice_sequence',
                                           'dispute_number_sequence')
                  and exists (
                      select 1 from information_schema.columns c
                      where c.table_schema = t.table_schema
                        and c.table_name = t.table_name
                        and c.column_name = 'updated_at')
                  and not exists (
                      select 1 from information_schema.columns c
                      where c.table_schema = t.table_schema
                        and c.table_name = t.table_name
                        and c.column_name = 'version')
                """, String.class);

        assertThat(missing)
                .describedAs("a table with updated_at can be mutated and needs optimistic locking")
                .isEmpty();
    }

    @Test
    @DisplayName("append-only tables are append-only on purpose, not by omission")
    void appendOnlyTablesAreDeliberate() {
        // The other half of the rule above. If one of these ever grows an
        // updated_at, that is a design change — an audit row being rewritten, or a
        // join table becoming an entity — and should be a conscious one.
        var appendOnly = jdbc.queryForList("""
                select t.table_name
                from information_schema.tables t
                where t.table_schema = database()
                  and not exists (
                      select 1 from information_schema.columns c
                      where c.table_schema = t.table_schema
                        and c.table_name = t.table_name
                        and c.column_name = 'updated_at')
                """, String.class);

        assertThat(appendOnly)
                .describedAs("unexpected table without updated_at — is it meant to be append-only?")
                .containsExactlyInAnyOrder(
                        "flyway_schema_history",
                        // Append-only facts.
                        "audit_log",
                        "catalog_import_row",
                        // A ledger of what we asked the provider to do. Rewriting
                        // one would erase the trail a payment is reconstructed from.
                        "payment_transaction",
                        // The same, for credit. A limit change or a repayment is
                        // answered by the row that recorded it, not by today's balance.
                        "credit_transaction",
                        "credit_limit_history",
                        "credit_payment",
                        // Delivery's evidence trail. A courier's event, a driver's
                        // position and a booking attempt are records of what
                        // happened; editing one would rewrite the journey.
                        "delivery_quote",
                        "delivery_provider_attempt",
                        "delivery_event",
                        "delivery_location",
                        // Realtime's projection and its handshake tickets. Both are
                        // written once and read by cursor; a row that changed after
                        // the fact would change what a client already replayed.
                        "realtime_event",
                        "realtime_ticket",
                        // What arrived, what was complained about, and the evidence
                        // for it. Editing any of these would rewrite the record a
                        // dispute is argued from.
                        "receiving_item",
                        "dispute_item",
                        "dispute_message",
                        "dispute_evidence",
                        // Reference data that is added or removed, never edited.
                        "canonical_product_alias",
                        // Pure join tables.
                        "role_permission",
                        "restaurant_user_outlet",
                        "supplier_user_store",
                        // Owned by ShedLock; its columns are fixed by the library.
                        "shedlock");
    }
}
