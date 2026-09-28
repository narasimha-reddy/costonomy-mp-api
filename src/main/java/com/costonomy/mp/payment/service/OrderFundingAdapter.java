package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
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
                payment = paymentService.createForOrder(
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

    @Override
    @Transactional(readOnly = true)
    public boolean isFundingSecured(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .map(payment -> payment.getStatus().fundsSecured())
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FundingIntent> openIntent(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .filter(payment -> payment.getStatus() == PaymentStatus.CREATED
                        && payment.getProviderOrderId() != null)
                .map(payment -> new FundingIntent(
                        supplierOrderId, payment.getId(), payment.getProvider(),
                        payment.getProviderOrderId(), payment.getAuthorizedAmount(),
                        payment.getCurrency(), publicKeyFor(payment.getProvider())));
    }

    /**
     * How long a provider holds an authorisation before it lapses back to the
     * customer — Razorpay's manual-capture maximum, five days — less a margin, so
     * nothing is handed over against a hold about to expire (D-103).
     */
    static final java.time.Duration HOLD_USABLE_FOR = java.time.Duration.ofDays(5).minusHours(6);

    @Override
    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Nothing, for prepaid, since D-103: the money stays held until the goods
        // are about to leave (onOrderDispatched). Credit still draws here.
    }

    @Override
    @Transactional
    public void onOrderDispatched(Long supplierOrderId, BigDecimal amount) {
        // Marks only. The provider call happens after this transaction commits —
        // see PaymentService.markForCapture for why that separation matters.
        try (var trace = TraceScope.of("order", supplierOrderId)) {
            paymentService.markForCapture(supplierOrderId, amount);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canTakeFunds(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .map(payment -> switch (payment.getStatus()) {
                    case AUTHORIZED -> payment.getAuthorizedAt() != null
                            && payment.getAuthorizedAt().isAfter(java.time.Instant.now().minus(HOLD_USABLE_FOR));
                    case CAPTURE_PENDING, CAPTURED, PARTIALLY_REFUNDED -> true;
                    default -> false;
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

        if (payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            // Money was taken — since D-103 only once an order was ready, after
            // which it cannot be cancelled, so this is an order from before, or a
            // path that bypassed that. It used to log "a refund is required" and
            // stop: the restaurant was charged for a cancelled order and nothing
            // ever refunded it. Now the full remaining amount is refunded, once
            // (the key is the order's), with no one having to ask — to the
            // outlet's wallet, from which it can go back to the card (D-104).
            refundService.refundCancelled(payment, reason);
            return;
        }

        paymentService.releaseOrRefund(supplierOrderId,
                RefundReason.SUPPLIER_REJECTION, reason);
    }

    /** The payment's own status: AUTHORIZED, CAPTURED, RELEASED, PARTIALLY_REFUNDED… */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> paymentState(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId).map(payment -> payment.getStatus().name());
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
