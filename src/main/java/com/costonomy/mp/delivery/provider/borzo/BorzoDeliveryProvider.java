package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Port adapter implementing {@link DeliveryProvider} for Borzo.
 *
 * <p>Gated on {@code costonomy.mp.borzo.enabled}, not on
 * {@code costonomy.mp.providers.delivery} — that property is a single-valued
 * switch Pidge's own {@code @ConditionalOnProperty} is pinned to, and Borzo
 * needs to be dispatchable alongside Pidge, not instead of it. The
 * {@code delivery_provider} row this adapter answers to (migration V41) is
 * seeded {@code enabled = 0}; both gates have to agree before
 * {@link com.costonomy.mp.delivery.service.DeliveryProviderRegistry} will
 * actually offer this adapter a quote.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.borzo.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BorzoDeliveryProvider implements DeliveryProvider {

    public static final String CODE = "BORZO";

    private final BorzoApiClient client;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Quote quote(QuoteRequest request) {
        return client.calculateOrder(request);
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
        // Borzo exposes a web tracking page per point (Booking.trackingUrl), not
        // a lat/lng feed we can poll. Doc 06 §8: no position fix means null, same
        // as Pidge — never an interpolated point standing in for a real one.
        return null;
    }

    @Override
    public void cancel(String providerDeliveryId, String reason) {
        client.cancel(providerDeliveryId, reason);
    }

    @Override
    public boolean supportsTracking() {
        // A trackable URL exists (same shape as Pidge's), even though location()
        // never returns a position fix.
        return true;
    }
}
