package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.delivery.domain.ConsignmentWeight;
import com.costonomy.mp.delivery.domain.DeliveryFeeQuote;
import com.costonomy.mp.delivery.domain.DeliveryQuoteSource;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryFeeQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What a Costonomy delivery costs, priced before the order exists. D-091.
 *
 * <p>The restaurant chooses the mode and pays the fee, so the fee has to be a
 * real number on the screen where they choose — which is before any order, any
 * {@code Delivery} row and any booked courier.
 *
 * <p><b>Both endpoints are resolved here.</b> Callers name an outlet and a store;
 * the coordinates come from those rows. A caller-supplied origin would let
 * somebody quote a one-kilometre run and be charged for it while a courier drove
 * twenty, and guardrail 3 puts this arithmetic on the server regardless.
 *
 * <p><b>The answer is stored and referenced, never recomputed.</b> A fee
 * recalculated between the screen that showed it and the charge that collected it
 * is a silent reprice, which §23A.16 forbids. {@link #consume} validates the
 * stored quote instead.
 *
 * <p><b>Providers price it; the rate card is the floor under an outage.</b> Doc 06
 * §10 keeps bidding internal, so what comes back is one fee and an ETA — never a
 * provider, a vehicle or a list of quotes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryFeeQuoteService {

    private final DeliveryFeeQuoteRepository quotes;
    private final DeliveryDirectory directory;
    private final DeliveryProviderRegistry registry;
    private final AppConfigService config;

    /** What a quote is worth, and what the caller may see of it. */
    public record Fee(
            String quoteReference,
            BigDecimal amount,
            String currency,
            Integer etaMinutes,
            Double distanceKm,
            Instant expiresAt) {
    }

    /**
     * Price a Costonomy delivery for this request.
     *
     * @param intentId the request being ordered, so the quote cannot be presented
     *                 for one basket and spent on another
     */
    @Transactional
    public Fee quote(Long intentId, Long outletId, Long supplierStoreId, BigDecimal orderValue) {
        var pickup = directory.pickupFor(supplierStoreId);
        var drop = directory.dropFor(outletId);

        Double distanceKm = pickup == null || drop == null ? null : Serviceability.distanceKm(
                pickup.latitude(), pickup.longitude(), drop.latitude(), drop.longitude());

        // Withheld rather than estimated from a pincode. A delivery fee guessed
        // from a postcode is a number the restaurant is charged and nobody can
        // defend; the honest answer is that this mode is not available here.
        if (distanceKm == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We can't price delivery to this address yet. Choose pickup, "
                            + "or ask the supplier to deliver.");
        }

        var weight = ConsignmentWeight.of(
                directory.intentLines(intentId), DeliveryDirectory.DEFAULT_PIECE_GRAMS);

        var quote = new DeliveryFeeQuote();
        quote.setReference("DQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        quote.setIntentId(intentId);
        quote.setOutletId(outletId);
        quote.setSupplierStoreId(supplierStoreId);
        quote.setPickupLatitude(pickup.latitude());
        quote.setPickupLongitude(pickup.longitude());
        quote.setDropLatitude(drop.latitude());
        quote.setDropLongitude(drop.longitude());
        quote.setDistanceKm(BigDecimal.valueOf(distanceKm).setScale(4, RoundingMode.HALF_UP));
        quote.setWeightGrams(weight.grams());
        quote.setVehicleType(vehicleFor(weight.grams()));
        quote.setExpiresAt(Instant.now().plus(Duration.ofSeconds(
                config.getInt("delivery.quoteTtlSeconds", 900))));

        priceIt(quote, orderValue, weight.grams());
        var saved = quotes.save(quote);

        return new Fee(saved.getReference(), saved.getFee(), saved.getCurrency(),
                saved.getEtaMinutes(), distanceKm, saved.getExpiresAt());
    }

    /**
     * Ask the providers, and fall back to the rate card if none answers.
     *
     * <p>A provider throwing is not a pricing failure — doc 06 §7 treats it as an
     * answer of "not from us". If every one of them declines or fails, the order
     * still has to be placeable, so the rate card prices it and the quote records
     * that nobody quoted it.
     */
    private void priceIt(DeliveryFeeQuote quote, BigDecimal orderValue, BigDecimal weightGrams) {
        var request = new DeliveryProvider.QuoteRequest(
                null, quote.getPickupLatitude(), quote.getPickupLongitude(),
                quote.getDropLatitude(), quote.getDropLongitude(),
                orderValue, weightGrams, null);

        DeliveryProvider.Quote best = null;
        for (var available : registry.enabled()) {
            try {
                var answer = available.adapter().quote(request);
                if (answer.serviceable() && answer.amount() != null
                        && (best == null || answer.amount().compareTo(best.amount()) < 0)) {
                    best = answer;
                }
            } catch (RuntimeException ex) {
                log.info("Provider {} could not price a fee quote: {}",
                        available.record().getCode(), ex.getMessage());
            }
        }

        if (best != null) {
            quote.setSource(DeliveryQuoteSource.QUOTED);
            quote.setFee(best.amount().setScale(2, RoundingMode.HALF_UP));
            quote.setCurrency(best.currency() == null ? "INR" : best.currency());
            quote.setEtaMinutes(best.etaMinutes());
            quote.setProviderReference(best.providerQuoteId());
            return;
        }

        quote.setSource(DeliveryQuoteSource.ESTIMATED);
        quote.setFee(rateCard(quote.getDistanceKm(), weightGrams));
        quote.setEtaMinutes(estimatedMinutes(quote.getDistanceKm()));
        log.warn("No provider priced delivery for outlet {} from store {}; used the rate card",
                quote.getOutletId(), quote.getSupplierStoreId());
    }

    /**
     * The fallback price.
     *
     * <p>Distance and weight, both configured, because a rate card that ignores
     * weight prices a hundred kilos like a single crate — and this figure is what
     * a restaurant actually pays whenever the providers are unreachable.
     */
    private BigDecimal rateCard(BigDecimal distanceKm, BigDecimal weightGrams) {
        BigDecimal base = config.getDecimal("delivery.baseFee", BigDecimal.valueOf(40));
        BigDecimal perKm = config.getDecimal("delivery.perKm", BigDecimal.valueOf(8));
        BigDecimal perKg = config.getDecimal("delivery.perKg", BigDecimal.valueOf(2));
        BigDecimal minimum = config.getDecimal("delivery.minFee", BigDecimal.valueOf(40));

        BigDecimal fee = base
                .add(perKm.multiply(distanceKm))
                .add(perKg.multiply(weightGrams.divide(BigDecimal.valueOf(1000), 4,
                        RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);

        return fee.max(minimum.setScale(2, RoundingMode.HALF_UP));
    }

    private Integer estimatedMinutes(BigDecimal distanceKm) {
        BigDecimal speed = config.getDecimal("eta.averageSpeedKmph", BigDecimal.valueOf(20));
        int overhead = config.getInt("eta.dispatchOverheadMinutes", 15);
        if (speed.signum() <= 0) {
            return null;
        }
        return overhead + distanceKm.multiply(BigDecimal.valueOf(60))
                .divide(speed, 0, RoundingMode.CEILING).intValue();
    }

    /**
     * Which vehicle this needs. Internal — it sets the platform's cost, not the
     * restaurant's price, and a kitchen has no use for the answer.
     */
    private String vehicleFor(BigDecimal weightGrams) {
        BigDecimal kg = weightGrams.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        if (kg.compareTo(config.getDecimal("delivery.bikeMaxKg", BigDecimal.valueOf(20))) <= 0) {
            return "BIKE";
        }
        if (kg.compareTo(config.getDecimal("delivery.threeWheelerMaxKg",
                BigDecimal.valueOf(150))) <= 0) {
            return "THREE_WHEELER";
        }
        return "TRUCK";
    }

    /**
     * Spend a quote on an order.
     *
     * <p>Validated rather than trusted: a quote belongs to one request, one
     * outlet and one store, and it is spent once. A mismatch is somebody
     * presenting a cheap quote for a different basket.
     *
     * @return the fee the order should carry
     */
    /**
     * Read a quote's fee, checking it is this request's and still standing.
     *
     * <p>Separate from {@link #consume} because the order has to be priced
     * before it exists and attached to after: doing both in one call meant
     * spending the quote and then finding it already spent, which reported
     * itself as somebody else's quote.
     */
    @Transactional(readOnly = true)
    public BigDecimal priceFor(String quoteReference, Long intentId, Long outletId,
                               Long supplierStoreId, Instant now) {
        return validate(quoteReference, intentId, outletId, supplierStoreId, now).getFee();
    }

    /** Mark the quote spent, now that there is an order to attach it to. */
    @Transactional
    public BigDecimal consume(String quoteReference, Long intentId, Long outletId,
                              Long supplierStoreId, Long supplierOrderId, Instant now) {

        var quote = validate(quoteReference, intentId, outletId, supplierStoreId, now);
        quote.setConsumedAt(now);
        quote.setSupplierOrderId(supplierOrderId);
        quotes.save(quote);
        return quote.getFee();
    }

    private DeliveryFeeQuote validate(String quoteReference, Long intentId, Long outletId,
                                      Long supplierStoreId, Instant now) {

        var quote = quotes.findByReference(quoteReference)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That delivery quote is no longer valid. Please check the fee again."));

        boolean mismatched = !quote.getOutletId().equals(outletId)
                || !quote.getSupplierStoreId().equals(supplierStoreId)
                || (quote.getIntentId() != null && !quote.getIntentId().equals(intentId));

        if (mismatched || quote.isConsumed()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That delivery quote belongs to a different request.");
        }

        // Expiry is a price change, not an error to swallow. §23A.16: the
        // restaurant sees the old figure and the new one and agrees, or does not.
        if (quote.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.PRICE_CHANGED,
                    "The delivery fee has changed since you saw it. Please review it again.");
        }

        return quote;
    }
}
