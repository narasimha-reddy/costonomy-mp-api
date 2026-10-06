package com.costonomy.mp.procurement.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs generation for a delivery date across every subscription. Deliberately not transactional: each
 * subscription is its own transaction inside {@link SubscriptionOrderGenerator}, so one failing never rolls
 * back or stops another (the same shape as the settlement sweep), and a failure is recorded after its
 * transaction has ended.
 *
 * <p>Idempotent: running a date again after a top-up generates the orders that failed and leaves the ones
 * that succeeded alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionGenerationService {

    public record Summary(int generated, int failed, int skipped, int errors) {
    }

    private final SubscriptionRepository subscriptions;
    private final SubscriptionSkipDateRepository skipDates;
    private final SubscriptionOrderGenerator generator;
    private final SubscriptionRunStore runStore;

    public Summary runFor(LocalDate date) {
        int generated = 0;
        int failed = 0;
        int skipped = 0;
        int errors = 0;

        for (Long id : subscriptions.findIdsSpanning(SubscriptionStatus.ACTIVE, date)) {
            try {
                if (generator.generate(id, date) != null) {
                    generated++;
                }
            } catch (SubscriptionRunException ex) {
                var sub = subscriptions.findById(id).orElse(null);
                if (sub == null) {
                    continue;
                }
                runStore.record(sub, date, ex.outcome().name(), ex.getMessage(), ex.amount());
                if (ex.outcome() == SubscriptionRunException.Outcome.FUNDING_FAILED) {
                    failed++;
                } else {
                    skipped++;
                }
            } catch (DataIntegrityViolationException ex) {
                // Another run made this date's order first; the unique key stopped this one before any money moved.
                log.info("Subscription {} already has an order for {}", id, date);
            } catch (RuntimeException ex) {
                // One subscription must not stop the sweep: the next may be the one that works.
                errors++;
                log.error("Subscription {} could not be generated for {}", id, date, ex);
            }
        }

        for (Long id : subscriptions.findIdsSpanning(SubscriptionStatus.PAUSED, date)) {
            try {
                var sub = subscriptions.findById(id).orElse(null);
                if (sub != null && isDue(sub, date)) {
                    runStore.record(sub, date, "SKIPPED_PAUSED", "The subscription is paused", null);
                    skipped++;
                }
            } catch (RuntimeException ex) {
                errors++;
                log.error("Paused subscription {} could not be recorded for {}", id, date, ex);
            }
        }
        return new Summary(generated, failed, skipped, errors);
    }

    private boolean isDue(Subscription sub, LocalDate date) {
        Set<LocalDate> skipped = skipDates.findBySubscriptionId(sub.getId()).stream()
                .map(SubscriptionSkipDate::getSkipDate).collect(Collectors.toSet());
        return SubscriptionSchedule.isDue(sub.getStartDate(), sub.getEndDate(), sub.getFrequency(), date, skipped);
    }
}
