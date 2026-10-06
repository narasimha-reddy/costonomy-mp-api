package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundDestination;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.payment.domain.ReleaseReason;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.service.OrderFundingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Funds orders with real payments. Implements procurement's {@link OrderFundingPort}.
 *
 * <p>This class is what closes the gap recorded as OPEN-004: before it existed, a
 * submitted order reached its supplier with {@code payment_status = PENDING} and
 * nothing had been authorised, which guardrail 16 and doc 01 §14 both forbid.
 *
 * <p>The dependency points from payment to procurement, not the other way round.
 * Procurement knows an order must be funded; it does not know what a payment is.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFundingAdapter implements OrderFundingPort {

    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final RefundService refundService;
    private final RefundRepository refunds;
    private final PaymentHoldPolicy holdPolicy;

    @Override
    public String paymentMethod() {
        return "PREPAID";
    }

    @Override
    @Transactional
    public List<FundingIntent> arrangeFunding(List<SupplierOrder> orders) {
        List<FundingIntent> intents = new ArrayList<>();

        for (SupplierOrder order : orders) {
            Payment payment;
            try (var trace = TraceScope.of("order", order.getId(), "order_number", order.getOrderNumber())) {
                // The row only: the provider's checkout is opened after the order commits (prepareCheckout, D-136).
                payment = paymentService.recordForOrder(
                        order.getId(), order.getProcurementId(), order.getOutletId(),
                        order.getTotalAmount(), order.getPaymentMethod());
            }

            intents.add(new FundingIntent(
                    order.getId(), payment.getId(), payment.getProvider(),
                    payment.getProviderOrderId(), payment.getAuthorizedAmount(),
                    payment.getCurrency(),
                    // Publishable only. Doc 09 §4: no secret ever reaches a client.
                    publicKeyFor(payment.getProvider())));
        }

        return intents;
    }

    /**
     * Opens the provider's checkout for this order's payment, after the order has committed (D-136). Not
     * transactional: it makes a network call to the provider.
     */
    @Override
    public java.util.Optional<FundingIntent> prepareCheckout(Long supplierOrderId) {
        var payment = payments.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return java.util.Optional.empty();
        }
        paymentService.openCheckout(payment.getId());
        return openIntent(supplierOrderId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFundingSecured(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                // Not the status alone: money captured only to be sent back (D-109) is
                // CAPTURED and funds nothing. Asked here, it is asked of every release
                // trigger at once, whichever of them fires (F1).
                .map(Payment::fundsSecuredForOrder)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FundingIntent> openIntent(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                // Not for an order that was cancelled: a checkout handed out for it (the
                // replay of a create request, say) would take the payer's money only for it
                // to be captured and refunded at our cost. The payment-intent read makes the
                // same refusal (D-109).
                .filter(payment -> payment.getStatus() == PaymentStatus.CREATED
                        && payment.getProviderOrderId() != null
                        && payment.getCancelRequestedAt() == null)
                .map(payment -> new FundingIntent(
                        supplierOrderId, payment.getId(), payment.getProvider(),
                        payment.getProviderOrderId(), payment.getAuthorizedAmount(),
                        payment.getCurrency(), publicKeyFor(payment.getProvider())));
    }

    @Override
    @Transactional
    public void relinquishUnfunded(Long supplierOrderId) {
        // Locked, so a card authorisation arriving now and this switch have one winner: whichever takes the row first.
        var payment = payments.lockBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.CREATED || payment.getCancelRequestedAt() != null) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Your card payment has already gone through.");
        }
        // The same mark a cancellation leaves (D-109): every guard that refuses to let a payment fund an order reads
        // it, and money that still arrives is returned. Razorpay has no way to cancel an order, so it stays open there.
        payment.setCancelRequestedAt(java.time.Instant.now());
        payments.save(payment);
        log.info("Payment {} of order {} superseded: the order is being paid another way",
                payment.getId(), supplierOrderId);
    }

    @Override
    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Nothing, for prepaid, since D-103: the money stays held until the goods
        // are about to leave (onOrderDispatched). Credit still draws here.
    }

    @Override
    @Transactional
    public Reduction onOrderDispatched(Long supplierOrderId, BigDecimal finalPayable, BigDecimal reductionAmount) {
        // Marks only. The provider call happens after this transaction commits —
        // see PaymentService.markForCapture for why that separation matters.
        try (var trace = TraceScope.of("order", supplierOrderId)) {
            paymentService.markForCapture(supplierOrderId, finalPayable);
        }
        // Whatever was held beyond the final payable is released by the capture, never refunded, so a weight
        // shortfall is already settled here (D-128).
        return Reduction.applied(payments.findBySupplierOrderId(supplierOrderId)
                .map(payment -> "payment:" + payment.getId()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canTakeFunds(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .map(payment -> {
                    if (payment.getCancelRequestedAt() != null) {
                        // The order was cancelled: whatever state the money is in, it is on
                        // its way back to the payer, and nothing may be handed over against
                        // it. CAPTURED here is our own capture made to refund it (F1).
                        return false;
                    }
                    return switch (payment.getStatus()) {
                        // Our own clock, against the expiry this payment was created with
                        // (PaymentHoldPolicy), which is what the provider fixed when the order
                        // was made, so the two cannot disagree (D-109). The setting as it reads
                        // today is used only for a payment with none stored (F3). It is the
                        // second line: a hold the provider has already returned is caught by the
                        // sweep, and becomes RELEASED, which is false here.
                        case AUTHORIZED -> payment.getAuthorizedAt() != null
                                && payment.getAuthorizedAt().isAfter(
                                        java.time.Instant.now().minus(holdPolicy.usableFor(payment)));
                        case CAPTURE_PENDING, CAPTURED, PARTIALLY_REFUNDED -> true;
                        default -> false;
                    };
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public void onOrderUnfulfilled(Long supplierOrderId, String reason) {
        var payment = payments.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }
        // The payment's ids on every line written under a cancellation (D-100): the
        // request that cancels the order knows nothing of payments, and its lines
        // would otherwise not be found by the payment's id.
        try (var trace = PaymentTrace.of(payment)) {
            cancelPayment(payment, supplierOrderId, reason);
        }
    }

    private void cancelPayment(Payment payment, Long supplierOrderId, String reason) {
        if (payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            // Money was taken — since D-103 only once an order was ready, after
            // which it cannot be cancelled, so this is an order from before, or a
            // path that bypassed that. It used to log "a refund is required" and
            // stop: the restaurant was charged for a cancelled order and nothing
            // ever refunded it. Now the full remaining amount is refunded, once
            // (the key is the order's), with no one having to ask — to the
            // outlet's wallet, from which it can go back to the card (D-104).
            //
            // Read unlocked and refunded first, on purpose: the refund takes the
            // wallet and then the payment, and taking the payment first here would
            // reverse that order against a withdrawal or a dispute refund.
            refundService.refundCancelled(payment, reason);
        }

        // Everything else, and the note that this order was cancelled: what a
        // held payment becomes is decided in one place (D-109), with no call to the
        // provider inside this transaction (D-099).
        paymentService.onOrderCancelled(supplierOrderId, reason);
    }

    /**
     * The payment's own status, except where the order was cancelled and money is on
     * its way back (D-109), which reads:
     *
     * <ul>
     *   <li>{@code RETURNING} — debited money of a cancelled order is being sent back
     *       (CANCEL_PENDING, or captured with its cancellation refund still open);</li>
     *   <li>{@code RETURNED} — the payment provider returned it itself;</li>
     *   <li>{@code RETURN_DELAYED} — the return is stuck and a person has been told.</li>
     * </ul>
     *
     * Otherwise AUTHORIZED, CAPTURED, RELEASED, PARTIALLY_REFUNDED, FULLY_REFUNDED…
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> paymentState(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId).map(this::displayState);
    }

    private String displayState(Payment payment) {
        return switch (payment.getStatus()) {
            case CANCEL_PENDING -> payment.getReviewRequiredAt() != null ? "RETURN_DELAYED" : "RETURNING";
            case RELEASED -> payment.getReleaseReason() == ReleaseReason.PROVIDER_AUTO_REFUND
                    ? "RETURNED" : payment.getStatus().name();
            case CAPTURED, PARTIALLY_REFUNDED -> openCancellationRefund(payment);
            default -> payment.getStatus().name();
        };
    }

    /** RETURNING or RETURN_DELAYED while the order's cancellation refund is still open; else the status. */
    private String openCancellationRefund(Payment payment) {
        if (payment.getCancelRequestedAt() == null) {
            return payment.getStatus().name();
        }
        return refunds.findByIdempotencyKey(CancellationLedger.cancelRefundKey(payment.getSupplierOrderId()))
                .filter(refund -> refund.getDestination() == RefundDestination.ORIGINAL)
                .map(refund -> switch (refund.getStatus()) {
                    // REJECTED is being checked with the provider (D-110); if it ends in review it reads as delayed.
                    case REQUESTED, PROCESSING, FAILED, REJECTED -> "RETURNING";
                    case NEEDS_REVIEW -> "RETURN_DELAYED";
                    // REVERSED: operations sent it to the wallet instead; the payment says so.
                    case COMPLETED, REVERSED -> payment.getStatus().name();
                })
                .orElse(payment.getStatus().name());
    }

    /**
     * The refund that sends a cancelled order's debited money back to where it came
     * from, for the order's own JSON. Only that refund: a legacy refund of an order
     * cancelled after capture goes to the wallet, and is not "money coming back to
     * your account".
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<CancelRefund> cancelRefund(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .filter(payment -> payment.getCancelRequestedAt() != null)
                .flatMap(payment -> refunds.findByIdempotencyKey(CancellationLedger.cancelRefundKey(supplierOrderId)))
                .filter(refund -> refund.getDestination() == RefundDestination.ORIGINAL)
                .map(refund -> new CancelRefund(refund.getAmount(),
                        refund.getStatus() == RefundStatus.COMPLETED ? refund.getCompletedAt() : null));
    }

    /** How the payer paid, once read from the provider: card, upi, netbanking… */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> paymentInstrument(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .map(Payment::getProviderMethod);
    }

    @Override
    public BigDecimal refundableToWallet(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .map(payment -> refundService.refundableFor(payment.getId()))
                .orElse(BigDecimal.ZERO);
    }

    /** A wallet refund of the order's captured payment (D-104). */
    @Override
    public Long refundToWallet(Long supplierOrderId, BigDecimal amount, String key,
                               Long actorId, String note) {
        var payment = payments.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new com.costonomy.mp.common.error.BusinessException(
                        com.costonomy.mp.common.error.ErrorCode.PAYMENT_STATE_CONFLICT,
                        "This order has no payment to refund."));
        return refundService.refundToWallet(actorId, payment.getId(), amount,
                RefundReason.DISPUTE_RESOLVED, note, key).getId();
    }

    /**
     * A doorstep rejection on a card payment (D-128, D-129): a refund to the wallet, withdrawable (D-104),
     * keyed so a repeat refunds nothing more.
     *
     * <p>While the payment is still being captured there is nothing to give back yet, so this returns
     * {@code DEFERRED} and writes nothing. The final payable is already reduced; the order adjustment job applies
     * the refund once the payment is captured. Shrinking the pending capture instead would race the capture job,
     * which reads the amount without a lock and calls the provider outside any transaction.
     */
    @Override
    @Transactional
    public Reduction reduceAfterDispatch(Long supplierOrderId, BigDecimal amount, BigDecimal newFinalPayable,
                                         String key, Long actorId, String reason) {
        var payment = payments.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new com.costonomy.mp.common.error.BusinessException(
                        com.costonomy.mp.common.error.ErrorCode.PAYMENT_STATE_CONFLICT,
                        "This order has no payment to refund."));
        var refund = refundService.refundToWalletOnceCaptured(actorId, payment.getId(), amount,
                RefundReason.DOORSTEP_REJECTION, reason, key);
        return refund == null ? Reduction.deferred() : Reduction.applied("refund:" + refund.getId());
    }

    /**
     * The provider's publishable key.
     *
     * <p>Read from the adapter rather than configuration so there is no property a
     * secret could be put in by mistake — the mock has no real key, and Razorpay's
     * key id is public by design.
     */
    private String publicKeyFor(String providerName) {
        return provider.name().equals(providerName)
                ? provider.createAuthorizationPublicKey() : null;
    }
}
