package com.costonomy.mp.intent;

import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.intent.domain.IntentPolicy;
import com.costonomy.mp.intent.domain.IntentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rules an intent keeps without a database. */
class IntentDomainTest {

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("runs draft, open, answered, ordered")
        void happyPath() {
            assertThat(IntentStatus.DRAFT.canTransitionTo(IntentStatus.OPEN)).isTrue();
            assertThat(IntentStatus.OPEN.canTransitionTo(IntentStatus.RESPONSES_RECEIVED)).isTrue();
            assertThat(IntentStatus.RESPONSES_RECEIVED.canTransitionTo(IntentStatus.ORDERED))
                    .isTrue();
        }

        @Test
        @DisplayName("an ordered intent is finished with — cloning is the way to buy again")
        void orderedIsTerminal() {
            assertThat(IntentStatus.ORDERED.isTerminal()).isTrue();
            assertThat(IntentStatus.ORDERED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("an unanswered intent cannot be ordered from")
        void cannotSkipTheSupplier() {
            assertThat(IntentStatus.OPEN.canTransitionTo(IntentStatus.ORDERED)).isFalse();
            assertThat(IntentStatus.DRAFT.canTransitionTo(IntentStatus.ORDERED)).isFalse();
        }

        @Test
        @DisplayName("only a draft may be edited")
        void frozenOnceAnswered() {
            assertThat(IntentStatus.DRAFT.isEditable()).isTrue();
            // §20: once the supplier has answered, neither side may change it.
            assertThat(IntentStatus.OPEN.isEditable()).isFalse();
            assertThat(IntentStatus.RESPONSES_RECEIVED.isEditable()).isFalse();
        }

        @Test
        @DisplayName("an unused answer and an ignored request are different endings")
        void twoKindsOfExpiry() {
            assertThat(IntentStatus.OPEN.canTransitionTo(IntentStatus.EXPIRED)).isTrue();
            assertThat(IntentStatus.OPEN.canTransitionTo(IntentStatus.ORDER_CREATION_EXPIRED))
                    .isFalse();
            assertThat(IntentStatus.RESPONSES_RECEIVED
                    .canTransitionTo(IntentStatus.ORDER_CREATION_EXPIRED)).isTrue();
            assertThat(IntentStatus.RESPONSES_RECEIVED.canTransitionTo(IntentStatus.EXPIRED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("fulfilment")
    class Fulfilment {

        @Test
        @DisplayName("is computed from quantities, not from a status")
        void fromQuantities() {
            assertThat(IntentFulfilment.of(qty("20"), qty("20"))).isEqualTo(IntentFulfilment.FULFILLED);
            assertThat(IntentFulfilment.of(qty("20"), qty("8")))
                    .isEqualTo(IntentFulfilment.PARTIALLY_FULFILLED);
            assertThat(IntentFulfilment.of(qty("20"), qty("0")))
                    .isEqualTo(IntentFulfilment.NOT_FULFILLED);
        }

        @Test
        @DisplayName("no answer is not a refusal")
        void awaitingIsNotZero() {
            assertThat(IntentFulfilment.of(qty("20"), null)).isEqualTo(IntentFulfilment.AWAITING);
            assertThat(IntentFulfilment.of(qty("20"), qty("0")))
                    .isEqualTo(IntentFulfilment.NOT_FULFILLED);
        }

        // 20 and 20.0000 are one quantity and two BigDecimals. `equals` would
        // call a fully served intent partial, and only for suppliers who happened
        // to type a trailing zero.
        @Test
        @DisplayName("compares by value, so scale cannot make a full answer partial")
        void scaleDoesNotMatter() {
            assertThat(IntentFulfilment.of(qty("20"), qty("20.0000")))
                    .isEqualTo(IntentFulfilment.FULFILLED);
        }

        private BigDecimal qty(String value) {
            return new BigDecimal(value);
        }
    }

    @Nested
    @DisplayName("the order-creation window")
    class Window {

        @Test
        @DisplayName("defaults to five minutes and follows configuration")
        void configurable() {
            assertThat(policy(Map.of()).orderCreationWindowSeconds()).isEqualTo(300);
            assertThat(policy(Map.of("intent.orderCreationWindowSeconds", 600))
                    .orderCreationWindowSeconds()).isEqualTo(600);
        }

        // A zero would expire every intent the instant it was accepted, and the
        // failure would read as a broken countdown rather than a bad setting.
        @Test
        @DisplayName("is clamped, so a mistyped setting cannot make it absurd")
        void clamped() {
            assertThat(policy(Map.of("intent.orderCreationWindowSeconds", 0))
                    .orderCreationWindowSeconds()).isEqualTo(60);
            assertThat(policy(Map.of("intent.orderCreationWindowSeconds", 999_999))
                    .orderCreationWindowSeconds()).isEqualTo(24 * 60 * 60);
        }

        @Test
        @DisplayName("a deadline is the accepting instant plus the window frozen with it")
        void deadlineFromSnapshot() {
            var acceptedAt = Instant.parse("2026-09-18T10:00:00Z");
            assertThat(policy(Map.of()).orderCreationDeadline(acceptedAt, 300))
                    .isEqualTo(Instant.parse("2026-09-18T10:05:00Z"));
        }

        /**
         * The reason the window is stored rather than recomputed: an intent
         * accepted under a five-minute window keeps it even after the platform
         * moves to ten, so nobody gains or loses time retroactively.
         */
        @Test
        @DisplayName("a configuration change does not move a deadline already set")
        void snapshotSurvivesReconfiguration() {
            var acceptedAt = Instant.parse("2026-09-18T10:00:00Z");
            var intent = new Intent();
            intent.setAcceptedOrderCreationWindowSeconds(300);
            intent.setOrderCreationDeadline(
                    policy(Map.of()).orderCreationDeadline(acceptedAt, 300));

            var widened = policy(Map.of("intent.orderCreationWindowSeconds", 600));
            assertThat(widened.orderCreationWindowSeconds()).isEqualTo(600);
            // The intent's own deadline is untouched by the new setting.
            assertThat(intent.getOrderCreationDeadline())
                    .isEqualTo(Instant.parse("2026-09-18T10:05:00Z"));
            assertThat(intent.withinOrderWindow(Instant.parse("2026-09-18T10:04:59Z"))).isTrue();
            assertThat(intent.withinOrderWindow(Instant.parse("2026-09-18T10:05:01Z"))).isFalse();
        }

        @Test
        @DisplayName("an intent with no deadline is outside the window, not inside it")
        void absentDeadlineIsClosed() {
            // An unanswered intent has no window. Treating absent as open would
            // let an order be created before any supplier had agreed to anything.
            assertThat(new Intent().withinOrderWindow(Instant.now())).isFalse();
        }

        private IntentPolicy policy(Map<String, Integer> settings) {
            var config = mock(AppConfigService.class);
            var values = new HashMap<>(settings);
            when(config.getInt(anyString(), anyInt())).thenAnswer(call ->
                    values.getOrDefault(call.getArgument(0), call.getArgument(1)));
            return new IntentPolicy(config);
        }
    }
}
