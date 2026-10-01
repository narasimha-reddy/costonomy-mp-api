package com.costonomy.mp.payment;

import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.costonomy.mp.payment.domain.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Payment and refund state machines. Doc 03 §6–7. */
class PaymentLifecycleTest {

    @Nested
    @DisplayName("payment")
    class Payments {

        @Test
        @DisplayName("the happy path runs CREATED → AUTHORIZED → CAPTURE_PENDING → CAPTURED")
        void happyPath() {
            assertThat(CREATED.canTransitionTo(AUTHORIZED)).isTrue();
            assertThat(AUTHORIZED.canTransitionTo(CAPTURE_PENDING)).isTrue();
            assertThat(CAPTURE_PENDING.canTransitionTo(CAPTURED)).isTrue();
        }

        @Test
        @DisplayName("capture cannot be reached without authorisation")
        void captureNeedsAuthorization() {
            // Taking money that was never held is not a thing a payment system
            // should be able to express.
            assertThat(CREATED.canTransitionTo(CAPTURED)).isFalse();
            assertThat(CREATED.canTransitionTo(CAPTURE_PENDING)).isFalse();
        }

        @Test
        @DisplayName("a failed capture returns to AUTHORIZED so it can be retried")
        void captureFailureIsRecoverable() {
            // The money is still held, so the payment is exactly where it was. A
            // terminal failure here would strand an authorisation until it lapsed.
            assertThat(CAPTURE_PENDING.canTransitionTo(AUTHORIZED)).isTrue();
        }

        @Test
        @DisplayName("a captured payment cannot be released")
        void capturedCannotBeReleased() {
            // Money already taken comes back as a refund, which the customer sees
            // as a reversal. Releasing implies it was never taken.
            assertThat(CAPTURED.canTransitionTo(RELEASED)).isFalse();
            assertThat(CAPTURED.canTransitionTo(FULLY_REFUNDED)).isTrue();
        }

        @Test
        @DisplayName("a released or failed payment is terminal")
        void terminalStates() {
            assertThat(RELEASED.allowedTransitions()).isEmpty();
            assertThat(FAILED.allowedTransitions()).isEmpty();
            assertThat(FULLY_REFUNDED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("a partially refunded payment can be refunded again")
        void partialRefundsAccumulate() {
            assertThat(PARTIALLY_REFUNDED.canTransitionTo(PARTIALLY_REFUNDED)).isTrue();
            assertThat(PARTIALLY_REFUNDED.canTransitionTo(FULLY_REFUNDED)).isTrue();
        }

        @Test
        @DisplayName("an out-of-order authorisation cannot drag a captured payment backwards")
        void noBackwardsTransitions() {
            // Doc 03 §6 and doc 46: webhooks arrive out of order. A late
            // 'authorized' event describes the past, not the present.
            assertThat(CAPTURED.canTransitionTo(AUTHORIZED)).isFalse();
            assertThat(CAPTURED.canTransitionTo(CREATED)).isFalse();
            assertThat(AUTHORIZED.canTransitionTo(CREATED)).isFalse();
        }

        @Test
        @DisplayName("funds are secured from authorisation onward, and never before")
        void fundsSecuredIsTheGuardrail() {
            // Guardrail 16: this predicate decides whether a supplier may see an
            // order at all.
            assertThat(CREATED.fundsSecured()).isFalse();
            assertThat(FAILED.fundsSecured()).isFalse();
            assertThat(RELEASED.fundsSecured()).isFalse();

            assertThat(AUTHORIZED.fundsSecured()).isTrue();
            assertThat(CAPTURE_PENDING.fundsSecured()).isTrue();
            assertThat(CAPTURED.fundsSecured()).isTrue();
        }
    }

    @Nested
    @DisplayName("a cancelled order's debited payment (D-109)")
    class CancelPending {

        @Test
        @DisplayName("CANCEL_PENDING can only become CAPTURED, RELEASED or FAILED")
        void cancelPendingTransitions() {
            assertThat(CANCEL_PENDING.allowedTransitions()).containsExactlyInAnyOrder(CAPTURED, RELEASED, FAILED);
            for (var other : PaymentStatus.values()) {
                if (other != CAPTURED && other != RELEASED && other != FAILED) {
                    assertThat(CANCEL_PENDING.canTransitionTo(other)).describedAs("to " + other).isFalse();
                }
            }
        }

        @Test
        @DisplayName("it is neither secured nor holding: nothing may release the order or take the money for it")
        void cancelPendingIsNotFundsSecuredNorHolding() {
            assertThat(CANCEL_PENDING.fundsSecured()).isFalse();
            assertThat(CANCEL_PENDING.isHoldingFunds()).isFalse();
            assertThat(CANCEL_PENDING.isSettled()).isFalse();
        }

        @Test
        @DisplayName("an authorised payment may go to CANCEL_PENDING, and nothing else may")
        void authorizedMayGoToCancelPending() {
            assertThat(AUTHORIZED.canTransitionTo(CANCEL_PENDING)).isTrue();
            for (var other : PaymentStatus.values()) {
                if (other != AUTHORIZED) {
                    assertThat(other.canTransitionTo(CANCEL_PENDING)).describedAs("from " + other).isFalse();
                }
            }
        }

        @Test
        @DisplayName("FAILED stays terminal: late money reopens it through an explicit move, not a transition")
        void failedStaysTerminal() {
            assertThat(FAILED.allowedTransitions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refund")
    class Refunds {

        @Test
        @DisplayName("a failed refund can be retried on the same record")
        void failureIsRetryable() {
            // Doc 22: a retry must not create a second refund, which is why the
            // same row is retried rather than a new one raised.
            assertThat(RefundStatus.FAILED.canTransitionTo(RefundStatus.PROCESSING)).isTrue();
        }

        @Test
        @DisplayName("a completed refund is terminal")
        void completedIsTerminal() {
            assertThat(RefundStatus.COMPLETED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("a refund cannot skip straight to completed")
        void cannotSkipProcessing() {
            assertThat(RefundStatus.REQUESTED.canTransitionTo(RefundStatus.COMPLETED)).isFalse();
        }
    }
}
