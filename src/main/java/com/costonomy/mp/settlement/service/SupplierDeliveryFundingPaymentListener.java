package com.costonomy.mp.settlement.service;

import com.costonomy.mp.payment.service.ProviderPaymentListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Claims the gateway webhooks of supplier delivery funding checkouts (D-203). The webhook service offers it every
 * payment it has no order payment for; if the Razorpay order is a funding charge's, the payment is fetched from the
 * gateway (the payload is never trusted) and the charge is either marked paid or the payment is refunded.
 */
@Component
@RequiredArgsConstructor
public class SupplierDeliveryFundingPaymentListener implements ProviderPaymentListener {

    private final SupplierDeliveryFundingPaymentService funding;

    @Override
    public boolean onProviderPayment(String providerOrderId, String providerPaymentId, String eventType) {
        return funding.reconcileWebhookPayment(providerOrderId, providerPaymentId);
    }
}
