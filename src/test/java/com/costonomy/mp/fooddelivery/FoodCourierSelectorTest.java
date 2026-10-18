package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.domain.ProviderReliability;
import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;
import com.costonomy.mp.delivery.domain.ReliabilitySettings;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.delivery.service.ProviderReliabilityService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryQuote;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryQuoteRepository;
import com.costonomy.mp.fooddelivery.service.FoodCourierSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Which couriers a food delivery asks, and what is recorded about the choice. */
@ExtendWith(MockitoExtension.class)
class FoodCourierSelectorTest {

    @Mock private FoodDeliveryQuoteRepository quotes;
    @Mock private FoodDeliveryAttemptRepository attempts;
    @Mock private ProviderReliabilityService reliability;
    @Mock private DeliveryProviderRegistry registry;

    private final ReliabilitySettings settings = new ReliabilitySettings();
    private final java.time.Instant now = java.time.Instant.parse("2026-10-18T12:00:00Z");
    private FoodCourierSelector selector;
    private FoodDelivery delivery;

    @BeforeEach
    void setUp() {
        selector = new FoodCourierSelector(quotes, attempts, reliability, registry);
        delivery = new FoodDelivery();
        delivery.setId(5L);
        lenient().when(reliability.settings()).thenReturn(settings);
        lenient().when(registry.record(any())).thenReturn(new DeliveryProviderRecord());
        lenient().when(attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(5L)).thenReturn(List.of());
    }

    private static FoodDeliveryQuote quote(String code, String amount, int eta) {
        var q = new FoodDeliveryQuote();
        q.setFoodDeliveryId(5L);
        q.setProviderCode(code);
        q.setStatus("QUOTED");
        q.setAmount(new BigDecimal(amount));
        q.setEtaMinutes(eta);
        return q;
    }

    private static FoodDeliveryAttempt attempt(String code, int n) {
        var a = new FoodDeliveryAttempt();
        a.setProviderCode(code);
        a.setAttemptNumber(n);
        return a;
    }

    private void quotesAre(FoodDeliveryQuote... q) {
        when(quotes.findByFoodDeliveryIdOrderByIdAsc(5L)).thenReturn(List.of(q));
    }

    private void reliabilityIs(Map<String, ProviderReliability> m) {
        when(reliability.assess(any())).thenReturn(m);
    }

    private static ProviderReliability rel(String code, double score, BreakerState state) {
        return new ProviderReliability(code, score, state, 0.9, 12);
    }

    @Test
    @DisplayName("a first attempt asks the cheapest courier first and records it as the cheapest")
    void firstAttempt() {
        quotesAre(quote("PIDGE", "40", 20), quote("BORZO", "55", 20));
        reliabilityIs(Map.of("PIDGE", rel("PIDGE", 0.8, BreakerState.CLOSED), "BORZO", rel("BORZO", 0.95, BreakerState.CLOSED)));

        var choices = selector.order(delivery, null, now);

        assertThat(choices).extracting(c -> c.quote().getProviderCode()).containsExactly("PIDGE", "BORZO");
        assertThat(choices.get(0).rank()).isEqualTo(1);
        assertThat(choices.get(0).reason()).isEqualTo("CHEAPEST");
        assertThat(choices.get(0).breakerState()).isEqualTo("CLOSED");
        assertThat(choices.get(0).score()).isEqualByComparingTo("0.8000");
    }

    @Test
    @DisplayName("after a failure the more reliable courier within the cost bound is asked first, and the reason says so")
    void afterFailure() {
        quotesAre(quote("PIDGE", "40", 20), quote("BORZO", "55", 20), quote("PORTER", "60", 20));
        when(attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(5L)).thenReturn(List.of(attempt("PIDGE", 1)));
        reliabilityIs(Map.of("BORZO", rel("BORZO", 0.70, BreakerState.CLOSED), "PORTER", rel("PORTER", 0.97, BreakerState.CLOSED),
                "PIDGE", rel("PIDGE", 0.3, BreakerState.CLOSED)));

        var choices = selector.order(delivery, null, now);

        assertThat(choices).extracting(c -> c.quote().getProviderCode()).containsExactly("PORTER", "BORZO");
        assertThat(choices.get(0).reason()).isEqualTo("RELIABILITY");
    }

