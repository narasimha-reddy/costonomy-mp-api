package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Payment state. Doc 03 §6, doc 21.
 *
 * <p>Two rules govern everything here.
 *
 * <p><b>The provider is the authority.</b> A payment becomes {@code AUTHORIZED}
 * because the provider says so — via a verified webhook, or because we asked them
 * — never because a client reported success. Doc 01 §14 and guardrail 3 make this
 * absolute, and it is the reason {@code confirm()} calls
 * {@link PaymentProvider#fetchPayment} instead of trusting its own request body.
 *
 * <p><b>Transitions are checked, never assumed.</b> Doc 03 §6 warns that webhooks
 * arrive out of order, so a late {@code authorized} event cannot drag a captured
 * payment backwards. {@link #transition} refuses rather than reorders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentProvider provider;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── Creation ─────────────────────────────────────────────────────────

    /**
     * Create a payment intent for an order.
     *
     * <p>Idempotent by construction: {@code uk_payment_order} means one payment per
     * order, so a resubmission returns the existing intent rather than creating a
     * second authorisation against the same order (doc 10 §2).
     */
    @Transactional
    public Payment createForOrder(Long supplierOrderId, Long procurementId, Long outletId,
                                  BigDecimal amount, String paymentMethod) {

        var existing = payments.findBySupplierOrderId(supplierOrderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var payment = new Payment();
        payment.setSupplierOrderId(supplierOrderId);
        payment.setProcurementId(procurementId);
        payment.setOutletId(outletId);
        payment.setProvider(provider.name());
        payment.setPaymentMethod(paymentMethod);
        payment.setAuthorizedAmount(amount);
        payment.setStatus(PaymentStatus.CREATED);
        payments.saveAndFlush(payment);

        try {
            var intent = provider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                    "order-" + supplierOrderId, amount, "INR",
                    "Mandi order " + supplierOrderId,
                    // Derived from the order, not random: a retry of the same
                    // submission reaches the same intent on the provider's side.
                    "auth-order-" + supplierOrderId));

            payment.setProviderOrderId(intent.providerOrderId());
            payments.save(payment);

        } catch (PaymentProviderException ex) {
            // The order cannot be funded, so it must not reach the supplier.
            fail(payment, ex.providerCode(), ex.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                    "We couldn't set up payment for this order. Please try again.");
        }

        return payment;
    }

    // ── Authorization ────────────────────────────────────────────────────

    /**
     * Confirm a payment by asking the provider what happened.
     *
     * <p>The client tells us <em>which</em> payment to look at and nothing more.
     * Doc 01 §14, guardrail 3, and §23A.19 all say the same thing from different
     * angles: a client callback is not financial truth.
     *
     * <p>This is also doc 46's recovery path. A client that timed out after paying
     * calls here — or never calls, and the reconciliation job asks on its behalf.
     */
    @Transactional
    public Payment confirm(Long paymentId, String providerPaymentId) {
        var payment = load(paymentId);

        if (payment.getStatus().fundsSecured()) {
            // Already resolved, by a webhook or an earlier confirm. Returning the
            // payment is the true answer to "did my payment go through?".
            return payment;
        }

        PaymentProvider.ProviderPayment providerPayment;
        try {
            providerPayment = provider.fetchPayment(providerPaymentId);
        } catch (PaymentProviderException ex) {
            if (ex.isRetryable()) {
                // We could not ask. Leaving the payment alone is correct — the
                // reconciliation job will ask again. Claiming failure here could
                // release an authorisation the customer actually completed.
                throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                        "We couldn't confirm your payment just yet. We'll keep checking.");
            }
            // The provider refused to look it up: it does not know that id. That
            // says the client's claim is wrong, not that this payment failed —
            // and FAILED is terminal, so failing it here would let one stale or
            // garbled id make an order unpayable for good. Refuse the claim and
            // leave the payment waiting for its own money, as a mismatch does.
            log.warn("Confirm for payment {} named a payment the provider refused: {}",
                    paymentId, ex.getMessage());
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We couldn't find that payment. Nothing has changed on this order.");
        }

        if (!completes(payment, providerPayment)) {
            // A real payment id, but not this payment's. Without this check any
            // authorised payment — a cheaper one, someone else's — would fund
            // this order. Nothing changes; the payment is still waiting for its
            // own money.
            auditService.record(null, null, "PAYMENT_CONFIRM_MISMATCH", "PAYMENT",
                    payment.getId(), payment.getStatus().name(), payment.getStatus().name(),
                    "Provider payment " + providerPaymentId + " belongs to order "
                            + providerPayment.providerOrderId(), "CLIENT");
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment doesn't belong to this order.");
        }

        return applyProviderState(payment, providerPayment, "CONFIRM");
    }

    /**
     * Whether a provider payment is the one completing this payment's intent.
     *
     * <p>The provider says a payment exists and what state it is in; only this
     * says it is <em>ours</em>. It must complete the intent we created — the
     * provider order id we minted — and, the first time money appears against
     * it, for the amount we asked for.
     */
    boolean completes(Payment payment, PaymentProvider.ProviderPayment providerPayment) {
        if (payment.getProviderOrderId() == null
                || !payment.getProviderOrderId().equals(providerPayment.providerOrderId())) {
            return false;
        }
        boolean moneyArrives = payment.getStatus() == PaymentStatus.CREATED
                && (providerPayment.status() == PaymentProvider.ProviderPaymentStatus.AUTHORIZED
                        || providerPayment.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED);
        return !moneyArrives
                || providerPayment.authorizedAmount().compareTo(payment.getAuthorizedAmount()) == 0;
    }

    /**
     * Reconcile our record against the provider's. Doc 21.
     *
     * <p>Used by webhooks and by the reconciliation job. Deliberately ignores
     * anything that would move the payment backwards, because an out-of-order
     * webhook is a statement about the past, not the present.
     */
    @Transactional
    public Payment applyProviderState(Payment payment,
                                      PaymentProvider.ProviderPayment providerPayment,
                                      String source) {

        if (!completes(payment, providerPayment)) {
            // Never applied, whoever brought it: a webhook, the sweep or a
            // capture. Logged at error because it should not happen — the
            // provider describing a payment against someone else's intent means
            // a bug on one side, and a human should look.
            log.error("Ignoring {} provider payment {} for payment {}: it completes order {}, not {}",
                    source, providerPayment.providerPaymentId(), payment.getId(),
                    providerPayment.providerOrderId(), payment.getProviderOrderId());
            return payment;
        }

        payment.setProviderPaymentId(providerPayment.providerPaymentId());
        payment.setReconciledAt(Instant.now());

        PaymentStatus target = switch (providerPayment.status()) {
            case AUTHORIZED -> PaymentStatus.AUTHORIZED;
            case CAPTURED -> PaymentStatus.CAPTURED;
            case FAILED -> PaymentStatus.FAILED;
            case RELEASED -> PaymentStatus.RELEASED;
            case REFUNDED -> PaymentStatus.FULLY_REFUNDED;
            case CREATED -> PaymentStatus.CREATED;
        };

        if (target == payment.getStatus()) {
            payments.save(payment);
            return payment;
        }

        if (!payment.getStatus().canTransitionTo(target)) {
            // A late authorisation event arriving after capture, typically. The
            // provider is describing something we already moved past; recording
            // that we heard it is enough (doc 03 §6, doc 46).
            log.info("Ignoring out-of-order {} event for payment {}: {} cannot become {}",
                    source, payment.getId(), payment.getStatus(), target);
            payments.save(payment);
            return payment;
        }

        var previous = payment.getStatus();
        payment.setStatus(target);

        switch (target) {
            case AUTHORIZED -> {
                payment.setAuthorizedAt(Instant.now());
                payment.setAuthorizedAmount(providerPayment.authorizedAmount());
                record(payment, "AUTHORIZE", providerPayment.authorizedAmount(),
                        "SUCCESS", providerPayment.providerPaymentId(), null, null);
                outbox.publish("PaymentAuthorized", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "amount", providerPayment.authorizedAmount().toPlainString()),
                        null);
            }
            case CAPTURED -> {
                payment.setCapturedAt(Instant.now());
                payment.setCapturedAmount(providerPayment.capturedAmount());
                record(payment, "CAPTURE", providerPayment.capturedAmount(),
                        "SUCCESS", providerPayment.providerPaymentId(), null, null);
                outbox.publish("PaymentCaptured", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "amount", providerPayment.capturedAmount().toPlainString()),
                        null);
            }
            case FAILED -> {
                payment.setFailureCode(providerPayment.failureCode());
                payment.setFailureReason(providerPayment.failureReason());
                outbox.publish("PaymentFailed", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "reason", String.valueOf(providerPayment.failureCode())),
                        null);
            }
            default -> { }
        }

        payments.save(payment);
        auditService.record(null, null, "PAYMENT_" + target.name(), "PAYMENT",
                payment.getId(), previous.name(), target.name(), source, "PROVIDER");

        return payment;
    }

    // ── Capture and release ──────────────────────────────────────────────

    /**
     * Mark a payment for capture. Does not call the provider.
     *
     * <p>Runs inside the transaction that records the supplier's acceptance, so it
     * must not do network I/O: a slow gateway would hold that transaction open, and
     * a gateway failure would roll back an acceptance that really happened. The
     * capture itself is performed by {@link PaymentCaptureJob} afterwards and can be
     * retried until it succeeds — which is what {@code CAPTURE_PENDING} is for.
     */
    @Transactional
    public void markForCapture(Long supplierOrderId, BigDecimal acceptedAmount) {
        var payment = payments.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        if (!payment.getStatus().canTransitionTo(PaymentStatus.CAPTURE_PENDING)) {
            log.warn("Cannot mark payment {} for capture from {}",
                    payment.getId(), payment.getStatus());
            return;
        }

        // The accepted amount, never the authorised one (doc 01 §14). Clamped
        // because capturing more than was held would be rejected by the provider
        // and, if it were not, would be a charge nobody agreed to.
        BigDecimal toCapture = acceptedAmount.min(payment.getAuthorizedAmount());

        payment.setStatus(PaymentStatus.CAPTURE_PENDING);
        payment.setCapturedAmount(toCapture);
        payments.save(payment);

        auditService.record(null, null, "PAYMENT_CAPTURE_PENDING", "PAYMENT", payment.getId(),
                PaymentStatus.AUTHORIZED.name(), PaymentStatus.CAPTURE_PENDING.name(),
                "Accepted " + toCapture.toPlainString(), "SYSTEM");
    }

    /** Perform a pending capture against the provider. Called by the capture job. */
    @Transactional
    public void performCapture(Long paymentId) {
        var payment = load(paymentId);
        if (payment.getStatus() != PaymentStatus.CAPTURE_PENDING) {
            return;
        }

        try {
            var captured = provider.capture(payment.getProviderPaymentId(),
                    payment.getCapturedAmount(),
                    // Stable per payment: a retry after a network failure reaches
                    // the same capture on the provider's side rather than a second one.
                    "capture-payment-" + paymentId);

            applyProviderState(payment, captured, "CAPTURE_JOB");

            // The unaccepted remainder was never taken, so it is released rather
            // than refunded — faster for the customer, and not a reversal on their
            // statement.
            BigDecimal remainder = payment.getAuthorizedAmount()
                    .subtract(payment.getCapturedAmount());
            if (remainder.signum() > 0) {
                payment.setReleasedAmount(remainder);
                record(payment, "RELEASE", remainder, "SUCCESS",
                        payment.getProviderPaymentId(), null, null);
                payments.save(payment);
            }

        } catch (PaymentProviderException ex) {
            record(payment, "CAPTURE", payment.getCapturedAmount(), "FAILED",
                    null, ex.providerCode(), ex.getMessage());

            if (ex.isRetryable()) {
                // Back to AUTHORIZED: the money is still held, so the payment is
                // exactly where it was and the job will try again.
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setCapturedAmount(BigDecimal.ZERO);
                payments.save(payment);
                log.warn("Capture of payment {} failed, will retry: {}", paymentId, ex.getMessage());
            } else {
                fail(payment, ex.providerCode(), ex.getMessage());
            }
        }
    }

    /**
     * Return everything for an order nobody fulfilled.
     *
     * <p>Releases an authorisation, or refunds if the money was already captured.
     * Doc 01 §14: a supplier who rejects or times out receives nothing, and neither
     * does Mandi.
     */
    @Transactional
    public void releaseOrRefund(Long supplierOrderId, RefundReason reason, String note) {
        var payment = payments.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            // Money was taken. Returning it is a refund, and belongs to
            // RefundService — which keeps the idempotency and provider handling in
            // one place rather than duplicated here.
            return;
        }

        if (!payment.getStatus().canTransitionTo(PaymentStatus.RELEASED)) {
            return;
        }

        try {
            provider.release(payment.getProviderPaymentId(), "release-payment-" + payment.getId());
        } catch (PaymentProviderException ex) {
            // The hold lapses on its own at the provider, so failing to release
            // explicitly costs the customer time, not money. Recording our
            // intention is enough for reconciliation to finish the job.
            log.warn("Could not release authorization for payment {}: {}",
                    payment.getId(), ex.getMessage());
        }

        payment.setStatus(PaymentStatus.RELEASED);
        payment.setReleasedAmount(payment.getAuthorizedAmount());
        payment.setReleasedAt(Instant.now());
        payments.save(payment);

        record(payment, "RELEASE", payment.getAuthorizedAmount(), "SUCCESS",
                payment.getProviderPaymentId(), null, null);

        auditService.record(null, null, "PAYMENT_RELEASED", "PAYMENT", payment.getId(),
                PaymentStatus.AUTHORIZED.name(), PaymentStatus.RELEASED.name(),
                reason.name() + (note == null ? "" : ": " + note), "SYSTEM");
    }

    // ── Reads and helpers ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Payment load(Long paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public Payment loadForOrder(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("Payment", supplierOrderId));
    }

    private void fail(Payment payment, String code, String reason) {
        if (payment.getStatus().canTransitionTo(PaymentStatus.FAILED)) {
            payment.setStatus(PaymentStatus.FAILED);
        }
        payment.setFailureCode(code);
        payment.setFailureReason(reason);
        payments.save(payment);
    }

    void record(Payment payment, String type, BigDecimal amount, String status,
                String providerReference, String failureCode, String failureReason) {

        transactions.save(PaymentTransaction.builder()
                .paymentId(payment.getId())
                .transactionType(type)
                .amount(amount)
                .currency(payment.getCurrency())
                .status(status)
                .providerReference(providerReference)
                .failureCode(failureCode)
                .failureReason(failureReason)
                .build());
    }

    @Transactional
    public void transition(Payment payment, PaymentStatus target) {
        if (!payment.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This payment has already moved on.");
        }
        payment.setStatus(target);
        payments.save(payment);
    }
}
