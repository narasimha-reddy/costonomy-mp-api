package com.costonomy.mp.delivery.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A courier Mandi can dispatch to. Doc 06 §3.
 *
 * <p>Third-party providers and a future Costonomy fleet implement the same
 * interface, which is the point: doc 06 §1 requires delivery to be
 * provider-agnostic so that supplier own-delivery, Porter, Rapido and an in-house
 * fleet all produce the same order experience.
 *
 * <p><b>No provider DTO crosses this boundary.</b> Every type here is ours. A
 * provider's own status vocabulary is mapped in the adapter, because the day a
 * provider renames a status is not a day the domain should change.
 *
 * <p>Every method may throw {@link DeliveryProviderException}. A provider being
 * unreachable is normal and is the case doc 06 §7 is mostly about — the caller
 * tries the next one rather than failing the delivery.
 */
public interface DeliveryProvider {

    /** The code this adapter is registered under, matching {@code delivery_provider.code}. */
    String code();

    /**
     * What it would cost and how long it would take.
     *
     * <p>A provider that does not serve the route answers {@link Quote#unserviceable},
     * which is an answer rather than a failure: doc 06 §4 selects among providers
     * that meet serviceability, and knowing one declined is part of the record.
     */
    Quote quote(QuoteRequest request);

    /** Commit to the quote. The provider now owns the consignment. */
    Booking book(BookingRequest request);

    /** Where the delivery stands according to the provider. */
    ProviderDelivery status(String providerDeliveryId);

    /**
     * The driver's last known position.
     *
     * <p>Returns {@code null} when the provider has nothing — before assignment, or
     * where tracking is unsupported. Doc 06 §8: never fabricate a location. An
     * adapter that invents a midpoint to fill this in is worse than one that
     * returns nothing, because the restaurant cannot tell the difference.
     */
    Location location(String providerDeliveryId);

    /** Ask the provider to stand down. Idempotent on their side and ours. */
    void cancel(String providerDeliveryId, String reason);

    /** Whether this provider reports driver positions at all. */
    default boolean supportsTracking() {
        return true;
    }

    // ── our types, never theirs ──────────────────────────────────────────

    record QuoteRequest(
            /**
             * Null when quoting before the order exists.
             *
             * <p>D-091 quotes the fee at order creation, so the restaurant knows
             * what it is paying before it pays — and at that moment there is no
             * order to name.
             */
            Long supplierOrderId,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            BigDecimal dropLatitude,
            BigDecimal dropLongitude,
            BigDecimal orderValue,
            /**
             * What the consignment weighs. Decides the vehicle, and with it the
             * price — a provider cannot quote a hundred kilos onto a bike.
             */
            BigDecimal weightGrams,
            /** What the order needs, for a provider that can offer a faster tier. */
            Integer requiredEtaMinutes) {
    }

    /**
     * @param serviceable false when the provider will not take the route at all;
     *                    {@code amount} and {@code etaMinutes} are then null
     */
    record Quote(
            String providerQuoteId,
            boolean serviceable,
            BigDecimal amount,
            String currency,
            Integer etaMinutes,
            Double distanceKm,
            Instant expiresAt,
            String declineReason) {

        public static Quote unserviceable(String reason) {
            return new Quote(null, false, null, "INR", null, null, null, reason);
        }
    }

    record BookingRequest(
            Long supplierOrderId,
            String providerQuoteId,
            String pickupAddress,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String pickupContactName,
            String pickupContactPhone,
            String dropAddress,
            BigDecimal dropLatitude,
            BigDecimal dropLongitude,
            String dropContactName,
            String dropContactPhone,
            /** Ours, so a retried booking cannot produce two couriers. */
            String idempotencyKey) {
    }

    record Booking(
            String providerDeliveryId,
            BigDecimal amount,
            String currency,
            Integer etaMinutes,
            Instant estimatedArrivalAt) {
    }

    /** Provider-reported state, already mapped onto our vocabulary. */
    record ProviderDelivery(
            String providerDeliveryId,
            ProviderDeliveryStatus status,
            String driverName,
            String driverPhone,
            String driverVehicle,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            String failureCode,
            String failureReason,
            /** Newest first, and only those the provider actually reported. */
            List<ProviderEvent> events) {
    }

    record ProviderEvent(
            String providerEventId,
            ProviderDeliveryStatus status,
            String description,
            Instant occurredAt) {
    }

    record Location(
            BigDecimal latitude,
            BigDecimal longitude,
            Double bearing,
            Double speedKmph,
            /** The provider's timestamp for the fix, not ours. */
            Instant recordedAt) {
    }

    /**
     * The provider's lifecycle, in our words. Doc 06 §5.
     *
     * <p>Deliberately the same vocabulary as {@code DeliveryStatus} minus the
     * states only we can be in — an adapter maps into this, and the service maps
     * this onto the delivery.
     */
    enum ProviderDeliveryStatus {
        PENDING,
        DRIVER_ASSIGNED,
        DRIVER_AT_PICKUP,
        PICKED_UP,
        IN_TRANSIT,
        ARRIVED_AT_DESTINATION,
        DELIVERED,
        DRIVER_CANCELLED,
        PICKUP_FAILED,
        DELIVERY_FAILED,
        CANCELLED
    }
}
