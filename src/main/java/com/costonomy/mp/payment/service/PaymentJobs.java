package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Background payment work. Doc 38, doc 21, doc 46.
 *
 * <p>Three jobs, each closing a gap that a synchronous flow cannot:
 *
 * <ul>
 *   <li><b>Capture</b> — completes captures marked during an acceptance, which
 *       deliberately did not call the provider inside that transaction.</li>
 *   <li><b>Reconciliation</b> — asks the provider about payments that stalled.
 *       This is doc 46's lost-callback recovery: the customer paid, the client
 *       vanished, no webhook arrived, and only asking reveals it.</li>
 *   <li><b>Refunds</b> — sends requested refunds and retries failed ones.</li>
 * </ul>
 *
 * <p>All three are idempotent and locked, so a second instance cannot double-take
 * or double-return money.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentJobs {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final OrderReleaseService orderRelease;
    private final PaymentProvider provider;

    @Scheduled(fixedDelayString = "${costonomy.mp.payments.capture-interval:PT10S}")
    @SchedulerLock(name = "payment-capture", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void capturePending() {
        var pending = payments.findPendingCaptures();
        for (var payment : pending) {
            try {
                paymentService.performCapture(payment.getId());
            } catch (RuntimeException ex) {
                // One stuck payment must not block the rest — otherwise a single
                // poison row would leave every later capture unprocessed, and an
                // uncaptured authorisation eventually lapses into money the
                // supplier never receives.
                log.error("Could not capture payment {}", payment.getId(), ex);
            }
        }
    }

    /**
     * Doc 46: "client timeout after successful payment → server state recovery".
     *
     * <p>The customer completed checkout and the client died before telling us. No
     * webhook, no confirm call, and an order sitting in DRAFT that the supplier
     * will never see. Asking the provider is the only way to find out, and it is
     * why this job exists rather than trusting callbacks.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.payments.reconcile-interval:PT60S}")
    @SchedulerLock(name = "payment-reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void reconcileStale() {
        var staleBefore = Instant.now().minus(Duration.ofMinutes(2));
        var stale = payments.findStale(staleBefore);

        for (var payment : stale) {
            if (payment.getProviderPaymentId() == null && payment.getProviderOrderId() == null) {
                continue;
            }
            try {
                if (payment.getProviderPaymentId() == null) {
                    // We only have the intent, so there is nothing to ask about
                    // yet — the customer never started. It will be abandoned by
                    // the cart's own expiry rather than here.
                    continue;
                }

                var providerPayment = provider.fetchPayment(payment.getProviderPaymentId());
                var updated = paymentService.applyProviderState(
                        payment, providerPayment, "RECONCILE");

                if (updated.getStatus().fundsSecured()) {
                    // The order the customer paid for, finally released.
                    orderRelease.releaseIfFunded(updated.getSupplierOrderId());
                } else if (updated.getStatus() == PaymentStatus.FAILED) {
                    orderRelease.abandonUnfunded(updated.getSupplierOrderId(),
                            "Payment failed: " + String.valueOf(updated.getFailureCode()));
                }

            } catch (PaymentProviderException ex) {
                // Unreachable providers are normal. The next sweep asks again.
                log.debug("Could not reconcile payment {}: {}", payment.getId(), ex.getMessage());
            } catch (RuntimeException ex) {
                log.error("Could not reconcile payment {}", payment.getId(), ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.payments.refund-interval:PT30S}")
    @SchedulerLock(name = "payment-refund", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void processRefunds() {
        var pending = refunds.findByStatusIn(
                java.util.List.of(RefundStatus.REQUESTED, RefundStatus.FAILED));

        for (var refund : pending) {
            try {
                refundService.process(refund.getId());
            } catch (RuntimeException ex) {
                log.error("Could not process refund {}", refund.getId(), ex);
            }
        }
    }
}
