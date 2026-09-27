package com.costonomy.mp.common.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Business ids on every log line written while they are in scope (D-100).
 *
 * <p>Log lines used to name what they were about in whatever words the author
 * chose — "payment 152", "Order 7", a Razorpay id or nothing — so finding one
 * payment's story meant guessing phrasings. Inside a scope, every line carries
 * the same {@code key=value} pairs, appended by the log pattern from the
 * {@code trace} MDC key, including lines written deep inside code that has never
 * heard of the payment.
 *
 * <pre>{@code
 * try (var trace = TraceScope.of("payment", payment.getId(), "order", orderId)) {
 *     ...
 * }
 * }</pre>
 *
 * <p>Scopes nest: an inner one adds to the outer ids and closing it restores them.
 * Null values are left out, so a scope can be opened before every id is known.
 */
public final class TraceScope implements AutoCloseable {

    static final String MDC_KEY = "trace";

    private final String previous;

    private TraceScope(String previous) {
        this.previous = previous;
    }

    /** Key, value, key, value… Values may be null and are then omitted. */
    public static TraceScope of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("TraceScope takes key/value pairs");
        }
        String previous = MDC.get(MDC_KEY);

        Map<String, String> ids = new LinkedHashMap<>();
        if (previous != null) {
            for (String pair : previous.trim().split(" ")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    ids.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                ids.put(String.valueOf(keysAndValues[i]), sanitize(String.valueOf(value)));
            }
        }

        String rendered = ids.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" ", " ", ""));
        MDC.put(MDC_KEY, rendered);
        return new TraceScope(previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    }

    /**
     * Keep a value on one line and in one token. Ids reaching here include ones a
     * provider sent us; a newline in one would let it forge log lines, the reason
     * RequestIdFilter sanitises its header too.
     */
    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.:\\-]", "_");
    }
}
