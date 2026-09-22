package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Port adapter implementing {@link DeliveryProvider} for Pidge Smart Dispatch.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.delivery", havingValue = "PIDGE")
@RequiredArgsConstructor
@Slf4j
public class PidgeDeliveryProvider implements DeliveryProvider {

    public static final String CODE = "PIDGE";

    private final PidgeApiClient client;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Quote quote(QuoteRequest request) {
        return client.getQuote(request);
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
        // Pidge coordinates are delivered in webhook milestone events / live tracking URLs.
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
