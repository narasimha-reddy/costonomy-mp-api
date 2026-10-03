package com.costonomy.mp.delivery.provider.shadowfax;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Port adapter implementing {@link DeliveryProvider} for Shadowfax Logistics.
 *
 * <p>Gated on {@code costonomy.mp.shadowfax.enabled}, independent of Pidge and Borzo.
 * Requires both the configuration property and the enabled row in {@code delivery_provider}
 * (migration V50) to participate in the delivery auction.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.shadowfax.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ShadowfaxDeliveryProvider implements DeliveryProvider {

    public static final String CODE = "SHADOWFAX";

    private final ShadowfaxApiClient client;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Quote quote(QuoteRequest request) {
        return client.calculateQuote(request);
    }

    @Override
    public Booking book(BookingRequest request) {
        return client.createOrder(request);
    }

    @Override
    public ProviderDelivery status(String providerDeliveryId) {
        return client.getStatus(providerDeliveryId);
    }

    @Override
    public Location location(String providerDeliveryId) {
        return null;
    }

    @Override
    public void cancel(String providerDeliveryId, String reason) {
        client.cancel(providerDeliveryId, reason);
    }

    @Override
    public boolean supportsTracking() {
        return true;
    }
}
