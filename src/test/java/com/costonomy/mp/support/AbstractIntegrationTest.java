package com.costonomy.mp.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real database.
 *
 * <p>Runs against <b>MySQL 8, not H2</b>. Doc 10 §8 requires every migration to
 * run from a clean database in CI, and H2's MySQL compatibility mode does not
 * faithfully reproduce MySQL 8 DDL, {@code utf8mb4_0900_ai_ci} collation,
 * {@code JSON} columns or foreign-key behaviour. Verifying migrations against H2
 * would mean verifying them against a database we never deploy to, which is
 * close to not verifying them at all. See docs/DECISIONS.md D-007.
 *
 * <p>The container is {@code static} so one MySQL instance is shared by every
 * integration test in the run. Testcontainers starts it on first use and reuses
 * it thereafter; a container per test class would add ~10s each.
 *
 * <p>Tagged {@code integration}, which Surefire excludes and Failsafe includes.
 * So {@code mvn test} runs the fast unit suite with no Docker requirement, and
 * {@code mvn verify} runs these.
 *
 * <p>{@code @EnabledIf} additionally skips the whole class when no Docker daemon
 * is reachable, so a developer who has not started Docker gets a clear "skipped"
 * rather than a wall of initialisation errors. <b>CI must not rely on that
 * skip</b> — a silently skipped migration suite is the same as no migration
 * suite, so the pipeline asserts Docker is present before running `mvn verify`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@EnabledIf("dockerAvailable")
public abstract class AbstractIntegrationTest {

    /**
     * Whether a Docker daemon is reachable. Evaluated before the static
     * initialiser below runs, which is what turns an absent daemon into a skip
     * rather than an {@code ExceptionInInitializerError}.
     */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("costonomy_mp")
            .withUsername("mp")
            .withPassword("mp")
            // Match the production collation exactly. A test database on a
            // different collation silently accepts DDL and comparison behaviour
            // that production would reject.
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    // UTC, matching hibernate.jdbc.time_zone. A container on a
                    // different zone would make timestamp assertions pass locally
                    // and fail elsewhere.
                    "--default-time-zone=+00:00");

    static {
        // Started once per JVM and deliberately never stopped: Testcontainers'
        // Ryuk sidecar removes it when the JVM exits. Stopping it in an
        // @AfterAll would tear it down between test classes.
        //
        // Guarded, because a static initialiser runs before JUnit evaluates
        // @EnabledIf on a *subclass*, and an unguarded start() would then throw
        // ExceptionInInitializerError instead of skipping.
        if (dockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // Flyway builds the schema; Hibernate then validates its entities against
        // it. A mismatch fails the test run, which is how entity/migration drift
        // gets caught before a deploy does.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
