package com.costonomy.mp.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the current thread, so a test can say "this page costs N queries,
 * however many rows it has" (D-116). Registered for every integration test by AbstractIntegrationTest; MockMvc runs the
 * request on the test's own thread. JdbcTemplate statements are not counted.
 */
public class CountingStatementInspector implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static int count() {
        return COUNT.get()[0];
    }
}
