package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.Refund;

/**
 * The ids a payment's log lines carry (D-100): ours and Razorpay's, the same
 * keys everywhere, so one search finds a payment's whole sequence.
 */
public final class PaymentTrace {

    private PaymentTrace() {
    }

    public static TraceScope of(Payment payment) {
        return TraceScope.of(
                "payment", payment.getId(),
                "order", payment.getSupplierOrderId(),
                "rzp_order", payment.getProviderOrderId(),
                "rzp_payment", payment.getProviderPaymentId());
    }

    public static TraceScope of(Refund refund) {
        return TraceScope.of(
                "refund", refund.getId(),
                "payment", refund.getPaymentId(),
                "order", refund.getSupplierOrderId());
    }
}
