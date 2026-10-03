package com.costonomy.mp.procurement;

import com.costonomy.mp.procurement.domain.DeliveryMode;
import com.costonomy.mp.procurement.domain.ProcurementStatus;
import com.costonomy.mp.procurement.domain.RequirementStatus;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.costonomy.mp.procurement.domain.SupplierOrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The three state machines. Doc 03 §3–5. */
class LifecycleTest {

    @Nested
    @DisplayName("requirement")
    class Requirements {

        @Test
        @DisplayName("a partially fulfilled requirement can be sourced again")
        void partialCanBeResourced() {
            // Guardrail 14. A supplier accepting 12 of 20 leaves 8 needed, and the
            // restaurant sources those elsewhere without retyping anything.
            assertThat(RequirementStatus.PARTIALLY_FULFILLED
                    .canTransitionTo(RequirementStatus.SOURCING)).isTrue();
        }

        @Test
        @DisplayName("a fulfilled requirement is terminal")
        void fulfilledIsTerminal() {
            // A delivered order that turns out short becomes a receiving
            // discrepancy and then a dispute (doc 26), not a reopened requirement —
            // reopening would lose the fact that it was delivered.
            assertThat(RequirementStatus.FULFILLED.isTerminal()).isTrue();
            assertThat(RequirementStatus.FULFILLED
                    .canTransitionTo(RequirementStatus.PARTIALLY_FULFILLED)).isFalse();
        }

        @Test
        @DisplayName("a cancelled requirement cannot come back")
        void cancelledIsTerminal() {
            assertThat(RequirementStatus.CANCELLED.isTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("procurement")
    class Procurements {

        @Test
        @DisplayName("a rejected approval returns to DRAFT so it can be fixed")
        void rejectionIsRecoverable() {
            assertThat(ProcurementStatus.PENDING_APPROVAL
                    .canTransitionTo(ProcurementStatus.DRAFT)).isTrue();
        }

        @Test
        @DisplayName("a validated order can be revalidated when it goes stale")
        void readyCanRevalidate() {
            // Doc 01 §11 requires revalidation at checkout, so READY has to be able
            // to go back through validation rather than being a one-way door.
            assertThat(ProcurementStatus.READY.canTransitionTo(ProcurementStatus.VALIDATING)).isTrue();
            assertThat(ProcurementStatus.APPROVED.canTransitionTo(ProcurementStatus.VALIDATING)).isTrue();
        }

        @Test
        @DisplayName("a submitted order is terminal")
        void submittedIsTerminal() {
            assertThat(ProcurementStatus.SUBMITTED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("a failed submission can be retried")
        void failureIsRecoverable() {
            // Doc 03 §4 requires retry semantics: a supplier going offline mid-submit
            // should not cost the restaurant their cart.
            assertThat(ProcurementStatus.FAILED.canTransitionTo(ProcurementStatus.VALIDATING)).isTrue();
        }

        @Test
        @DisplayName("a draft cannot skip straight to submitted")
        void cannotSkipValidation() {
            assertThat(ProcurementStatus.DRAFT.canTransitionTo(ProcurementStatus.SUBMITTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("supplier order")
    class SupplierOrders {

        @Test
        @DisplayName("a funded order is confirmed — it is never pending an acceptance")
        void noSecondAcceptance() {
            // D-091. The supplier committed on the request and the restaurant paid
            // against that commitment, so there is nothing left to accept. DRAFT's
            // only forward step is CONFIRMED.
            assertThat(DRAFT.allowedTransitions(DeliveryMode.PICKUP))
                    .containsExactlyInAnyOrder(CONFIRMED, CANCELLED);
        }

        @Test
        @DisplayName("where an order goes after ready depends on who is carrying it")
        void theModeDecidesTheTail() {
            // The reason allowedTransitions takes a mode at all. Encoding both as
            // unconditionally legal would let a pickup be marked out for delivery.
            assertThat(READY_FOR_PICKUP.allowedTransitions(DeliveryMode.PICKUP))
                    .containsExactly(COMPLETED);
            assertThat(READY_FOR_PICKUP.allowedTransitions(DeliveryMode.SUPPLIER_DELIVERY))
                    .containsExactly(OUT_FOR_DELIVERY);
            assertThat(READY_FOR_PICKUP.allowedTransitions(DeliveryMode.COSTONOMY_DELIVERY))
                    .containsExactly(OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a collected order never passes through delivered")
        void pickupSkipsDelivered() {
            // Nothing delivered it, so DELIVERED would describe something that did
            // not happen.
            assertThat(READY_FOR_PICKUP.canTransitionTo(DELIVERED, DeliveryMode.PICKUP))
                    .isFalse();
            assertThat(READY_FOR_PICKUP.canTransitionTo(
                    OUT_FOR_DELIVERY, DeliveryMode.PICKUP)).isFalse();
        }

        @Test
        @DisplayName("nothing can be cancelled once it is out for delivery")
        void noCancellationAfterPickup() {
            // Doc 01 §13: once goods have left, the path is return or dispute.
            for (DeliveryMode mode : DeliveryMode.values()) {
                assertThat(READY_FOR_PICKUP.canTransitionTo(CANCELLED, mode)).isFalse();
                assertThat(OUT_FOR_DELIVERY.canTransitionTo(CANCELLED, mode)).isFalse();
            }
        }

        @Test
        @DisplayName("cancellation is the only unfulfilled ending left")
        void unfulfilledMeansCancelled() {
            // REJECTED and EXPIRED are gone with the acceptance that produced them.
            // A supplier who cannot fulfil cancels, and CancelledBy records that it
            // was them.
            assertThat(CANCELLED.isUnfulfilled()).isTrue();
            assertThat(CONFIRMED.isUnfulfilled()).isFalse();
            assertThat(COMPLETED.isUnfulfilled()).isFalse();
        }

        @Test
        @DisplayName("the delivery path runs forward only")
        void deliveryPathIsForwardOnly() {
            for (DeliveryMode mode : DeliveryMode.values()) {
                assertThat(DELIVERED.canTransitionTo(OUT_FOR_DELIVERY, mode)).isFalse();
                assertThat(COMPLETED.allowedTransitions(mode)).isEmpty();
                assertThat(CANCELLED.allowedTransitions(mode)).isEmpty();
            }
        }

        @Test
        @DisplayName("both endings are terminal whoever carried the goods")
        void terminalityDoesNotDependOnTheMode() {
            assertThat(COMPLETED.isTerminal()).isTrue();
            assertThat(CANCELLED.isTerminal()).isTrue();
            assertThat(CONFIRMED.isTerminal()).isFalse();
        }
    }
}
