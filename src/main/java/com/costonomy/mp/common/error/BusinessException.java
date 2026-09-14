package com.costonomy.mp.common.error;

import java.util.HashMap;
import java.util.Map;

/**
 * A business-rule or state violation, carrying the {@link ErrorCode} the client
 * will branch on.
 *
 * <p>This is the only exception type services should throw for an expected
 * failure. Expected failures are not exceptional in the logging sense — an
 * expired supplier order or an exceeded credit limit is the system working
 * correctly — so {@link GlobalExceptionHandler} logs these at WARN with no
 * stack trace, and reserves ERROR for genuine faults.
 *
 * <p>Stack traces are suppressed ({@code super(..., false, false)}): these are
 * thrown on ordinary control-flow paths, potentially at high volume, and filling
 * in a trace for each one costs more than it is worth when the code and context
 * already say everything needed.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Attach one piece of structured context, e.g. {@code detail("newPrice", ...)}. */
    public static BusinessException of(ErrorCode code, String key, Object value) {
        var details = new HashMap<String, Object>();
        details.put(key, value);
        return new BusinessException(code, code.defaultMessage(), details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