    @Test
    @DisplayName("a courier with an open breaker is skipped, and the decision record says it was")
    void openBreakerIsSkippedAndRecorded() throws Exception {
        quotesAre(quote("PIDGE", "40", 20), quote("BORZO", "55", 20));
        reliabilityIs(Map.of("PIDGE", rel("PIDGE", 0.2, BreakerState.OPEN), "BORZO", rel("BORZO", 0.9, BreakerState.CLOSED)));

        var choices = selector.order(delivery, null, now);

        assertThat(choices).extracting(c -> c.quote().getProviderCode()).containsExactly("BORZO");
        assertThat(choices.get(0).reason()).isEqualTo("ONLY_OPTION");
        JsonNode decision = new ObjectMapper().readTree(choices.get(0).decisionJson());
        assertThat(decision.at("/skippedBreakerOpen/0").asText()).isEqualTo("PIDGE");
        assertThat(decision.at("/candidates/0/provider").asText()).isEqualTo("BORZO");
        assertThat(decision.at("/candidates/0/breaker").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a recovering courier is probed last and labelled as a probe")
    void probe() {
        quotesAre(quote("PIDGE", "40", 20), quote("BORZO", "55", 20));
        reliabilityIs(Map.of("PIDGE", rel("PIDGE", 0.4, BreakerState.HALF_OPEN), "BORZO", rel("BORZO", 0.9, BreakerState.CLOSED)));

        var choices = selector.order(delivery, null, now);

        assertThat(choices).extracting(c -> c.quote().getProviderCode()).containsExactly("BORZO", "PIDGE");
        assertThat(choices.get(1).reason()).isEqualTo("PROBE");
    }

    @Test
    @DisplayName("the fee the restaurant agreed to is a hard cap, and the delivery's deadline puts fast couriers first")
    void capAndDeadline() {
        delivery.setMaxDeliveryMinutes(25);
        quotesAre(quote("SLOW", "30", 60), quote("FAST", "50", 20), quote("DEAR", "90", 15));
        reliabilityIs(Map.of());

        var choices = selector.order(delivery, new BigDecimal("60"), now);

        assertThat(choices).extracting(c -> c.quote().getProviderCode()).containsExactly("FAST", "SLOW");
    }

    @Test
    @DisplayName("expired, failed and unserviceable quotes are never offered")
    void unusableQuotesAreDropped() {
        var expired = quote("OLD", "30", 20);
        expired.setExpiresAt(now.minusSeconds(1));
        var failed = quote("DOWN", "30", 20);
        failed.setStatus("FAILED");
        var declined = quote("FAR", "30", 20);
        declined.setStatus("UNSERVICEABLE");
        quotesAre(expired, failed, declined, quote("OK", "50", 20));
        reliabilityIs(Map.of());

        assertThat(selector.order(delivery, null, now)).extracting(c -> c.quote().getProviderCode()).containsExactly("OK");
    }

    @Test
    @DisplayName("no usable quote means no choice, and the booking service ends the delivery")
    void nothingToAsk() {
        quotesAre();
        assertThat(selector.order(delivery, null, now)).isEmpty();
    }

    @Test
    @DisplayName("every courier tried already means nothing left to ask")
    void everyoneTried() {
        quotesAre(quote("PIDGE", "40", 20));
        when(attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(5L)).thenReturn(List.of(attempt("PIDGE", 1)));
        reliabilityIs(Map.of());

        assertThat(selector.order(delivery, null, now)).isEmpty();
    }
}
