package com.costonomy.mp.fooddelivery.domain;

import java.util.Set;

/**
 * Doc 06 §16, D-197.
 *
 * <pre>
 * REQUESTED → QUOTED → PROVIDER_SELECTED → DRIVER_ASSIGNED → AT_PICKUP → PICKED_UP
 *   → OUT_FOR_DELIVERY → ARRIVED → DELIVERED
 * </pre>
 *
 * <p>Failures: {@code QUOTE_FAILED}, {@code PROVIDER_UNAVAILABLE}, {@code FAILED}, {@code CANCELLED},
 * {@code RETURNED}.
 *
 * <p>Same rule as supplier delivery (doc 06 §7): a courier dropping out before pickup is a setback for one
 * attempt, not the end. The delivery goes back to {@code PROVIDER_SELECTED} on the next courier in the waterfall.
 * Once the food is picked up nobody may cancel it; what remains is delivery or failure.
 */
public enum FoodDeliveryStatus {

    REQUESTED,
    QUOTED,
    PROVIDER_SELECTED,
    DRIVER_ASSIGNED,
    AT_PICKUP,
    PICKED_UP,
    OUT_FOR_DELIVERY,
    ARRIVED,
    DELIVERED,

    /** Nobody would quote. Recoverable: quoting can be retried. */
    QUOTE_FAILED,
    /** Every eligible courier refused the booking. */
    PROVIDER_UNAVAILABLE,
    /** The courier could not hand the food over. Re-attemptable, or ends {@link #RETURNED}. */
    FAILED,
    CANCELLED,
    /** The food went back to the outlet. */
    RETURNED;

    /**
     * The domain event this status raises. Named here because the name is a contract that notifications, realtime
     * and analytics match on; the "FoodDelivery" prefix keeps them from colliding with supplier delivery's
     * {@code DriverAssigned} and friends.
     */
    public String eventName() {
        return switch (this) {
            case REQUESTED -> "FoodDeliveryRequested";
            case QUOTED -> "FoodDeliveryQuoted";
            case PROVIDER_SELECTED -> "FoodDeliveryProviderSelected";
            case DRIVER_ASSIGNED -> "FoodDeliveryDriverAssigned";
            case AT_PICKUP -> "FoodDeliveryDriverAtPickup";
            case PICKED_UP -> "FoodDeliveryPickedUp";
            case OUT_FOR_DELIVERY -> "FoodDeliveryOutForDelivery";
            case ARRIVED -> "FoodDeliveryArrived";
            case DELIVERED -> "FoodDeliveryDelivered";
            case QUOTE_FAILED -> "FoodDeliveryQuoteFailed";
            case PROVIDER_UNAVAILABLE -> "FoodDeliveryProviderUnavailable";
            case FAILED -> "FoodDeliveryFailed";
            case CANCELLED -> "FoodDeliveryCancelled";
            case RETURNED -> "FoodDeliveryReturned";
        };
    }

    /** Progress order, used to refuse a provider event that would move a delivery backwards. */
    public int rank() {
        return switch (this) {
            case REQUESTED -> 0;
            case QUOTE_FAILED, QUOTED -> 1;
            case PROVIDER_UNAVAILABLE, PROVIDER_SELECTED -> 2;
            case DRIVER_ASSIGNED -> 3;
            case AT_PICKUP -> 4;
            case PICKED_UP -> 5;
            case OUT_FOR_DELIVERY -> 6;
            case FAILED, ARRIVED -> 7;
            case DELIVERED, CANCELLED, RETURNED -> 8;
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == RETURNED;
    }

    /** Whether the food is with a courier. */
    public boolean isInFlight() {
        return this == PICKED_UP || this == OUT_FOR_DELIVERY || this == ARRIVED;
    }

    /** Whether a rider exists to track. Tracking starts at assignment. */
    public boolean isTrackable() {
        return rank() >= DRIVER_ASSIGNED.rank() && !isTerminal();
    }

    /** Whether the restaurant may still cancel. Not once the courier has the food. */
    public boolean isCancellable() {
        return !isTerminal() && rank() < PICKED_UP.rank();
    }

    /** Whether cancelling here returns the fee (doc 06 §16): only before pickup. */
    public boolean refundsFeeOnCancel() {
        return isCancellable();
    }

    public Set<FoodDeliveryStatus> allowedTransitions() {
        return switch (this) {
            case REQUESTED -> Set.of(QUOTED, QUOTE_FAILED, CANCELLED);
            case QUOTED -> Set.of(PROVIDER_SELECTED, PROVIDER_UNAVAILABLE, CANCELLED);
            // FAILED from here: no courier took it within the retry window (failure code NO_COURIER).
            case QUOTE_FAILED -> Set.of(QUOTED, QUOTE_FAILED, CANCELLED, FAILED);
            // PROVIDER_SELECTED to itself: the waterfall booked the next courier after one timed out.
            case PROVIDER_SELECTED -> Set.of(DRIVER_ASSIGNED, PROVIDER_SELECTED, PROVIDER_UNAVAILABLE, CANCELLED);
            case PROVIDER_UNAVAILABLE -> Set.of(PROVIDER_SELECTED, QUOTED, CANCELLED, FAILED);
            // A courier cancelling before pickup sends the delivery back to selection (reassign).
            case DRIVER_ASSIGNED -> Set.of(AT_PICKUP, PICKED_UP, PROVIDER_SELECTED, CANCELLED);
            case AT_PICKUP -> Set.of(PICKED_UP, PROVIDER_SELECTED, CANCELLED);
            case PICKED_UP -> Set.of(OUT_FOR_DELIVERY, ARRIVED, DELIVERED, FAILED);
            case OUT_FOR_DELIVERY -> Set.of(ARRIVED, DELIVERED, FAILED);
            case ARRIVED -> Set.of(DELIVERED, FAILED);
            case FAILED -> Set.of(OUT_FOR_DELIVERY, DELIVERED, RETURNED);
            case DELIVERED, CANCELLED, RETURNED -> Set.of();
        };
    }

    public boolean canTransitionTo(FoodDeliveryStatus target) {
        return allowedTransitions().contains(target);
    }
}
