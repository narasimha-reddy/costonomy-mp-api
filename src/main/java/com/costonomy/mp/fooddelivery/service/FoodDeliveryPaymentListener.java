package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.payment.service.ProviderPaymentListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Claims the gateway webhooks of food delivery checkouts (D-197). The webhook service offers it every payment it has no
 * order payment for; if the Razorpay order is a food delivery's, the payment is fetched from the gateway (the payload is
 * never trusted) and the delivery either uses it or the payment is refunded.
 */
@Component
@RequiredArgsConstructor
public class FoodDeliveryPaymentListener implements ProviderPaymentListener {

    private final FoodDeliveryBookingService booking;

    @Override
    public boolean onProviderPayment(String providerOrderId, String providerPaymentId, String eventType) {
        return booking.reconcileWebhookPayment(providerOrderId, providerPaymentId);
    }
}
