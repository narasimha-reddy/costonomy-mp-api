package com.costonomy.mp.procurement;

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
        @DisplayName("rejection and expiry are both reachable and distinct")
        void rejectionAndExpiryAreSeparate() {
            // Doc 01 §12 rule 11. A supplier declining is a decision; not answering
            // is a failure to respond. They feed different performance signals and
            // a restaurant reads them differently.
            assertThat(PENDING_ACCEPTANCE.canTransitionTo(REJECTED)).isTrue();
            assertThat(PENDING_ACCEPTANCE.canTransitionTo(EXPIRED)).isTrue();
            assertThat(REJECTED).isNotEqualTo(EXPIRED);
        }

        @Test
        @DisplayName("both acceptance and expiry are legal from pending — the race is real")
        void theRaceIsReal() {
            // Nothing in the enum prevents both happening; optimistic locking on
            // the aggregate is what makes exactly one win (doc 03 §5, doc 10 §2).
            assertThat(PENDING_ACCEPTANCE.allowedTransitions())
                    .contains(CONFIRMED, PARTIALLY_ACCEPTED, EXPIRED);
        }

        @Test
        @DisplayName("partial acceptance is a first-class outcome")
        void partialIsFirstClass() {
            assertThat(PARTIALLY_ACCEPTED.isAccepted()).isTrue();
            assertThat(PARTIALLY_ACCEPTED.canTransitionTo(PREPARING)).isTrue();
        }

        @Test
        @DisplayName("an expired order cannot then be accepted")
        void expiredCannotBeAccepted() {
            // Doc 01 §12 and guardrail: a supplier cannot accept an expired order.
            assertThat(EXPIRED.canTransitionTo(CONFIRMED)).isFalse();
            assertThat(EXPIRED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("nothing can be cancelled once it is out for delivery")
        void noCancellationAfterPickup() {
            // Doc 01 §13: once goods have left, the path is return or dispute.
            assertThat(READY_FOR_PICKUP.canTransitionTo(CANCELLED)).isFalse();
            assertThat(OUT_FOR_DELIVERY.canTransitionTo(CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("an unfulfilled order is one nobody committed to")
        void unfulfilledMeansNoCommitment() {
            assertThat(REJECTED.isUnfulfilled()).isTrue();
            assertThat(EXPIRED.isUnfulfilled()).isTrue();
            assertThat(CANCELLED.isUnfulfilled()).isTrue();
            assertThat(CONFIRMED.isUnfulfilled()).isFalse();
            assertThat(PARTIALLY_ACCEPTED.isUnfulfilled()).isFalse();
        }

        @Test
        @DisplayName("the delivery path runs forward only")
        void deliveryPathIsForwardOnly() {
            assertThat(DELIVERED.canTransitionTo(OUT_FOR_DELIVERY)).isFalse();
            assertThat(COMPLETED.allowedTransitions()).isEmpty();
        }
    }
}
