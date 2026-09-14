package com.costonomy.mp.common.web;

/**
 * Per-request correlation state.
 *
 * <p>Doc 09 §15 requires a request ID on every request, propagated into logs,
 * audit records, domain events and provider calls. Threading it through every
 * method signature would be noise, so it lives in a thread-local that
 * {@link RequestIdFilter} populates and clears.
 *
 * <p>Beware: a value set here is <em>not</em> visible on another thread. Any
 * {@code @Async} method or executor task that needs the request ID must be
 * handed it explicitly, because the thread-local does not follow.
 */
public final class RequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    /** The current request's ID, or {@code "-"} outside a request (jobs, tests). */
    public static String requestId() {
        String id = REQUEST_ID.get();
        return id == null ? "-" : id;
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
