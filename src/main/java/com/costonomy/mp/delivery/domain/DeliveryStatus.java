package com.costonomy.mp.delivery.domain;

import java.util.Set;

/**
 * Doc 03 §10 and doc 06 §5.
 *
 * <pre>
 * DELIVERY_REQUESTED → QUOTE_RECEIVED → PROVIDER_SELECTED → DRIVER_ASSIGNED
 *   → DRIVER_AT_PICKUP → PICKED_UP → IN_TRANSIT → ARRIVED_AT_DESTINATION → DELIVERED
 * </pre>
 *
 * <p>Failures: {@code QUOTE_FAILED}, {@code PROVIDER_UNAVAILABLE},
 * {@code DRIVER_CANCELLED}, {@code PICKUP_FAILED}, {@code DELIVERY_FAILED},
 * {@code CANCELLED}.
 *
 * <p><b>Most failures are not terminal.</b> A driver cancelling or a pickup
 * failing is a setback for one attempt, not the end of the consignment — doc 06 §7
 * says to reassign, and the delivery goes back to {@code PROVIDER_SELECTED} on a
 * new attempt rather than ending. Only {@code DELIVERED} and {@code CANCELLED}
 * are final, because only those mean the goods have stopped needing to move.
 */
public enum DeliveryStatus {

    DELIVERY_REQUESTED,
    QUOTE_RECEIVED,
    PROVIDER_SELECTED,
    DRIVER_ASSIGNED,
    DRIVER_AT_PICKUP,
    PICKED_UP,
    IN_TRANSIT,
    ARRIVED_AT_DESTINATION,
    DELIVERED,

    /** Nobody would quote. Recoverable: quoting can be retried. */
    QUOTE_FAILED,
    /** Everyone who quoted refused the booking. */
    PROVIDER_UNAVAILABLE,
    DRIVER_CANCELLED,
    PICKUP_FAILED,
    DELIVERY_FAILED,
    CANCELLED;

    /** Progress order, used to refuse an event that would move backwards. */
    public int rank() {
        return switch (this) {
            case DELIVERY_REQUESTED -> 0;
            case QUOTE_FAILED, QUOTE_RECEIVED -> 1;
            case PROVIDER_UNAVAILABLE, PROVIDER_SELECTED -> 2;
            case DRIVER_CANCELLED, DRIVER_ASSIGNED -> 3;
            case PICKUP_FAILED, DRIVER_AT_PICKUP -> 4;
            case PICKED_UP -> 5;
            case IN_TRANSIT -> 6;
            case DELIVERY_FAILED, ARRIVED_AT_DESTINATION -> 7;
            case DELIVERED, CANCELLED -> 8;
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /** Whether a reassignment can rescue this. Doc 06 §7. */
    public boolean isRecoverableFailure() {
        return this == QUOTE_FAILED || this == PROVIDER_UNAVAILABLE
                || this == DRIVER_CANCELLED || this == PICKUP_FAILED
                || this == DELIVERY_FAILED;
    }

    /** Whether the goods are with a courier. */
    public boolean isInFlight() {
        return this == PICKED_UP || this == IN_TRANSIT || this == ARRIVED_AT_DESTINATION;
    }

    /** Whether a driver exists to track. Doc 06 §8: tracking starts at assignment. */
    public boolean isTrackable() {
        return rank() >= DRIVER_ASSIGNED.rank() && !isTerminal() && this != DRIVER_CANCELLED;
    }

    public Set<DeliveryStatus> allowedTransitions() {
        return switch (this) {
            case DELIVERY_REQUESTED -> Set.of(QUOTE_RECEIVED, QUOTE_FAILED, CANCELLED);
            case QUOTE_RECEIVED -> Set.of(PROVIDER_SELECTED, PROVIDER_UNAVAILABLE, CANCELLED);
            case QUOTE_FAILED -> Set.of(QUOTE_RECEIVED, QUOTE_FAILED, CANCELLED);
            case PROVIDER_SELECTED -> Set.of(
                    DRIVER_ASSIGNED, DRIVER_CANCELLED, PROVIDER_UNAVAILABLE, CANCELLED);
            // A failed booking can be retried against another provider, which is
            // why PROVIDER_UNAVAILABLE leads back into selection rather than out.
            case PROVIDER_UNAVAILABLE -> Set.of(PROVIDER_SELECTED, QUOTE_RECEIVED, CANCELLED);
            case DRIVER_ASSIGNED -> Set.of(
                    DRIVER_AT_PICKUP, PICKED_UP, DRIVER_CANCELLED, PICKUP_FAILED, CANCELLED);
            case DRIVER_AT_PICKUP -> Set.of(PICKED_UP, PICKUP_FAILED, DRIVER_CANCELLED, CANCELLED);
            case DRIVER_CANCELLED, PICKUP_FAILED -> Set.of(
                    PROVIDER_SELECTED, DRIVER_ASSIGNED, CANCELLED);
            // Past pickup the goods are moving and cancellation is no longer ours
            // to choose — doc 01 §13. What remains is delivery or failure.
            case PICKED_UP -> Set.of(IN_TRANSIT, ARRIVED_AT_DESTINATION, DELIVERED, DELIVERY_FAILED);
            case IN_TRANSIT -> Set.of(ARRIVED_AT_DESTINATION, DELIVERED, DELIVERY_FAILED);
            case ARRIVED_AT_DESTINATION -> Set.of(DELIVERED, DELIVERY_FAILED);
            // A failed delivery can be re-attempted: the goods still exist and the
            // restaurant still needs them (doc 06 §7).
            case DELIVERY_FAILED -> Set.of(IN_TRANSIT, DELIVERED, PROVIDER_SELECTED, CANCELLED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(DeliveryStatus target) {
        return allowedTransitions().contains(target);
    }
}
