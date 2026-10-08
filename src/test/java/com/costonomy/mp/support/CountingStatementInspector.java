package com.costonomy.mp.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the current thread, so a test can say "this page costs N queries,
 * however many rows it has" (D-116). Registered for every integration test by AbstractIntegrationTest; MockMvc runs the
 * request on the test's own thread. JdbcTemplate statements are not counted.
 */
public class CountingStatementInspector implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<java.util.List<String>> SQL = ThreadLocal.withInitial(java.util.ArrayList::new);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        SQL.get().add(sql);
        return sql;
    }

    public static void reset() {
        COUNT.get()[0] = 0;
        SQL.get().clear();
    }

    public static int count() {
        return COUNT.get()[0];
    }

    /** The statements prepared on the current thread since the last {@link #reset()}. */
    public static java.util.List<String> statements() {
        return java.util.List.copyOf(SQL.get());
    }
}
