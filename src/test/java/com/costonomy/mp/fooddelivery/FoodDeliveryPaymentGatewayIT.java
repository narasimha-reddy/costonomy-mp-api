package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class FoodDeliveryPaymentGatewayIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FoodDeliveryBookingService service;
    @Autowired private FoodDeliveryRepository deliveries;
    @Autowired private WalletService wallet;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private com.costonomy.mp.delivery.service.ReliabilityProperties reliability;

    @Autowired @Qualifier("mockExpressDeliveryProvider") private MockDeliveryProvider express;
    @Autowired @Qualifier("mockSaverDeliveryProvider") private MockDeliveryProvider saver;

    private ApiClient api;
    private String token;
    private long restaurantId;
    private long outletId;

    @BeforeEach
    void setUp() throws Exception {
        reliability.setEnabled(false);
        express.disarm();
        saver.disarm();
        api = new ApiClient(mvc, json);

        token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Bawarchi",
                "firstOutlet", Map.of("name", "RTC X Roads", "addressLine1", "Musheerabad", "city", "Hyderabad",
                        "state", "Telangana", "pincode", "500020", "contactName", "Naveen", "contactPhone", "+919876522222",
                        "latitude", "17.4089", "longitude", "78.4947"))).get("data");
        restaurantId = created.get("id").asLong();
        outletId = created.at("/outlets/0/id").asLong();
        // Give 0 wallet balance to ensure gateway payment does not require wallet balance
    }

    @AfterEach
    void tearDown() {
        reliability.setEnabled(true);
        express.disarm();
        saver.disarm();
    }

    private Map<String, Object> deliveryPayload(String paymentMethod) {
        Map<String, Object> map = new HashMap<>();
        map.put("externalOrderRef", "POS-101");
        map.put("customerName", "Rohit");
        map.put("customerPhone", "+919876543210");
        map.put("dropAddress", "Flat 102, Himayatnagar");
        map.put("dropLandmark", "Near signal");
        map.put("dropLatitude", new BigDecimal("17.4042"));
        map.put("dropLongitude", new BigDecimal("78.4870"));
        map.put("dropCity", "Hyderabad");
        map.put("dropState", "Telangana");
        map.put("dropPincode", "500029");
        map.put("dropInstructions", "Call on arrival");
        map.put("orderValue", new BigDecimal("500"));
        map.put("weightKg", new BigDecimal("2.0"));
        map.put("maxDeliveryMinutes", 45);
        map.put("paymentMethod", paymentMethod);
        return map;
    }

    private JsonNode createDelivery(String key, Map<String, Object> payload) throws Exception {
        String res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/outlets/" + outletId + "/food-deliveries")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    @DisplayName("restaurant can request food delivery with GATEWAY payment without wallet balance")
    void createWithGatewayPayment() throws Exception {
        BigDecimal initialBalance = wallet.balanceOf(outletId);
        assertThat(initialBalance).isZero();

        String key = UUID.randomUUID().toString();
        var res = createDelivery(key, deliveryPayload("GATEWAY"));
        assertThat(res.get("error").isNull()).isTrue();

        var data = res.get("data");
        long deliveryId = data.get("id").asLong();
        assertThat(data.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(data.get("paymentMethod").asText()).isEqualTo("GATEWAY");
        assertThat(data.get("maxFee").asDouble()).isGreaterThan(0.0);

        String providerOrderId = data.get("providerOrderId").asText();
        assertThat(providerOrderId).isNotBlank();

        // Wallet balance remains 0 (not debited!)
        assertThat(wallet.balanceOf(outletId)).isZero();

        // Verify database state
        var row = jdbc.queryForMap(
                "select status, payment_method, provider_order_id, paid_at from food_delivery where id = ?",
                deliveryId);
        assertThat(row.get("status")).isEqualTo("REQUESTED");
        assertThat(row.get("payment_method")).isEqualTo("GATEWAY");
        assertThat(row.get("provider_order_id")).isEqualTo(providerOrderId);
        assertThat(row.get("paid_at")).isNull();
    }

    @Test
    @DisplayName("restaurant can complete checkout and confirm payment, which books courier")
    void fullPaymentGatewayFlow() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();

        // 1. Simulate payment completion at provider
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String paymentId = payment.providerPaymentId();

        // 2. Confirm payment
        String confirmUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/confirm";
        var confirmRes = api.post(token, confirmUrl, Map.of(
                "paymentId", paymentId,
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));

        assertThat(confirmRes.get("error").isNull()).isTrue();
        var data = confirmRes.get("data");
        // Courier booking was triggered!
        assertThat(data.get("status").asText()).isIn("PROVIDER_SELECTED", "DRIVER_ASSIGNED", "PROVIDER_UNAVAILABLE");
        assertThat(data.get("paymentMethod").asText()).isEqualTo("GATEWAY");
        assertThat(data.get("paymentId").asText()).isEqualTo(paymentId);

        // Verify in DB
        var row = jdbc.queryForMap(
                "select status, payment_id, paid_at, fee_debit_ref from food_delivery where id = ?", deliveryId);
        assertThat(row.get("payment_id")).isEqualTo(paymentId);
        assertThat(row.get("paid_at")).isNotNull();
        assertThat((String) row.get("fee_debit_ref")).contains(paymentId);

        // Courier attempt exists
        int attempts = jdbc.queryForObject(
                "select count(*) from food_delivery_attempt where food_delivery_id = ?",
                Integer.class, deliveryId);
        assertThat(attempts).isGreaterThanOrEqualTo(1);

        // Idempotent retry of confirm returns success
        var replayRes = api.post(token, confirmUrl, Map.of(
                "paymentId", paymentId,
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));
        assertThat(replayRes.get("error").isNull()).isTrue();
    }

    @Test
    @DisplayName("restaurant can initiate payment on existing REQUESTED delivery via /pay endpoint")
    void initiatePaymentEndpoint() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();

        String payUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/pay";
        var payRes = api.post(token, payUrl, Map.of());

        assertThat(payRes.get("error").isNull()).isTrue();
        assertThat(payRes.at("/data/razorpayOrderId").asText()).isNotBlank();
        assertThat(payRes.at("/data/amount").asDouble()).isGreaterThan(0.0);
        assertThat(payRes.at("/data/currency").asText()).isEqualTo("INR");
    }

    @Test
    @DisplayName("confirm payment is rejected if signature does not verify")
    void invalidSignatureRejected() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();

        var payment = paymentProvider.completeCheckout(providerOrderId, "card");

        String confirmUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/confirm";
        int status = api.postStatus(token, confirmUrl, Map.of(
                "paymentId", payment.providerPaymentId(),
                "signature", "bad-signature"
        ));
        assertThat(status).isEqualTo(400);

        // Delivery remains in REQUESTED and unpaid
        var row = jdbc.queryForMap("select status, paid_at from food_delivery where id = ?", deliveryId);
        assertThat(row.get("status")).isEqualTo("REQUESTED");
        assertThat(row.get("paid_at")).isNull();
    }

    @Test
    @DisplayName("cancelling unpaid GATEWAY delivery does not refund unearned wallet balance")
    void cancelUnpaidGatewayDeliveryDoesNotRefundWallet() throws Exception {
        assertThat(wallet.balanceOf(outletId)).isZero();

        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();

        // Cancel before payment
        String cancelUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/cancel";
        var cancelRes = api.post(token, cancelUrl, Map.of("reason", "Customer cancelled"));
        assertThat(cancelRes.get("error").isNull()).isTrue();
        assertThat(cancelRes.at("/data/status").asText()).isEqualTo("CANCELLED");

        // Wallet balance MUST still be zero (not credited free money!)
        assertThat(wallet.balanceOf(outletId)).isZero();
    }

    @Test
    @DisplayName("cancelling paid GATEWAY delivery refunds held fee to wallet")
    void cancelPaidGatewayDeliveryRefundsWallet() throws Exception {
        assertThat(wallet.balanceOf(outletId)).isZero();

        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();
        double maxFee = res.at("/data/maxFee").asDouble();

        // 1. Pay via provider
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String confirmUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/confirm";
        api.post(token, confirmUrl, Map.of(
                "paymentId", payment.providerPaymentId(),
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));

        // 2. Cancel while waiting for driver pickup
        String cancelUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/cancel";
        var cancelRes = api.post(token, cancelUrl, Map.of("reason", "Food burned"));
        assertThat(cancelRes.get("error").isNull()).isTrue();
        assertThat(cancelRes.at("/data/status").asText()).isEqualTo("CANCELLED");

        // Wallet was credited with the maxFee that was paid!
        assertThat(wallet.balanceOf(outletId)).isEqualByComparingTo(BigDecimal.valueOf(maxFee));
    }

    @Test
    @DisplayName("webhook reconciles food delivery payment when client drops off before confirm")
    void webhookReconcilesFoodDelivery() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();

        // 1. Customer pays on Razorpay, but phone dies / browser closed
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String paymentId = payment.providerPaymentId();

        // 2. Razorpay delivers payment.captured webhook
        String webhookBody = """
                {
                  "entity": "event",
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "%s",
                        "status": "captured"
                      }
                    }
                  }
                }
                """.formatted(paymentId, providerOrderId);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", MockPaymentProvider.TEST_SIGNATURE)
                        .header("X-Razorpay-Event-Id", "evt_" + UUID.randomUUID())
                        .content(webhookBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // 3. Food delivery was automatically reconciled and booked!
        var row = jdbc.queryForMap(
                "select status, payment_id, paid_at, fee_debit_ref from food_delivery where id = ?", deliveryId);
        assertThat(row.get("payment_id")).isEqualTo(paymentId);
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(row.get("status")).isIn("PROVIDER_SELECTED", "DRIVER_ASSIGNED", "PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("background sweep job reconciles stale food delivery with captured payment")
    void sweepReconcilesStaleGatewayPayment() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();

        // 1. Payment completed at Razorpay, but both client callback AND webhook were lost
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String paymentId = payment.providerPaymentId();

        // 2. Age the delivery row past the stale threshold (60 seconds ago)
        jdbc.update("update food_delivery set created_at = date_sub(now(6), interval 2 minute) where id = ?", deliveryId);

        // 3. Run background sweep
        int recovered = service.sweepStaleGatewayPayments();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // 4. Delivery was recovered!
        var row = jdbc.queryForMap(
                "select status, payment_id, paid_at from food_delivery where id = ?", deliveryId);
        assertThat(row.get("payment_id")).isEqualTo(paymentId);
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(row.get("status")).isIn("PROVIDER_SELECTED", "DRIVER_ASSIGNED", "PROVIDER_UNAVAILABLE");
    }

    // ── A payment that cannot be used is refunded, never stranded ────────────────

    private String cancelThenPay(long deliveryId, String providerOrderId) throws Exception {
        api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/cancel", Map.of("reason", "changed mind"));
        return paymentProvider.completeCheckout(providerOrderId, "upi").providerPaymentId();
    }

    @Test
    @DisplayName("paying after cancelling the unpaid delivery is refunded to its source, and the delivery stays cancelled")
    void paymentAfterCancelIsRefundedOnConfirm() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String paymentId = cancelThenPay(deliveryId, res.at("/data/providerOrderId").asText());

        var confirm = api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/confirm",
                Map.of("paymentId", paymentId, "signature", MockPaymentProvider.TEST_SIGNATURE));

        assertThat(confirm.at("/error/code").asText()).as(confirm.toString()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(confirm.at("/error/message").asText()).contains("refunded");
        assertThat(paymentProvider.refundedOf(paymentId)).isGreaterThan(java.math.BigDecimal.ZERO);
        var row = jdbc.queryForMap("select status, paid_at, gateway_refunded_at from food_delivery where id = ?", deliveryId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("gateway_refunded_at")).isNotNull();
        assertThat(wallet.balanceOf(outletId)).as("back to the card or UPI, not the wallet").isZero();
    }

    @Test
    @DisplayName("a payment that nobody confirmed, on a delivery that ended, is refunded by the sweep and only once")
    void paymentAfterCancelIsRefundedBySweep() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String paymentId = cancelThenPay(deliveryId, res.at("/data/providerOrderId").asText());
        jdbc.update("update food_delivery set created_at = date_sub(now(6), interval 2 minute) where id = ?", deliveryId);

        service.sweepStaleGatewayPayments();
        service.sweepStaleGatewayPayments();

        assertThat(paymentProvider.refundedOf(paymentId)).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(paymentProvider.listRefunds(paymentId)).hasSize(1);
    }

    @Test
    @DisplayName("the webhook refunds a payment for a delivery that ended")
    void webhookRefundsPaymentForAnEndedDelivery() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();
        String paymentId = cancelThenPay(deliveryId, providerOrderId);

        assertThat(service.reconcileWebhookPayment(providerOrderId, paymentId)).isTrue();

        assertThat(paymentProvider.refundedOf(paymentId)).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select status from food_delivery where id = ?", String.class, deliveryId))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a delivery the restaurant never paid for is cancelled after the payment expiry, and a late payment is then refunded")
    void unpaidDeliveryExpiresAndALatePaymentIsRefunded() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        String providerOrderId = res.at("/data/providerOrderId").asText();

        jdbc.update("update food_delivery set created_at = date_sub(now(6), interval 2 hour) where id = ?", deliveryId);
        service.sweepStaleGatewayPayments();

        assertThat(jdbc.queryForObject("select status from food_delivery where id = ?", String.class, deliveryId))
                .as("no longer open for ever").isEqualTo("CANCELLED");

        var latePayment = paymentProvider.completeCheckout(providerOrderId, "upi").providerPaymentId();
        service.sweepStaleGatewayPayments();
        assertThat(paymentProvider.refundedOf(latePayment)).isGreaterThan(java.math.BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a delivery still inside the payment expiry is left open")
    void unpaidDeliveryInsideTheExpiryIsLeftOpen() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        jdbc.update("update food_delivery set created_at = date_sub(now(6), interval 2 minute) where id = ?", deliveryId);

        service.sweepStaleGatewayPayments();

        assertThat(jdbc.queryForObject("select status from food_delivery where id = ?", String.class, deliveryId))
                .isEqualTo("REQUESTED");
    }
}
