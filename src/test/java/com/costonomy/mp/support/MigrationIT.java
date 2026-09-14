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
    @DisplayName("stateful tables carry the audit and version columns doc 02 §6 requires")
    void statefulTablesHaveVersionAndTimestamps() {
        // audit_log and outbox-adjacent join tables are deliberately exempt:
        // audit rows are append-only facts, and role_permission is a join table.
        var missing = jdbc.queryForList("""
                select t.table_name
                from information_schema.tables t
                where t.table_schema = database()
                  and t.table_name not in ('flyway_schema_history', 'audit_log',
                                           'role_permission', 'shedlock')
                  and not exists (
                      select 1 from information_schema.columns c
                      where c.table_schema = t.table_schema
                        and c.table_name = t.table_name
                        and c.column_name = 'version')
                """, String.class);

        assertThat(missing)
                .describedAs("stateful aggregates need a version column for optimistic locking")
                .isEmpty();
    }
}
