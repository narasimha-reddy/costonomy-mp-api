package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Port adapter implementing {@link DeliveryProvider} for Pidge Smart Dispatch.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.delivery", havingValue = "PIDGE")
@RequiredArgsConstructor
@Slf4j
public class PidgeDeliveryProvider implements DeliveryProvider {

    public static final String CODE = "PIDGE";

    /** How long a polled order state may answer {@link #location}. The poller asks right after {@link #status}. */
    static final Duration STATE_TTL = Duration.ofMinutes(2);

    private final PidgeApiClient client;

    /** The last order state {@link #status} fetched per Pidge order, so {@link #location} needs no second call. */
    private final ConcurrentHashMap<String, Seen> lastState = new ConcurrentHashMap<>();

    private record Seen(PidgeOrderState state, Instant at) {
    }

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
        var state = client.getOrderState(providerDeliveryId);
        var now = Instant.now();
        // Bounded: anything past the TTL could not answer location() anyway.
        lastState.values().removeIf(seen -> seen.at().plus(STATE_TTL).isBefore(now));
        lastState.put(providerDeliveryId, new Seen(state, now));
        return PidgeApiClient.toProviderDelivery(providerDeliveryId, state);
    }

    @Override
    public Location location(String providerDeliveryId) {
        // The position of the latest log that carried one, from the state status() just fetched. Pidge logs a
        // position at milestones, so a polled fix moves per stage; live positions between them need the webhook.
        var seen = lastState.get(providerDeliveryId);
        if (seen == null || seen.at().plus(STATE_TTL).isBefore(Instant.now())) {
            return null;
        }
        var state = seen.state();
        if (state.latitude() == null || state.longitude() == null) {
            return null;
        }
        return new Location(state.latitude(), state.longitude(), null, null, state.locationAt());
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
