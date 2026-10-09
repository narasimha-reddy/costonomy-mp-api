package com.costonomy.mp.payment.service;

/**
 * Another module's claim on a provider payment that is not an order payment (D-196).
 *
 * <p>The webhook service asks each listener about a payment it has no order payment for, before it gives up and marks
 * the event IGNORED. A listener that owns the Razorpay order fetches the payment itself; it never trusts the payload.
 */
public interface ProviderPaymentListener {

    /**
     * @return true if this listener owns the order and handled the event; false to let the next one try
     */
    boolean onProviderPayment(String providerOrderId, String providerPaymentId, String eventType);
}
