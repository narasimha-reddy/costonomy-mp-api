package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliverySelection;
import com.costonomy.mp.delivery.domain.DeliverySelection.Candidate;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider selection and the delivery state machine. Doc 06 §4, §5, §7.
 *
 * <p>Selection is the part of delivery most likely to be quietly wrong, because
 * with one candidate every strategy agrees — which is also why there are two mock
 * providers rather than one.
 */
class DeliverySelectionTest {

    private static Candidate candidate(String code, String amount, int eta, int priority) {
        return new Candidate(code, new BigDecimal(amount), eta, priority);
    }

    @Nested
    @DisplayName("choosing a partner")
    class Choosing {

        @Test
        @DisplayName("the cheapest one that meets the deadline wins")
        void cheapestWithinEta() {
            // Doc 06 §4, verbatim: lowest cost meeting the required ETA.
            var selected = DeliverySelection.select(List.of(
                    candidate("FAST", "120.00", 20, 10),
                    candidate("CHEAP", "70.00", 40, 20)), 45);

            assertThat(selected).isPresent();
            assertThat(selected.get().providerCode()).isEqualTo("CHEAP");
        }

        @Test
        @DisplayName("a cheaper partner that misses the deadline doesn't win")
        void tooSlowIsExcluded() {
            // The half of the rule that a price-only implementation gets wrong, and
            // the reason "cheapest" alone is not the rule.
            var selected = DeliverySelection.select(List.of(
                    candidate("FAST", "120.00", 20, 10),
                    candidate("CHEAP", "70.00", 90, 20)), 30);

            assertThat(selected.get().providerCode()).isEqualTo("FAST");
        }

        @Test
        @DisplayName("with no deadline, price alone decides")
        void noDeadlineMeansCheapest() {
            var selected = DeliverySelection.select(List.of(
                    candidate("FAST", "120.00", 20, 10),
                    candidate("CHEAP", "70.00", 90, 20)), null);

            assertThat(selected.get().providerCode()).isEqualTo("CHEAP");
        }

        @Test
        @DisplayName("when nobody meets the deadline, the fastest carries it")
        void fallsBackToFastest() {
            // The goods still need to move. Late is better than not at all — but
            // the fallback orders by time, because lateness is now the problem.
            var selected = DeliverySelection.select(List.of(
                    candidate("SLOW_CHEAP", "70.00", 120, 20),
                    candidate("LESS_SLOW", "120.00", 60, 10)), 30);

            assertThat(selected.get().providerCode()).isEqualTo("LESS_SLOW");
        }

        @Test
        @DisplayName("a dead heat is broken the same way every time")
        void tiesAreDeterministic() {
            // Not "either is acceptable". A non-deterministic selection is a test
            // that passes until the map iteration order changes.
            var selected = DeliverySelection.select(List.of(
                    candidate("B", "80.00", 30, 50),
                    candidate("A", "80.00", 30, 10)), 60);

            assertThat(selected.get().providerCode()).isEqualTo("A");
        }

        @Test
        @DisplayName("price beats priority")
        void priorityIsOnlyATieBreak() {
            // Priority exists to settle a draw, not to override the rule. A
            // preferred partner charging more is still charging the restaurant more.
            var selected = DeliverySelection.select(List.of(
                    candidate("PREFERRED", "150.00", 25, 1),
                    candidate("OTHER", "90.00", 25, 99)), 60);

            assertThat(selected.get().providerCode()).isEqualTo("OTHER");
        }

        @Test
        @DisplayName("partners who couldn't quote are not candidates")
        void unquotedAreIgnored() {
            var selected = DeliverySelection.select(List.of(
                    new Candidate("FAILED", null, null, 10),
                    candidate("OK", "90.00", 25, 20)), 60);

            assertThat(selected.get().providerCode()).isEqualTo("OK");
        }

