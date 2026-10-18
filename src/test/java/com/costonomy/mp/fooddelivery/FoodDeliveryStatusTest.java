package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;

import static com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodDeliveryStatusTest {

    @Test
    @DisplayName("the happy path walks forward one step at a time")
    void happyPath() {
        var path = new FoodDeliveryStatus[]{REQUESTED, QUOTED, PROVIDER_SELECTED, DRIVER_ASSIGNED, AT_PICKUP,
                PICKED_UP, OUT_FOR_DELIVERY, ARRIVED, DELIVERED};
        for (int i = 0; i < path.length - 1; i++) {
            assertThat(path[i].canTransitionTo(path[i + 1])).describedAs(path[i] + " -> " + path[i + 1]).isTrue();
            assertThat(path[i].rank()).isLessThan(path[i + 1].rank());
        }
    }

    @Test
    @DisplayName("terminal statuses allow no way out")
    void terminalIsFinal() {
        for (var s : EnumSet.of(DELIVERED, CANCELLED, RETURNED)) {
            assertThat(s.isTerminal()).isTrue();
            assertThat(s.allowedTransitions()).isEmpty();
        }
        assertThat(FAILED.isTerminal()).as("a failed handover can still be re-attempted or returned").isFalse();
    }

    @Test
    @DisplayName("a courier dropping out before pickup sends the delivery back to selection, not to a new row")
    void reassignmentBeforePickup() {
        assertThat(DRIVER_ASSIGNED.canTransitionTo(PROVIDER_SELECTED)).isTrue();
        assertThat(AT_PICKUP.canTransitionTo(PROVIDER_SELECTED)).isTrue();
        assertThat(PROVIDER_SELECTED.canTransitionTo(PROVIDER_SELECTED))
                .as("waterfall books the next courier after a timeout").isTrue();
        assertThat(PICKED_UP.canTransitionTo(PROVIDER_SELECTED)).as("not once the food is with the courier").isFalse();
    }

    @Test
    @DisplayName("cancelling is allowed, and refunds the fee, only before pickup")
    void cancelPolicy() {
        for (var s : EnumSet.of(REQUESTED, QUOTED, PROVIDER_SELECTED, DRIVER_ASSIGNED, AT_PICKUP,
                QUOTE_FAILED, PROVIDER_UNAVAILABLE)) {
            assertThat(s.isCancellable()).describedAs(s.name()).isTrue();
            assertThat(s.refundsFeeOnCancel()).describedAs(s.name()).isTrue();
            assertThat(s.canTransitionTo(CANCELLED)).describedAs(s.name()).isTrue();
        }
        for (var s : EnumSet.of(PICKED_UP, OUT_FOR_DELIVERY, ARRIVED, FAILED, DELIVERED, CANCELLED, RETURNED)) {
            assertThat(s.isCancellable()).describedAs(s.name()).isFalse();
            assertThat(s.refundsFeeOnCancel()).describedAs(s.name()).isFalse();
            assertThat(s.canTransitionTo(CANCELLED)).describedAs(s.name()).isFalse();
        }
    }

    @Test
    @DisplayName("a rider is trackable from assignment until the delivery ends")
    void trackable() {
        assertThat(PROVIDER_SELECTED.isTrackable()).isFalse();
        assertThat(DRIVER_ASSIGNED.isTrackable()).isTrue();
        assertThat(OUT_FOR_DELIVERY.isTrackable()).isTrue();
        assertThat(DELIVERED.isTrackable()).isFalse();
    }

    @Test
    @DisplayName("every status has its own event name, and none collides with a supplier delivery event")
    void eventNames() {
        var names = new HashSet<String>();
        for (var s : values()) {
            assertThat(s.eventName()).startsWith("FoodDelivery");
            assertThat(names.add(s.eventName())).describedAs("duplicate event name " + s.eventName()).isTrue();
        }
    }

    @Test
    @DisplayName("moveTo enforces the state machine")
    void moveTo() {
        var d = new FoodDelivery();
        d.moveTo(QUOTED);
        assertThat(d.getStatus()).isEqualTo(QUOTED);
        assertThatThrownBy(() -> d.moveTo(DELIVERED)).isInstanceOf(IllegalStateException.class);
        assertThat(d.getStatus()).as("a refused move changes nothing").isEqualTo(QUOTED);
    }

    @Test
    @DisplayName("tracking tokens are 32 hex characters and do not repeat")
    void trackingToken() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String t = FoodDelivery.newTrackingToken();
            assertThat(t).matches("[0-9a-f]{32}");
            assertThat(seen.add(t)).isTrue();
        }
        assertThat(new FoodDelivery().getTrackingToken()).matches("[0-9a-f]{32}");
    }
}
