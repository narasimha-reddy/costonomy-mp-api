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
            "costonomy.mp.providers.payment", "costonomy.mp.providers.otp",
            "costonomy.mp.providers.payout");

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
        // A switch for watching a withdrawal be reversed on a local stack (D-110). With it off, a
        // withdrawal is debited without first asking the provider whether it can be refunded. Any explicit
        // value but exactly "true" is refused, not only "false": Spring reads off, no and 0 as false too, and
        // a value that means something else than what it says must not slip through.
        String precheck = environment.getProperty("costonomy.mp.wallet.withdraw-precheck");
        if (precheck != null && !"true".equalsIgnoreCase(precheck.trim())) {
            throw new IllegalStateException("costonomy.mp.wallet.withdraw-precheck is '" + precheck.trim()
                    + "' under a production profile. It is for local testing only; remove it, or set it to true.");
        }
        // D-106: paying third-party merchants out of a wallet balance needs
        // legal/RBI sign-off before this can go live, whatever the payout
        // provider is set to.
        if (environment.getProperty("costonomy.mp.quickscan.enabled", Boolean.class, false)) {
            throw new IllegalStateException("costonomy.mp.quickscan.enabled is true under a production "
                    + "profile. QuickScan needs legal sign-off before production; leave it disabled.");
        }
    }
}
