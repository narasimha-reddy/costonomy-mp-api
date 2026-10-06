package com.costonomy.mp.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-101: a production profile must not come up on a mock payment or OTP provider. */
class ProductionProviderGuardTest {

    @Test
    @DisplayName("production with a provider left at its MOCK default refuses to start")
    void productionOnMockIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        // payment not set: the MOCK default is exactly the forgotten-setting case.
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.providers.payment");
    }

    @Test
    @DisplayName("production on real providers starts, and local on mocks starts")
    void otherwiseStarts() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("production");
        prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        prod.setProperty("costonomy.mp.providers.otp", "MSG91");
        prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        realInvoices(prod);
        assertThatCode(() -> ProductionProviderGuard.check(prod)).doesNotThrowAnyException();

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-110: the switch that skips the check before a withdrawal is refused under production, and fine locally")
    void withdrawalPrecheckCannotBeSwitchedOffInProduction() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        prod.setProperty("costonomy.mp.providers.otp", "MSG91");
        prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        realInvoices(prod);
        prod.setProperty("costonomy.mp.wallet.withdraw-precheck", "false");
        assertThatThrownBy(() -> ProductionProviderGuard.check(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withdraw-precheck");

        prod.setProperty("costonomy.mp.wallet.withdraw-precheck", "true");
        assertThatCode(() -> ProductionProviderGuard.check(prod)).doesNotThrowAnyException();

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        local.setProperty("costonomy.mp.wallet.withdraw-precheck", "false");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-110: production refuses any explicit value of the pre-check switch but true, including the ones Spring reads as false")
    void withdrawalPrecheckOnlyTrueOrUnsetInProduction() {
        // Spring binds off, no and 0 (and a blank) to false for a boolean property: a guard that only looked for
        // the word "false" let each of them switch the check off in production unnoticed.
        for (String value : new String[]{"false", "FALSE", "off", "no", "0", "n", "", " ", "disabled", "yes", "1"}) {
            var prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
            prod.setProperty("costonomy.mp.providers.otp", "MSG91");
            prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
            realInvoices(prod);
            prod.setProperty("costonomy.mp.wallet.withdraw-precheck", value);
            assertThatThrownBy(() -> ProductionProviderGuard.check(prod))
                    .describedAs("value '%s'", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("withdraw-precheck");
        }
        for (String value : new String[]{"true", "TRUE", " true "}) {
            var prod = new MockEnvironment();
            prod.setActiveProfiles("production");
            prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
            prod.setProperty("costonomy.mp.providers.otp", "MSG91");
            prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
            realInvoices(prod);
            prod.setProperty("costonomy.mp.wallet.withdraw-precheck", value);
            assertThatCode(() -> ProductionProviderGuard.check(prod)).describedAs("value '%s'", value).doesNotThrowAnyException();
        }
        var unset = new MockEnvironment();
        unset.setActiveProfiles("prod");
        unset.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        unset.setProperty("costonomy.mp.providers.otp", "MSG91");
        unset.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        realInvoices(unset);
        assertThatCode(() -> ProductionProviderGuard.check(unset)).doesNotThrowAnyException();
    }

    @DisplayName("production with the payout provider left at its MOCK default refuses to start")
    void productionOnMockPayoutIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        // payout not set: the MOCK default is exactly the forgotten-setting case.
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.providers.payout");
    }

    @Test
    @DisplayName("D-106: production refuses to start with QuickScan enabled, needs legal sign-off first")
    void productionWithQuickScanEnabledIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("production");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        env.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        realInvoices(env);
        env.setProperty("costonomy.mp.quickscan.enabled", "true");

        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.quickscan.enabled");

        // Disabled (the default), production starts as before.
        env.setProperty("costonomy.mp.quickscan.enabled", "false");
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }

    /** The two bill settings a production deploy must now make (D-113). */
    private static void realInvoices(MockEnvironment env) {
        env.setProperty("costonomy.mp.invoices.storage.provider", "S3");
        env.setProperty("costonomy.mp.invoices.reader.provider", "HTTP");
        readerSignIn(env);
    }

    /** Placeholders, not credentials: the guard only checks that they are set. */
    private static void readerSignIn(MockEnvironment env) {
        env.setProperty("costonomy.mp.invoices.reader.username", "placeholder-user");
        env.setProperty("costonomy.mp.invoices.reader.password", "placeholder-not-a-secret");
        env.setProperty("costonomy.mp.invoices.reader.base-url", "https://cost.example.invalid");
        env.setProperty("costonomy.mp.invoices.reader.user-id", "6");
        env.setProperty("costonomy.mp.invoices.reader.outlet", "5");
        env.setProperty("costonomy.mp.invoices.cost-outlet-map", "1:5");
        env.setProperty("costonomy.mp.jwt.secret", "placeholder-secret-of-at-least-32-bytes-long");
    }

    private static MockEnvironment production() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        env.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        return env;
    }

    @Test
    @DisplayName("D-113: production refuses LOCAL bill storage, set or left at its default")
    void productionRefusesLocalInvoiceStorage() {
        var env = production();
        env.setProperty("costonomy.mp.invoices.reader.provider", "HTTP");
        readerSignIn(env);
        // storage not set: LOCAL is the default, the forgotten-setting case.
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.invoices.storage.provider");
        env.setProperty("costonomy.mp.invoices.storage.provider", "local");
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.invoices.storage.provider");
        env.setProperty("costonomy.mp.invoices.storage.provider", "S3");
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-113: production refuses the FAKE bill reader, set or left at its default")
    void productionRefusesFakeInvoiceReader() {
        var env = production();
        env.setProperty("costonomy.mp.invoices.storage.provider", "S3");
        readerSignIn(env);
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.invoices.reader.provider");
        env.setProperty("costonomy.mp.invoices.reader.provider", "FAKE");
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.invoices.reader.provider");
        env.setProperty("costonomy.mp.invoices.reader.provider", "HTTP");
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-113: locally, LOCAL storage and the FAKE reader are fine")
    void localKeepsLocalInvoiceDefaults() {
        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-114: production refuses the HTTP reader without a cost-app sign-in, and never prints the values")
    void productionRefusesReaderWithoutSignIn() {
        var env = production();
        env.setProperty("costonomy.mp.invoices.storage.provider", "S3");
        env.setProperty("costonomy.mp.invoices.reader.provider", "HTTP");
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVOICE_READER_USERNAME");
        env.setProperty("costonomy.mp.invoices.reader.username", "placeholder-user");
        env.setProperty("costonomy.mp.invoices.reader.password", " ");
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("placeholder-user");
        env.setProperty("costonomy.mp.invoices.reader.password", "placeholder-not-a-secret");
        readerSignIn(env);
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }

    private static MockEnvironment realReader() {
        var env = production();
        env.setProperty("costonomy.mp.invoices.storage.provider", "S3");
        env.setProperty("costonomy.mp.invoices.reader.provider", "HTTP");
        readerSignIn(env);
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
        return env;
    }

    private static void refused(MockEnvironment env, String setting, String secretValue) {
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(setting)
                .satisfies(e -> {
                    if (secretValue != null) {
                        org.assertj.core.api.Assertions.assertThat(e.getMessage()).doesNotContain(secretValue);
                    }
                });
    }

    @Test
    @DisplayName("D-115 (M8): production refuses a static token, a blank sign-in, a non-https base URL, user-id or outlet 0, the fallback and an empty or bad outlet map; never printing a value")
    void productionRefusesWeakReaderSettings() {
        var token = realReader();
        token.setProperty("costonomy.mp.invoices.reader.token", "placeholder-static-token-4471");
        refused(token, "INVOICE_READER_TOKEN", "placeholder-static-token-4471");

        var blankPassword = realReader();
        blankPassword.setProperty("costonomy.mp.invoices.reader.password", " ");
        refused(blankPassword, "INVOICE_READER_PASSWORD", null);

        var http = realReader();
        http.setProperty("costonomy.mp.invoices.reader.base-url", "http://cost.example.invalid");
        refused(http, "costonomy.mp.invoices.reader.base-url", "cost.example.invalid");

        var userZero = realReader();
        userZero.setProperty("costonomy.mp.invoices.reader.user-id", "0");
        refused(userZero, "costonomy.mp.invoices.reader.user-id", null);

        var outletZero = realReader();
        outletZero.setProperty("costonomy.mp.invoices.reader.outlet", "0");
        refused(outletZero, "costonomy.mp.invoices.reader.outlet", null);

        var fallback = realReader();
        fallback.setProperty("costonomy.mp.invoices.cost-outlet-fallback", "true");
        refused(fallback, "cost-outlet-fallback", null);

        var noMap = realReader();
        noMap.setProperty("costonomy.mp.invoices.cost-outlet-map", "");
        refused(noMap, "INVOICE_COST_OUTLET_MAP", null);

        var badMap = realReader();
        badMap.setProperty("costonomy.mp.invoices.cost-outlet-map", "1:5,secret-ish-9931");
        refused(badMap, "costonomy.mp.invoices.cost-outlet-map", "secret-ish-9931");

        // Locally the fallback and an empty map are fine.
        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        local.setProperty("costonomy.mp.invoices.cost-outlet-fallback", "true");
        local.setProperty("costonomy.mp.invoices.reader.token", "placeholder-static-token-4471");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a production profile with no JWT secret, a short one, or the one that was committed is refused")
    void productionJwtSecretMustBeReal() {
        for (String bad : new String[] {"", "short",
                "SWt/kWYyraXKIm3nlqnJSL9vSqmPLkCT7GdZyKa4jDU="}) {
            var env = new MockEnvironment();
            env.setActiveProfiles("prod");
            env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
            env.setProperty("costonomy.mp.providers.otp", "MSG91");
            env.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
            realInvoices(env);
            env.setProperty("costonomy.mp.jwt.secret", bad);

            assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.secret");
        }
    }

    @Test
    @DisplayName("production with Pidge as delivery provider but no webhook secret is refused")
    void productionPidgeNeedsWebhookSecret() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        env.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        realInvoices(env);
        env.setProperty("costonomy.mp.providers.delivery", "PIDGE");

        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pidge.webhook-secret");

        env.setProperty("costonomy.mp.pidge.webhook-secret", "placeholder-not-a-secret");
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }
}
