package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryQuote;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks every food-capable courier what it would charge (D-201), and records every answer, including the ones that were
 * declined or failed, with how long each took. Choosing among the answers is {@link FoodCourierSelector}'s.
 *
 * <p>Only couriers flagged {@code supports_food_delivery} are asked. A route beyond the intra-city radius is not sent to
 * anyone; a quote row says so, so the failure explains itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryQuotingService {

    /** The provider code on the quote recorded when the drop is beyond the radius and nobody is asked. */
    static final String RADIUS_LIMIT_PROVIDER_CODE = "RADIUS_LIMIT";

    private final DeliveryProviderRegistry registry;
    private final FoodDeliveryQuoteRepository quotes;

    @Value("${costonomy.mp.delivery.max-radius-km:30.0}")
    private double maxRadiusKm = 30.0;

    /** What quoting produced. {@code anyServiceable} is whether at least one courier gave a usable price. */
    public record Outcome(List<FoodDeliveryQuote> all, boolean anyServiceable) {
    }

    public Outcome gather(FoodDelivery delivery) {
        var vehicle = delivery.getVehicleType() != null ? delivery.getVehicleType()
                : VehicleType.fromWeight(delivery.getWeightKg(), false);

        Double distanceKm = Serviceability.distanceKm(delivery.getPickupLatitude(), delivery.getPickupLongitude(),
                delivery.getDropLatitude(), delivery.getDropLongitude());
        if (distanceKm != null && distanceKm > maxRadiusKm) {
            var tooFar = new FoodDeliveryQuote();
            tooFar.setFoodDeliveryId(delivery.getId());
            tooFar.setProviderCode(RADIUS_LIMIT_PROVIDER_CODE);
            tooFar.setStatus("UNSERVICEABLE");
            tooFar.setErrorClass("UNSERVICEABLE");
            tooFar.setDistanceKm(BigDecimal.valueOf(distanceKm).setScale(4, RoundingMode.HALF_UP));
            tooFar.setFailureReason("Exceeds %s km intra-city radius limit".formatted(maxRadiusKm));
            tooFar.setVehicleType(vehicle);
            return new Outcome(List.of(quotes.save(tooFar)), false);
        }

        var weightGrams = delivery.getWeightKg() == null ? null : delivery.getWeightKg().multiply(BigDecimal.valueOf(1000));
        var recorded = new ArrayList<FoodDeliveryQuote>();
        boolean anyServiceable = false;

        for (var available : registry.enabledForFood()) {
            var quote = new FoodDeliveryQuote();
            quote.setFoodDeliveryId(delivery.getId());
            quote.setDeliveryProviderId(available.record().getId());
            quote.setProviderCode(available.record().getCode());
            quote.setVehicleType(vehicle);

            var request = new DeliveryProvider.QuoteRequest(
                    null, delivery.getPickupLatitude(), delivery.getPickupLongitude(),
                    delivery.getDropLatitude(), delivery.getDropLongitude(), delivery.getOrderValue(),
                    weightGrams, delivery.getMaxDeliveryMinutes(), delivery.getWeightKg(), null, vehicle,
                    delivery.getPickupAddress(), delivery.getDropAddress(), "FD-" + delivery.getId());

            long startedNs = System.nanoTime();
            try {
                var answer = available.adapter().quote(request);
                quote.setCallMs(elapsedMs(startedNs));
                if (!answer.serviceable()) {
                    quote.setStatus("UNSERVICEABLE");
                    quote.setErrorClass("UNSERVICEABLE");
                    quote.setFailureReason(answer.declineReason());
                } else {
                    quote.setStatus("QUOTED");
                    quote.setAmount(answer.amount());
                    quote.setCurrency(answer.currency());
                    quote.setEtaMinutes(answer.etaMinutes());
                    quote.setDistanceKm(answer.distanceKm() == null ? null
                            : BigDecimal.valueOf(answer.distanceKm()).setScale(4, RoundingMode.HALF_UP));
                    quote.setProviderQuoteId(answer.providerQuoteId());
                    quote.setExpiresAt(answer.expiresAt());
                    if (answer.vehicleType() != null) {
                        quote.setVehicleType(answer.vehicleType());
                    }
                    anyServiceable |= answer.amount() != null && answer.etaMinutes() != null;
                }
            } catch (DeliveryProviderException ex) {
                log.info("Food quote from {} failed: {}", available.record().getCode(), ex.getMessage());
                quote.setStatus("FAILED");
                quote.setFailureReason(ex.getMessage());
                quote.setCallMs(elapsedMs(startedNs));
                quote.setErrorClass(ex.errorClass());
                quote.setRetryable(ex.retryable());
            }
            recorded.add(quotes.save(quote));
        }
        return new Outcome(recorded, anyServiceable);
    }

    private static int elapsedMs(long startedNs) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNs) / 1_000_000L);
    }
}
