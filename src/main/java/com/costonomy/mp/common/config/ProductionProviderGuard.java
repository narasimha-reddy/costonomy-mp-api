package com.costonomy.mp.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start a production profile on a mock provider (D-101).
 *
 * <p>Both default to {@code MOCK} so local development needs no configuration —
 * which also means a production deploy that forgets one setting comes up
 * quietly on the mock. For payments that switches on free checkout simulation
 * and free wallet top-ups; for OTP, with a mock code set, anyone can sign in as
 * anyone. A default that is right locally and wrong in production has to be
 * refused in production, not remembered.
 */
@Component
@RequiredArgsConstructor
public class ProductionProviderGuard {

    /** Profiles that mean real customers. */
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    /** Providers that must never be the mock where money and identity are real. */
    static final List<String> GUARDED = List.of(
            "costonomy.mp.providers.payment", "costonomy.mp.providers.otp");

    private final Environment environment;

    @PostConstruct
    void refuseMocksInProduction() {
        check(environment);
    }

    static void check(Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(PRODUCTION_PROFILES::contains);
        if (!production) {
            return;
        }
        for (String property : GUARDED) {
            String value = environment.getProperty(property, "MOCK");
            if ("MOCK".equalsIgnoreCase(value.trim())) {
                throw new IllegalStateException(property + " is MOCK under a production profile. "
                        + "Set it to the real provider, or run without the production profile.");
            }
        }
    }
}
