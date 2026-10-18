package com.costonomy.mp.experiment.context;

import java.util.Optional;

/**
 * Thread-local holder for experiment evaluation context.
 * Enables ambient subject evaluation across service calls without passing context down every method signature.
 */
public final class ExperimentContextHolder {

    private static final ThreadLocal<ExperimentContext> CONTEXT = new ThreadLocal<>();

    private ExperimentContextHolder() {
    }

    public static void set(ExperimentContext context) {
        if (context == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(context);
        }
    }

    public static Optional<ExperimentContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
