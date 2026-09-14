package com.costonomy.mp.payment.service;

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

    @Override
    public String paymentMethod() {
        return "PREPAID";
    }

    @Override
    @Transactional
    public List<FundingIntent> arrangeFunding(List<SupplierOrder> orders) {
        List<FundingIntent> intents = new ArrayList<>();

        for (SupplierOrder order : orders) {
            var payment = paymentService.createForOrder(
                    order.getId(), order.getProcurementId(), order.getOutletId(),
                    order.getTotalAmount(), order.getPaymentMethod());

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
    @Transactional
    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Marks only. The provider call happens after this transaction commits —
        // see PaymentService.markForCapture for why that separation matters.
        paymentService.markForCapture(supplierOrderId, acceptedAmount);
    }

    @Override
    @Transactional
    public void onOrderUnfulfilled(Long supplierOrderId, String reason) {
        var payment = payments.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            // Captured before the supplier resolved — a race the reconciliation
            // path can produce. Returning captured money is a refund, and
            // RefundService owns that so idempotency lives in one place.
            log.info("Order {} was unfulfilled after capture; a refund is required", supplierOrderId);
            return;
        }

        paymentService.releaseOrRefund(supplierOrderId,
                RefundReason.SUPPLIER_REJECTION, reason);
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
