package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.provider.pidge.PidgeApiClient;
import com.costonomy.mp.delivery.provider.pidge.PidgeDeliveryProvider;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two adapters' activation switches are genuinely independent —
 * the design point of {@link BorzoDeliveryProvider}'s
 * {@code @ConditionalOnProperty}. Pidge's own annotation is pinned to a single
 * value of {@code costonomy.mp.providers.delivery}, so Borzo deliberately uses
 * a different property ({@code costonomy.mp.borzo.enabled}) rather than
 * sharing it — otherwise "alongside Pidge, not instead of it" would not be
 * possible to configure.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full
 * {@code @SpringBootTest}/Testcontainers IT: what is under test is Spring's
 * conditional bean wiring, which needs no database.
 */
class BorzoPidgeCoexistenceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(
                    PidgeProperties.class, PidgeApiClient.class, PidgeDeliveryProvider.class,
                    BorzoProperties.class, BorzoApiClient.class, BorzoDeliveryProvider.class);

    @Test
    @DisplayName("both adapters register when Pidge's switch names PIDGE and Borzo's own flag is on")
    void bothProvidersCoexist() {
        runner.withPropertyValues(
                "costonomy.mp.providers.delivery=PIDGE",
                "costonomy.mp.borzo.enabled=true"
        ).run(context -> {
            assertThat(context).hasSingleBean(PidgeDeliveryProvider.class);
            assertThat(context).hasSingleBean(BorzoDeliveryProvider.class);
        });
    }

    @Test
    @DisplayName("Borzo stays off when its own flag is absent, even while Pidge is the active provider")
    void borzoDefaultsOffIndependentlyOfPidge() {
        runner.withPropertyValues(
                "costonomy.mp.providers.delivery=PIDGE"
        ).run(context -> {
            assertThat(context).hasSingleBean(PidgeDeliveryProvider.class);
            assertThat(context).doesNotHaveBean(BorzoDeliveryProvider.class);
        });
    }

    @Test
    @DisplayName("Borzo can be the only Costonomy provider on, independent of the MOCK/PIDGE switch")
    void borzoAloneWithoutPidge() {
        runner.withPropertyValues(
                "costonomy.mp.providers.delivery=MOCK",
                "costonomy.mp.borzo.enabled=true"
        ).run(context -> {
            assertThat(context).doesNotHaveBean(PidgeDeliveryProvider.class);
            assertThat(context).hasSingleBean(BorzoDeliveryProvider.class);
        });
    }
}