        @Test
        @DisplayName("nobody quoting means nobody is selected")
        void noCandidatesMeansEmpty() {
            assertThat(DeliverySelection.select(List.of(), 30)).isEmpty();
            assertThat(DeliverySelection.select(
                    List.of(new Candidate("FAILED", null, null, 10)), 30)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the delivery state machine")
    class StateMachine {

        @Test
        @DisplayName("the documented happy path is walkable end to end")
        void happyPath() {
            // Doc 06 §5, in order.
            var path = List.of(
                    DeliveryStatus.DELIVERY_REQUESTED, DeliveryStatus.QUOTE_RECEIVED,
                    DeliveryStatus.PROVIDER_SELECTED, DeliveryStatus.DRIVER_ASSIGNED,
                    DeliveryStatus.DRIVER_AT_PICKUP, DeliveryStatus.PICKED_UP,
                    DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_AT_DESTINATION,
                    DeliveryStatus.DELIVERED);

            for (int i = 0; i < path.size() - 1; i++) {
                assertThat(path.get(i).canTransitionTo(path.get(i + 1)))
                        .describedAs("%s → %s", path.get(i), path.get(i + 1))
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a failed attempt can go back out to another partner")
        void failuresAreRecoverable() {
            // Doc 06 §7: every failure below is answered by reassignment, so none
            // of them may be a dead end.
            for (DeliveryStatus failure : List.of(
                    DeliveryStatus.DRIVER_CANCELLED, DeliveryStatus.PICKUP_FAILED,
                    DeliveryStatus.PROVIDER_UNAVAILABLE, DeliveryStatus.DELIVERY_FAILED,
                    DeliveryStatus.QUOTE_FAILED)) {

                assertThat(failure.isRecoverableFailure())
                        .describedAs("%s is recoverable", failure).isTrue();
                assertThat(failure.allowedTransitions())
                        .describedAs("%s leads somewhere", failure).isNotEmpty();
                assertThat(failure.isTerminal()).isFalse();
            }
        }

        @Test
        @DisplayName("only delivered and cancelled are final")
        void onlyTwoTerminalStates() {
            for (DeliveryStatus status : DeliveryStatus.values()) {
                boolean expected = status == DeliveryStatus.DELIVERED
                        || status == DeliveryStatus.CANCELLED;
                assertThat(status.isTerminal())
                        .describedAs("%s is terminal", status).isEqualTo(expected);
                if (expected) {
                    assertThat(status.allowedTransitions()).isEmpty();
                }
            }
        }

        @Test
        @DisplayName("a picked-up delivery can't be cancelled")
        void noCancellationOnceCollected() {
            // Doc 01 §13: once the goods have left, the path is a return or a
            // dispute. Cancelling would leave a driver holding stock nobody owns.
            for (DeliveryStatus inFlight : List.of(
                    DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT,
                    DeliveryStatus.ARRIVED_AT_DESTINATION)) {

                assertThat(inFlight.isInFlight()).isTrue();
                assertThat(inFlight.canTransitionTo(DeliveryStatus.CANCELLED))
                        .describedAs("%s → CANCELLED", inFlight).isFalse();
            }
        }

        @Test
        @DisplayName("an event that would move a delivery backwards is detectable")
        void ranksOrderTheLifecycle() {
            // What DeliveryEventService compares to spot an out-of-order provider
            // event. Doc 06 §12.
            assertThat(DeliveryStatus.DRIVER_ASSIGNED.rank())
                    .isLessThan(DeliveryStatus.PICKED_UP.rank());
            assertThat(DeliveryStatus.PICKED_UP.rank())
                    .isLessThan(DeliveryStatus.DELIVERED.rank());
            // A failure ranks with the stage it failed at, so a late DRIVER_ASSIGNED
            // after a cancellation is not treated as progress.
            assertThat(DeliveryStatus.DRIVER_CANCELLED.rank())
                    .isEqualTo(DeliveryStatus.DRIVER_ASSIGNED.rank());
        }

        @Test
        @DisplayName("tracking starts at assignment and stops at the end")
        void trackableWindow() {
            // Doc 06 §8: tracking begins once a driver exists. Before that there is
            // nobody to track, and a position would be fabricated.
            assertThat(DeliveryStatus.DELIVERY_REQUESTED.isTrackable()).isFalse();
            assertThat(DeliveryStatus.PROVIDER_SELECTED.isTrackable()).isFalse();
            assertThat(DeliveryStatus.DRIVER_ASSIGNED.isTrackable()).isTrue();
            assertThat(DeliveryStatus.IN_TRANSIT.isTrackable()).isTrue();
            assertThat(DeliveryStatus.DELIVERED.isTrackable()).isFalse();
            // And a driver who cancelled is no longer anywhere worth showing.
            assertThat(DeliveryStatus.DRIVER_CANCELLED.isTrackable()).isFalse();
        }
    }

    @Nested
    @DisplayName("delivery modes")
    class Modes {

        @Test
        @DisplayName("only supplier own delivery is supplier-reported")
        void supplierReportsOnlyItsOwn() {
            // §23A.38: on a partner delivery a supplier must not be able to claim a
            // pickup someone else made.
            assertThat(DeliveryMode.SUPPLIER_OWN.isSupplierReported()).isTrue();
            assertThat(DeliveryMode.COSTONOMY.isSupplierReported()).isFalse();
        }

        @Test
        @DisplayName("only Costonomy delivery is tracked")
        void ownDeliveryHasNoTracking() {
            // Doc 06 §2: no live tracking on own delivery, initially. Showing a map
            // would mean inventing the van's position.
            assertThat(DeliveryMode.SUPPLIER_OWN.isTracked()).isFalse();
            assertThat(DeliveryMode.COSTONOMY.isTracked()).isTrue();
        }
    }
}
