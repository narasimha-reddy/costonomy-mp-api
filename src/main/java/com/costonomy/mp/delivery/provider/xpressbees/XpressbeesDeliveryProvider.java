package com.costonomy.mp.delivery.provider.xpressbees;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing {@link DeliveryProvider} for Xpressbees.
 *
 * <p>Registered in {@code DeliveryProviderRegistry} when enabled via
 * {@code costonomy.mp.xpressbees.enabled = true}.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.xpressbees.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class XpressbeesDeliveryProvider implements DeliveryProvider {

    private final XpressbeesApiClient client;

    @Override
    public String code() {
        return "XPRESSBEES";
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
        return client.location(providerDeliveryId);
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
