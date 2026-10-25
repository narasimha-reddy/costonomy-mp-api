package com.costonomy.mp.delivery.provider.payment;

import org.springframework.stereotype.Component;

/**
 * Providers that cannot be paid through an API: a person pays by bank transfer or against the provider's invoice, then
 * records it (D-204). The system never sends anything through this rail, so a payable on it waits in the finance queue
 * until someone records the payment.
 */
@Component
public class ManualCourierPaymentRail implements CourierPaymentRail {

    public static final String CODE = "MANUAL";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean automatic() {
        return false;
    }

    @Override
    public PayResult pay(PayRequest request) {
        throw new UnsupportedOperationException("The manual rail is paid by a person and recorded; it never sends");
    }

    @Override
    public LookupResult lookup(String idempotencyKey) {
        return LookupResult.unknown();
    }
}
