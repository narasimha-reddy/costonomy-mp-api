package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.DeliveryQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Asks every courier what they would charge, and picks one. Doc 06 §4, §6, §7.
 *
 * <p><b>Every provider is asked, and every answer is recorded</b> — including
 * declines and failures. Doc 06 §12 requires the quoting to be reconstructable,
 * and without the failures a delivery that fell back to the only courier left
 * looks like a choice rather than the last option standing.
 *
 * <p>A provider throwing is not a delivery failure. Doc 06 §7's quote-failure
 * handling is "try an alternative provider", so one courier being down costs
 * nothing as long as somebody answers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryQuotingService {

    private final DeliveryProviderRegistry registry;
    private final DeliveryQuoteRepository quotes;

    /** What quoting produced: the winner, and whether anybody answered at all. */
    public record Outcome(
            DeliveryQuote selected,
            List<DeliveryQuote> all,
            boolean anyServiceable) {
    }

    /**
     * Gather quotes and choose.
     *
     * @param excludedProviderCodes couriers already tried for this delivery — a
     *                              reassignment must not hand it back to the one
     *                              that just cancelled (doc 06 §7)
     */
    @Transactional
    public Outcome gather(Delivery delivery, BigDecimal orderValue,
                          Integer requiredEtaMinutes, List<String> excludedProviderCodes) {

        var request = new DeliveryProvider.QuoteRequest(
                delivery.getSupplierOrderId(),
                delivery.getPickupLatitude(), delivery.getPickupLongitude(),
                delivery.getDropLatitude(), delivery.getDropLongitude(),
                orderValue, requiredEtaMinutes);

        List<DeliveryQuote> recorded = new ArrayList<>();
        List<DeliverySelection.Candidate> candidates = new ArrayList<>();

        for (var available : registry.enabled()) {
            if (excludedProviderCodes.contains(available.record().getCode())) {
                continue;
            }

            var quote = new DeliveryQuote();
            quote.setDeliveryId(delivery.getId());
            quote.setDeliveryProviderId(available.record().getId());
            quote.setProviderCode(available.record().getCode());

            try {
                var answer = available.adapter().quote(request);

                if (!answer.serviceable()) {
                    quote.setStatus("UNSERVICEABLE");
                    quote.setFailureReason(answer.declineReason());
                } else {
                    quote.setStatus("QUOTED");
                    quote.setAmount(answer.amount());
                    quote.setCurrency(answer.currency());
                    quote.setEtaMinutes(answer.etaMinutes());
                    quote.setDistanceKm(answer.distanceKm() == null ? null
                            : BigDecimal.valueOf(answer.distanceKm())
                                    .setScale(4, RoundingMode.HALF_UP));
                    quote.setProviderQuoteId(answer.providerQuoteId());
                    quote.setExpiresAt(answer.expiresAt());

                    candidates.add(new DeliverySelection.Candidate(
                            available.record().getCode(), answer.amount(), answer.etaMinutes(),
                            available.record().getPriority()));
                }
            } catch (DeliveryProviderException ex) {
                // Doc 06 §7: try an alternative. Recorded so the fallback is
                // explicable afterwards.
                log.info("Quote from {} failed: {}", available.record().getCode(), ex.getMessage());
                quote.setStatus("FAILED");
                quote.setFailureReason(ex.getMessage());
            }

            quotes.save(quote);
            recorded.add(quote);
        }

        Optional<DeliverySelection.Candidate> winner =
                DeliverySelection.select(candidates, requiredEtaMinutes);

        DeliveryQuote selected = null;
        if (winner.isPresent()) {
            selected = recorded.stream()
                    .filter(quote -> quote.getProviderCode().equals(winner.get().providerCode()))
                    .filter(quote -> "QUOTED".equals(quote.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (selected != null) {
                selected.setSelected(true);
                quotes.save(selected);
            }
        }

        return new Outcome(selected, recorded, !candidates.isEmpty());
    }

    /** Quotes that are still usable, cheapest first, excluding ones already tried. */
    @Transactional(readOnly = true)
    public List<DeliveryQuote> usableQuotes(Long deliveryId, List<String> excludedProviderCodes) {
        var now = java.time.Instant.now();
        return quotes.findByDeliveryIdOrderByIdAsc(deliveryId).stream()
                .filter(quote -> "QUOTED".equals(quote.getStatus()))
                .filter(quote -> !excludedProviderCodes.contains(quote.getProviderCode()))
                // A quote has a shelf life. Booking an expired one means committing
                // the restaurant to a price the courier no longer offers.
                .filter(quote -> quote.getExpiresAt() == null || quote.getExpiresAt().isAfter(now))
                .sorted(java.util.Comparator.comparing(DeliveryQuote::getAmount))
                .toList();
    }
}
