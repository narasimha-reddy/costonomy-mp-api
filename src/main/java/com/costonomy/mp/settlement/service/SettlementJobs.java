package com.costonomy.mp.settlement.service;

import com.costonomy.mp.settlement.domain.SettlementStatus;
import com.costonomy.mp.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The daily settlement run, and the reconciliation sweep behind it.
 *
 * <p><b>Generation stops at CALCULATED.</b> Nothing here approves or pays: doc 03
 * §13 puts a human step between a calculated figure and money leaving, and a job
 * that walked past it would let a calculation bug pay itself out overnight.
 *
 * <p>The period is the previous whole UTC day. Settling a day that is still
 * running would produce a figure that changes every time the job runs, which is
 * the opposite of the reproducibility doc 09 §11 asks for.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementJobs {

    private final SettlementService settlements;
    private final SettlementReconciliationService reconciliation;
    private final SettlementRepository repository;
    private final SupplierDeliveryFundingPaymentService supplierFundingPayment;

    @Value("${costonomy.mp.settlement.generation-interval:PT1H}")
    private Duration generationInterval;

    @Scheduled(fixedDelayString = "${costonomy.mp.settlement.generation-interval:PT1H}")
    @SchedulerLock(name = "settlement-generate", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    @Transactional
    public void generateForYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant start = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = start.plus(Duration.ofDays(1));

        int touched = settlements.generate(start, end);
        if (touched > 0) {
            log.info("Generated or extended {} settlements for {}", touched, yesterday);
        }
    }

    /**
     * Reconcile everything not yet paid.
     *
     * <p>Repeatedly, and deliberately: a settlement's captured total can change
     * after it is calculated — a refund lands, a delayed capture completes — so a
     * single check at generation time would miss exactly the cases worth catching.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.settlement.reconcile-interval:PT1H}")
    @SchedulerLock(name = "settlement-reconcile", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void reconcileOpenSettlements() {
        for (SettlementStatus status : new SettlementStatus[] {
                SettlementStatus.CALCULATED, SettlementStatus.APPROVED}) {

            for (var settlement : repository.findByStatusOrderByIdAsc(status)) {
                try {
                    reconciliation.reconcileInternal(settlement.getId(), null);
                } catch (RuntimeException ex) {
                    // One settlement must not stop the sweep — the next one may be
                    // the one that does not add up.
                    log.error("Could not reconcile settlement {}", settlement.getId(), ex);
                }
            }
        }
    }

    /**
     * Sweeps pending delivery funding deductions that opened Razorpay orders and recovers
     * any lost client callbacks or missed webhooks (D-203).
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.settlement.delivery-funding-sweep-interval:PT60S}",
            initialDelayString = "${costonomy.mp.settlement.jobs-initial-delay:PT0S}")
    @SchedulerLock(name = "settlement-delivery-funding-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweepPendingDeliveryFundingPayments() {
        try {
            int recovered = supplierFundingPayment.sweepStaleGatewayPayments();
            if (recovered > 0) {
                log.info("Supplier delivery funding payment sweep reconciled {} payments", recovered);
            }
        } catch (Exception ex) {
            log.error("Supplier delivery funding payment sweep failed: {}", ex.getMessage());
        }
    }
}
