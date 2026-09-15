package com.costonomy.mp.delivery;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands the delivery tests the payment mock.
 *
 * <p>Delivery tests need a funded order — an order no supplier can see is an order
 * no courier can collect — but they are not about payment. Routing that through
 * one small accessor keeps the dependency visible rather than scattering
 * payment-module imports through a delivery suite.
 */
@Component
@RequiredArgsConstructor
public class TestPaymentAccess {

    private final MockPaymentProvider provider;

    public MockPaymentProvider provider() {
        return provider;
    }
}
