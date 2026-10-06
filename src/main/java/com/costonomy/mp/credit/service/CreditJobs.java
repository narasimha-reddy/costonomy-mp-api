package com.costonomy.mp.credit.service;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Overdue invoices, and the suspensions that follow. Doc 01 §18, doc 08 §4.
 *
 * <p>Two steps, deliberately separate. Marking an invoice overdue is a fact about
 * a date passing. Suspending a credit line is the supplier's policy response to
 * that fact, and only applies where they asked for it — {@code auto_suspend_enabled}
 * and {@code max_overdue_amount} are theirs to set, and a supplier who left them
 * alone has not asked us to cut anybody off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditJobs {

    private final CreditInvoiceService invoices;
    private final CreditAgreementService agreementService;
    private final CreditAgreementRepository agreements;
    private final CreditLifecycleService lifecycle;

    /**
     * Runs hourly rather than every minute: an invoice becomes overdue on a date
     * boundary, so checking sixty times an hour would find the same answer
     * fifty-nine times.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.credit.overdue-interval:PT1H}")
    @SchedulerLock(name = "credit-overdue", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void sweepOverdue() {
        int marked = invoices.markOverdue();
        if (marked > 0) {
            log.info("Marked {} credit invoices overdue", marked);
        }
        suspendOverLimit();
    }

    /**
     * Lapse the offers nobody accepted for 14 India days (D-137). Hourly like the overdue sweep: it is a date
     * boundary. Each offer is expired in its own transaction, so one failure does not stop the rest, and an offer that
     * fails is simply picked up by the next run.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.credit.offer-expiry-interval:PT1H}")
    @SchedulerLock(name = "credit-offer-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void expireOffers() {
        LocalDate today = invoices.today();
        int expired = 0;
        for (var agreement : agreements.findByStatus(CreditAgreementStatus.APPROVED)) {
            // A cheap look first, without the lock; expireOffer looks again on the locked row.
            if (!lifecycle.offerHasExpired(agreement, today)) {
                continue;
            }
            try {
                if (lifecycle.expireOffer(agreement.getId(), today)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not expire credit offer {}", agreement.getId(), ex);
            }
        }
        if (expired > 0) {
            log.info("Expired {} unaccepted credit offers", expired);
        }
    }

    /**
     * Suspend agreements whose overdue balance is past what the supplier tolerates.
     *
     * <p>Existing debt and reservations survive. Suspension stops <em>new</em>
     * orders; unwinding commitments already made would cancel deliveries a supplier
     * has already committed to, which helps nobody who is owed money.
     */
    void suspendOverLimit() {
        for (var agreement : agreements.findByStatus(CreditAgreementStatus.ACTIVE)) {
            if (!Boolean.TRUE.equals(agreement.getAutoSuspendEnabled())
                    || agreement.getMaxOverdueAmount() == null) {
                continue;
            }

            BigDecimal overdue = invoices.duesFor(agreement.getId()).overdue();
            if (overdue.signum() == 0 && agreement.getOverdueFloor() != null) {
                invoices.clearOverdueFloorIfCleared(agreement.getId());
            }
            // A supplier who lifted an earlier auto-suspension has accepted what was overdue then (D-133).
            BigDecimal tolerance = agreement.overdueTolerance();
            if (overdue.compareTo(tolerance) <= 0) {
                continue;
            }

            try {
                agreementService.suspendInternal(agreement,
                        "Overdue balance of %s is above the agreed maximum of %s"
                                .formatted(overdue, tolerance),
                        null);
            } catch (RuntimeException ex) {
                // One agreement must not stop the sweep — the next one may be the
                // one genuinely at risk.
                log.error("Could not suspend credit agreement {}", agreement.getId(), ex);
            }
        }
    }
}
