package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.domain.DeliverySelection.Candidate;
import com.costonomy.mp.delivery.domain.ProviderReliability;
import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;
import com.costonomy.mp.delivery.domain.ReliabilityRanking;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.delivery.service.ProviderReliabilityService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryQuote;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryQuoteRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which couriers to ask for a food delivery, and in what order (D-197 addendum): the first attempt is the cheapest that
 * meets the delivery's deadline; after a failure the most reliable within a cost bound; a courier whose circuit
 * breaker is open is skipped.
 *
 * <p>Returns the order and, for each courier, what to record about the choice, so the booking loop can fill in the
 * attempt row (rank, reason, score, breaker state, the candidates considered) as it tries them. Booking, the fee hold
 * and the 5-minute retry window are the booking service's.
 */
@Service
@RequiredArgsConstructor
public class FoodCourierSelector {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoodDeliveryQuoteRepository quotes;
    private final FoodDeliveryAttemptRepository attempts;
    private final ProviderReliabilityService reliability;
    private final DeliveryProviderRegistry registry;

    /** One courier to ask, with what to record on its attempt. */
    public record Choice(FoodDeliveryQuote quote, int rank, String reason, BigDecimal score,
                         String breakerState, String decisionJson) {
    }

    /**
     * @param maxAmount the most the restaurant will pay for this delivery (the fee hold), or null for no cap
     * @return couriers in the order to try them; empty if none can take it
     */
    @Transactional(readOnly = true)
    public List<Choice> order(FoodDelivery delivery, BigDecimal maxAmount) {
        return order(delivery, maxAmount, Instant.now());
    }

    /** As {@link #order(FoodDelivery, BigDecimal)} at a given instant, so expiry can be judged on a clock the caller controls. */
    @Transactional(readOnly = true)
    public List<Choice> order(FoodDelivery delivery, BigDecimal maxAmount, Instant now) {
        // The newest usable quote per courier: one still valid, with a price and an ETA.
        var byProvider = new LinkedHashMap<String, FoodDeliveryQuote>();
        quotes.findByFoodDeliveryIdOrderByIdAsc(delivery.getId()).stream()
                .filter(q -> "QUOTED".equals(q.getStatus()) && q.getAmount() != null && q.getEtaMinutes() != null)
                .filter(q -> q.getExpiresAt() == null || q.getExpiresAt().isAfter(now))
                .forEach(q -> byProvider.put(q.getProviderCode(), q));
        if (byProvider.isEmpty()) {
            return List.of();
        }

        Set<String> tried = attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).stream()
                .map(FoodDeliveryAttempt::getProviderCode).collect(Collectors.toSet());
        var assessments = reliability.assess(byProvider.keySet());
        var settings = reliability.settings();

        var candidates = byProvider.values().stream().map(q -> new Candidate(
                q.getProviderCode(), q.getAmount(), q.getEtaMinutes(), priorityOf(q.getProviderCode()))).toList();
        var ordered = ReliabilityRanking.order(candidates, tried, assessments,
                delivery.getMaxDeliveryMinutes(), maxAmount, settings);

        boolean afterFailure = !tried.isEmpty();
        var skippedOpen = candidates.stream()
                .filter(c -> !tried.contains(c.providerCode()))
                .filter(c -> state(assessments, c.providerCode()) == BreakerState.OPEN)
                .filter(c -> ordered.stream().noneMatch(o -> o.providerCode().equals(c.providerCode())))
                .map(Candidate::providerCode).toList();
        String decision = snapshot(ordered, assessments, skippedOpen, tried, maxAmount);

        var choices = new ArrayList<Choice>();
        for (int i = 0; i < ordered.size(); i++) {
            var candidate = ordered.get(i);
            var assessment = assessments.get(candidate.providerCode());
            String reason = ordered.size() == 1 ? "ONLY_OPTION"
                    : state(assessments, candidate.providerCode()) == BreakerState.HALF_OPEN ? "PROBE"
                    : afterFailure ? "RELIABILITY" : "CHEAPEST";
            choices.add(new Choice(byProvider.get(candidate.providerCode()), i + 1, reason,
                    assessment == null ? null : BigDecimal.valueOf(assessment.score()).setScale(4, RoundingMode.HALF_UP),
                    assessment == null ? null : assessment.breaker().name(), decision));
        }
        return choices;
    }

    private int priorityOf(String providerCode) {
        var record = registry.record(providerCode);
        return record == null || record.getPriority() == null ? 100 : record.getPriority();
    }

    private static BreakerState state(Map<String, ProviderReliability> assessments, String code) {
        var a = assessments.get(code);
        return a == null ? BreakerState.CLOSED : a.breaker();
    }

    private static String snapshot(List<Candidate> ordered, Map<String, ProviderReliability> assessments,
                                   List<String> skippedOpen, Set<String> tried, BigDecimal maxAmount) {
        try {
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < ordered.size(); i++) {
                var c = ordered.get(i);
                var a = assessments.get(c.providerCode());
                var row = new LinkedHashMap<String, Object>();
                row.put("provider", c.providerCode());
                row.put("rank", i + 1);
                row.put("amount", c.amount());
                row.put("etaMinutes", c.etaMinutes());
                row.put("score", a == null ? null : BigDecimal.valueOf(a.score()).setScale(4, RoundingMode.HALF_UP));
                row.put("breaker", a == null ? null : a.breaker().name());
                row.put("fulfilment", a == null ? null : BigDecimal.valueOf(a.fulfilment()).setScale(3, RoundingMode.HALF_UP));
                row.put("judged", a == null ? null : a.judged());
                rows.add(row);
            }
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("candidates", rows);
            snapshot.put("skippedBreakerOpen", skippedOpen);
            snapshot.put("alreadyTried", tried.stream().sorted(Comparator.naturalOrder()).toList());
            snapshot.put("maxAmount", maxAmount);
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            // A record for later, never a reason to fail a booking.
            return null;
        }
    }
}
