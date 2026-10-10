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
    /** The statement text is kept only between {@link #startCapture()} and {@link #stopCapture()}: this runs in every IT context. */
    private static final ThreadLocal<boolean[]> CAPTURING = ThreadLocal.withInitial(() -> new boolean[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        if (CAPTURING.get()[0]) {
            SQL.get().add(sql);
        }
        return sql;
    }

    public static void reset() {
        COUNT.get()[0] = 0;
        SQL.get().clear();
    }

    /** Resets, then keeps the text of every statement prepared on this thread until {@link #stopCapture()}. */
    public static void startCapture() {
        reset();
        CAPTURING.get()[0] = true;
    }

    public static void stopCapture() {
        CAPTURING.get()[0] = false;
    }

    public static int count() {
        return COUNT.get()[0];
    }

    /** The statements prepared on the current thread captured since {@link #startCapture()} (empty when not capturing). */
    public static java.util.List<String> statements() {
        return java.util.List.copyOf(SQL.get());
    }
}
