package com.costonomy.mp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An order, made the way the product makes one. D-091.
 *
 * <p>Every suite downstream of an order used to build one through the cart:
 * add items, validate, submit, and the supplier accepted afterwards. That path
 * is gone — D-088 made the request the basket and D-091 removed the order
 * acceptance it relied on — so this walks the real sequence instead:
 *
 * <ol>
 *   <li>the kitchen adds a SKU, which opens a draft request for that supplier</li>
 *   <li>it is sent, which locks the price and starts the supplier's clock</li>
 *   <li>the supplier answers with what they will supply</li>
 *   <li>the kitchen orders against that answer, choosing how it travels</li>
 * </ol>
 *
 * <p>The order comes back {@code DRAFT} with a payment intent, exactly as the
 * app receives it. {@link TestCheckout} then pays, and {@code OrderReleaseService}
 * confirms it. Nothing here shortcuts a step, because a harness that skips the
 * request would let a supplier be sent goods they never agreed to and no test
 * would notice.
 */
public final class TestOrder {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final ApiClient api;

    public TestOrder(MockMvc mvc, ObjectMapper json, ApiClient api) {
        this.mvc = mvc;
        this.json = json;
        this.api = api;
    }

    /**
     * A created order and the payment that has to clear before a supplier sees it.
     *
     * @param paymentId      null when the order needs no payment intent, as credit does
     * @param providerOrderId the provider's handle, for driving the mock checkout
     */
    public record Created(
            long intentId,
            long itemId,
            long orderId,
            Long paymentId,
            String providerOrderId) {
    }

    /** Pickup, so no delivery quote is needed. The common case for a fixture. */
    public Created place(String buyerToken, long outletId, String sellerToken,
                         long skuId, int quantity) throws Exception {
        return place(buyerToken, outletId, sellerToken, skuId, quantity, quantity,
                "PICKUP", null, null);
    }

    /** With a payment method, for the credit suites. */
    public Created place(String buyerToken, long outletId, String sellerToken,
                         long skuId, int quantity, String paymentMethod) throws Exception {
        return place(buyerToken, outletId, sellerToken, skuId, quantity, quantity,
                "PICKUP", paymentMethod, null);
    }

    /**
     * The whole sequence.
     *
     * @param offered  what the supplier will supply, to set up a shortfall
     * @param mode     how the goods travel; a quote is taken when it is ours to carry
     */
    public Created place(String buyerToken, long outletId, String sellerToken,
                         long skuId, int quantity, int offered, String mode,
                         String paymentMethod, String unusedQuoteReference) throws Exception {

        var draft = api.post(buyerToken, "/api/v1/outlets/" + outletId + "/intent-items",
                Map.of("supplierSkuId", skuId, "quantity", quantity));
        long intentId = draft.at("/data/id").asLong();
        long itemId = draft.at("/data/items/0/id").asLong();

        api.post(buyerToken, "/api/v1/intents/" + intentId + "/send", Map.of());

        keyed(sellerToken, "/api/v1/intents/" + intentId + "/respond",
                Map.of("lines", List.of(
                        Map.of("intentItemId", itemId, "offeredQuantity", offered))));

        // The fee has to be known before the order exists, because it is part of
        // what is charged. Pickup is free and a supplier's own delivery is their
        // configured fee; only ours needs asking.
        String quoteReference = null;
        if ("COSTONOMY_DELIVERY".equals(mode)) {
            quoteReference = api.post(buyerToken,
                            "/api/v1/intents/" + intentId + "/delivery-quote", Map.of())
                    .at("/data/quoteReference").asText();
        }

        var body = new java.util.HashMap<String, Object>();
        body.put("deliveryMode", mode);
        if (quoteReference != null) {
            body.put("deliveryQuoteReference", quoteReference);
        }
        if (paymentMethod != null) {
            body.put("paymentMethod", paymentMethod);
        }

        var response = keyed(buyerToken, "/api/v1/intents/" + intentId + "/orders", body);
        var created = response.at("/data");

        // Loudly, not silently. A missing id used to come back as 0 and every
        // call after it 404'd, which turned one refusal into a mystery spread
        // across the whole suite.
        if (created.at("/supplierOrderId").isMissingNode()
                || created.at("/supplierOrderId").asLong() == 0) {
            throw new IllegalStateException("Order was not created: " + response);
        }

        var payment = created.at("/payment");
        return new Created(intentId, itemId,
                created.at("/supplierOrderId").asLong(),
                payment.isMissingNode() || payment.isNull()
                        ? null : payment.at("/paymentId").asLong(),
                payment.isMissingNode() || payment.isNull()
                        ? null : payment.at("/providerOrderId").asText());
    }

    /** A POST that needs an idempotency key, which {@link ApiClient} does not send. */
    private JsonNode keyed(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }
}
