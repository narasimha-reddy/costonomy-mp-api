package com.costonomy.mp.supplier;

import com.costonomy.mp.supplier.domain.SupplierLifecycleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static com.costonomy.mp.supplier.domain.SupplierLifecycleStatus.*;

/** Doc 03 §2. */
class SupplierLifecycleTest {

    @Test
    @DisplayName("the happy path runs INVITED → REGISTERED → PENDING → VERIFIED → ACTIVE")
    void happyPath() {
        assertThat(INVITED.canTransitionTo(REGISTERED)).isTrue();
        assertThat(REGISTERED.canTransitionTo(VERIFICATION_PENDING)).isTrue();
        assertThat(VERIFICATION_PENDING.canTransitionTo(VERIFIED)).isTrue();
        assertThat(VERIFIED.canTransitionTo(ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("verification cannot be skipped on the way to trading")
    void cannotSkipVerification() {
        // Doc 03 §2: "Verification failure must not silently activate supplier."
        // The same applies to never verifying at all.
        assertThat(REGISTERED.canTransitionTo(ACTIVE)).isFalse();
        assertThat(REGISTERED.canTransitionTo(VERIFIED)).isFalse();
        assertThat(INVITED.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("being verified is not the same as being allowed to trade")
    void verifiedIsNotActive() {
        // Verification confirms identity; activation is the separate decision to
        // let the supplier receive orders.
        assertThat(VERIFIED.canTransitionTo(ACTIVE)).isTrue();
        assertThat(VERIFIED).isNotEqualTo(ACTIVE);
    }

    @Test
    @DisplayName("a rejected verification returns the supplier to REGISTERED to resubmit")
    void rejectionAllowsResubmission() {
        assertThat(VERIFICATION_PENDING.canTransitionTo(REGISTERED)).isTrue();
    }

    @Test
    @DisplayName("a supplier can go offline and come back without re-verifying")
    void offlineIsReversible() {
        assertThat(ACTIVE.canTransitionTo(OFFLINE)).isTrue();
        assertThat(OFFLINE.canTransitionTo(ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("a suspended supplier can only be reactivated, never quietly set offline")
    void suspensionIsAnOperationsDecision() {
        assertThat(SUSPENDED.allowedTransitions()).containsExactly(ACTIVE);
        assertThat(SUSPENDED.canTransitionTo(OFFLINE)).isFalse();
    }

    @Test
    @DisplayName("a supplier can be suspended from any trading state")
    void suspensionIsAlwaysReachable() {
        for (SupplierLifecycleStatus status : values()) {
            if (status == SUSPENDED) continue;
            if (status == INVITED) continue;  // nothing to suspend yet
            assertThat(status.canTransitionTo(SUSPENDED))
                    .describedAs("%s should be suspendable", status)
                    .isTrue();
        }
    }
}
