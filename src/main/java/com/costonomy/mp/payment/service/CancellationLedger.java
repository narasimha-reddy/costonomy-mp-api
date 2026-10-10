package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;

/**
 * The writes that end a cancelled order's payment (D-109): a hold dropped, or
 * money captured and a refund raised to send it back.
 *
 * <p>Shared by the cancel transaction, the cancellation job and the capture job,
 * so that each of them ends a payment the same way and writes the same rows. It
 * calls no provider and opens no transaction: every method runs inside its
 * caller's, on a payment the caller has already locked, which is what makes
 * "status re-checked under the lock, then written" one step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancellationLedger {

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final SupplierOrderRepository orders;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final CancelRefundSpeed refundSpeed;

    /** The refund a cancellation raises is keyed on the order, whichever path raises it. */
    static String cancelRefundKey(Long supplierOrderId) {
        return "cancel-order-" + supplierOrderId;
    }

    /** The key on the capture that returns a debited payment, stable per payment. */
    static String cancelCaptureKey(Long paymentId) {
        return "cancel-capture-" + paymentId;
    }

    /**
     * Drop a hold: nothing was taken, so nothing is refunded.
     *
     * <p>{@code CARD_HOLD_DROPPED} for a card the bank was only holding;
     * {@code PROVIDER_AUTO_REFUND} where Razorpay gave the money back itself.
     */
    void release(Payment payment, ReleaseReason reason, String detail, String source) {
        var previous = payment.getStatus();
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setReleasedAmount(payment.getAuthorizedAmount());
        payment.setReleasedAt(Instant.now());
        payment.setReleaseReason(reason);
        payments.save(payment);

        record(payment, "RELEASE", payment.getAuthorizedAmount(), "SUCCESS",
                payment.getProviderPaymentId(), null);
        auditService.record(null, null, "PAYMENT_RELEASED", "PAYMENT", payment.getId(),
                previous.name(), PaymentStatus.RELEASED.name(),
                truncate(reason.name() + (detail == null ? "" : ": " + detail)), source);
        log.info("Payment {} {} → RELEASED ({}, via {})", payment.getId(), previous, reason, source);
    }

    /**
     * Our own capture of a debited payment went through: record it, and raise the
     * refund that sends it back — one write, so there is never a captured
     * cancelled payment with no refund.
     *
     * @param fee what Razorpay kept on the capture, if it has said
     * @return the refund, or null if one already existed or nothing is left to return
     */
    Refund captureForReturn(Payment payment, BigDecimal fee, String source) {
        var previous = payment.getStatus();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedAmount(payment.getAuthorizedAmount());
        payment.setCapturedAt(Instant.now());
        if (fee != null) {
            payment.setProviderFee(fee);
        }
        payments.save(payment);

        record(payment, "CAPTURE", payment.getCapturedAmount(), "SUCCESS",
                payment.getProviderPaymentId(), cancelCaptureKey(payment.getId()));
        auditService.record(null, null, "PAYMENT_CAPTURED", "PAYMENT", payment.getId(),
                previous.name(), PaymentStatus.CAPTURED.name(),
                "Captured to return it: order cancelled", source);

        var refund = requestRefund(payment, source);
        log.info("Payment {} {} → CAPTURED; refund {} REQUESTED for {} to the original {}",
                payment.getId(), previous, refund == null ? "(none)" : refund.getId(),
                refund == null ? "0" : refund.getAmount().toPlainString(),
                methodLabel(payment));
        return refund;
    }

    /**
     * Raise the one cancellation refund of a captured payment, to where it came
     * from. Idempotent on the order's key, which the legacy wallet path uses too,
     * so at most one cancellation refund ever exists for an order.
     */
    Refund requestRefund(Payment payment, String source) {
        String key = cancelRefundKey(payment.getSupplierOrderId());
        var existing = refunds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("Payment {} already has its cancellation refund {}", payment.getId(),
                    existing.get().getId());
            return null;
        }
        BigDecimal amount = payment.refundableAmount();
        if (amount.signum() <= 0) {
            return null;
        }

        var refund = new Refund();
        refund.setPaymentId(payment.getId());
        refund.setSupplierOrderId(payment.getSupplierOrderId());
        refund.setAmount(amount);
        refund.setReason(RefundReason.CANCELLATION);
        refund.setDestination(RefundDestination.ORIGINAL);
        refund.setNote("Order cancelled before dispatch; paid by " + methodLabel(payment));
        refund.setStatus(RefundStatus.REQUESTED);
        refund.setIdempotencyKey(key);
        refunds.saveAndFlush(refund);

        auditService.record(null, null, "REFUND_REQUESTED", "REFUND", refund.getId(),
                null, RefundStatus.REQUESTED.name(),
                "CANCELLATION " + amount.toPlainString() + " to the original payment method", source);

        var payload = new HashMap<String, Object>();
        payload.put("paymentId", payment.getId());
        payload.put("supplierOrderId", payment.getSupplierOrderId());
        payload.put("outletId", payment.getOutletId());
        payload.put("amount", amount.toPlainString());
        payload.put("destination", RefundDestination.ORIGINAL.name());
        payload.put("reason", RefundReason.CANCELLATION.name());
        orders.findById(payment.getSupplierOrderId())
                .ifPresent(order -> payload.put("orderNumber", order.getOrderNumber()));
        // The message must not promise "5-7 working days" for a refund asked to be instant (F5).
        if (refundSpeed.instant()) {
            payload.put("notificationVariant", "INSTANT");
        }
        outbox.publish("RefundRequested", "REFUND", refund.getId(), payload, null);
        return refund;
    }

    private void record(Payment payment, String type, BigDecimal amount, String status,
                        String providerReference, String idempotencyKey) {
        transactions.save(PaymentTransaction.builder()
                .paymentId(payment.getId())
                .transactionType(type)
                .amount(amount)
                .currency(payment.getCurrency())
                .status(status)
                .providerReference(providerReference)
                .idempotencyKey(idempotencyKey)
                .build());
    }

    private static String methodLabel(Payment payment) {
        return payment.getProviderMethod() == null ? "payment method" : payment.getProviderMethod();
    }

    private static String truncate(String text) {
        return text.length() <= 480 ? text : text.substring(0, 480);
    }
}
