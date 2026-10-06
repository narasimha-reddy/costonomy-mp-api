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

    /** Signing keys that were once defaults in application.properties. */
    static final Set<String> LEAKED_JWT_SECRETS = Set.of("SWt/kWYyraXKIm3nlqnJSL9vSqmPLkCT7GdZyKa4jDU=");

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

        // D-113: bills are private. Local disk is not storage anyone can rely on, and the fake reader
        // answers every bill with the same made-up one. Both default to the local forms, so a deploy that
        // forgets them is refused, not quietly run on them.
        if ("LOCAL".equalsIgnoreCase(environment.getProperty("costonomy.mp.invoices.storage.provider", "LOCAL").trim())) {
            throw new IllegalStateException("costonomy.mp.invoices.storage.provider is LOCAL under a production "
                    + "profile. Bills are private: set it to S3.");
        }
        if ("FAKE".equalsIgnoreCase(environment.getProperty("costonomy.mp.invoices.reader.provider", "FAKE").trim())) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.provider is FAKE under a production "
                    + "profile. Set it to HTTP.");
        }
        // D-114, D-115: the real reader signs in to the cost app with a username and password (a static token is for
        // quick local tests only), over https, as a known cost-app user, and each marketplace outlet sees only the cost
        // outlet it is mapped to. Only whether a value is set or well formed is looked at; no value is ever printed.
        if (!blank(environment.getProperty("costonomy.mp.invoices.reader.token"))) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.token is set under a production profile. "
                    + "It is for quick tests only; remove INVOICE_READER_TOKEN and use the reader's sign-in.");
        }
        if (blank(environment.getProperty("costonomy.mp.invoices.reader.username"))
                || blank(environment.getProperty("costonomy.mp.invoices.reader.password"))) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.username and .password are not set under a "
                    + "production profile. Set INVOICE_READER_USERNAME and INVOICE_READER_PASSWORD.");
        }
        String baseUrl = environment.getProperty("costonomy.mp.invoices.reader.base-url", "").strip();
        if (!baseUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.base-url is not an https URL under a "
                    + "production profile. Set INVOICE_READER_BASE_URL to the cost app's https address.");
        }
        if (positive(environment.getProperty("costonomy.mp.invoices.reader.user-id", "0")) <= 0) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.user-id is not set under a production "
                    + "profile. Set INVOICE_READER_USER_ID to the reading account's cost-app user id.");
        }
        if (positive(environment.getProperty("costonomy.mp.invoices.reader.outlet", "0")) <= 0) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.outlet is not set under a production "
                    + "profile. Set INVOICE_READER_OUTLET to the reading account's own cost-app outlet.");
        }
        if (!"false".equalsIgnoreCase(environment.getProperty("costonomy.mp.invoices.cost-outlet-fallback", "false")
                .strip())) {
            throw new IllegalStateException("costonomy.mp.invoices.cost-outlet-fallback is on under a production "
                    + "profile. It would show one cost outlet's suppliers and prices to every outlet; use "
                    + "INVOICE_COST_OUTLET_MAP instead.");
        }
        if (blank(environment.getProperty("costonomy.mp.invoices.cost-outlet-map"))) {
            throw new IllegalStateException("costonomy.mp.invoices.cost-outlet-map is empty under a production "
                    + "profile. Set INVOICE_COST_OUTLET_MAP to mpOutletId:costOutletId pairs.");
        }
        try {
            com.costonomy.mp.wallet.invoice.costapi.CostOutletMap.parse(
                    environment.getProperty("costonomy.mp.invoices.cost-outlet-map"));
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage() + " (under a production profile)");
        }
        // An enabled Pidge with no webhook secret would reject every status update (the webhook fails
        // closed), leaving deliveries stuck; refuse to start rather than discover it from a stuck order.
        if ("PIDGE".equalsIgnoreCase(environment.getProperty("costonomy.mp.providers.delivery", "").trim())
                && blank(environment.getProperty("costonomy.mp.pidge.webhook-secret"))) {
            throw new IllegalStateException("costonomy.mp.pidge.webhook-secret is not set while Pidge is the delivery "
                    + "provider under a production profile. Set PIDGE_WEBHOOK_SECRET.");
        }
        // The signing key that used to be the property's default is in the repository history, so it
        // is known to everyone with read access. Under production it is refused, as is a short or
        // missing one (JwtService also refuses short keys, but this names the cause).
        String jwtSecret = environment.getProperty("costonomy.mp.jwt.secret", "");
        if (jwtSecret.isBlank() || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || LEAKED_JWT_SECRETS.contains(jwtSecret.trim())) {
            throw new IllegalStateException("costonomy.mp.jwt.secret is missing, shorter than 32 bytes, or a "
                    + "value that was committed to the repository, under a production profile. Set JWT_SECRET "
                    + "to a freshly generated 32+ byte secret.");
        }
    }

    /** The number, or 0 when it is not one. */
    private static long positive(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
