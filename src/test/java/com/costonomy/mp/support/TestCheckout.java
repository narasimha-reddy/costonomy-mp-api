package com.costonomy.mp.support;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Pay for a submitted procurement.
 *
 * <p>Since Phase 9 a supplier order is created in {@code DRAFT} and only reaches
 * its supplier once funding is secured (guardrail 16). So every test about what a
 * supplier does with an order has to get it funded first — not because the test
 * is about payment, but because an unfunded order correctly does not exist as far
 * as the supplier is concerned.
 *
 * <p>This walks the real path: the customer completes checkout at the provider,
 * then the client confirms. Nothing is written directly into {@code payment},
 * which would let the funding gate rot without a single test noticing.
 */
public final class TestCheckout {

    private final MockPaymentProvider provider;
    private final ApiClient api;

    public TestCheckout(MockPaymentProvider provider, ApiClient api) {
        this.provider = provider;
        this.api = api;
    }

    /**
     * Pay every payment intent in a submit response, releasing each order to its
     * supplier.
     *
     * @param submitResponse the whole body, envelope included
     */
    public void payAll(String buyerToken, JsonNode submitResponse) throws Exception {
        for (JsonNode intent : submitResponse.at("/data/paymentIntents")) {
            var providerPayment = provider.completeCheckout(
                    intent.get("providerOrderId").asText());
            api.post(buyerToken,
                    "/api/v1/payments/" + intent.get("paymentId").asLong() + "/confirm",
                    Map.of("providerPaymentId", providerPayment.providerPaymentId()));
        }
    }
}
