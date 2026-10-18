package com.costonomy.mp.delivery.domain;

/**
 * Published when a provider, not us, ended its hold on a consignment before pickup: the rider cancelled, the pickup
 * failed, or the provider cancelled the whole order. The delivery still needs carrying, so something must book the
 * next courier.
 *
 * <p>{@code providerDeliveryId} names the consignment that stood down, so a late handler can tell that the delivery
 * has already moved on to another one and do nothing.
 */
public record DeliveryProviderStoodDownEvent(
        Long deliveryId,
        String providerDeliveryId,
        String reason) {
}
