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
    @Autowired private com.costonomy.mp.fooddelivery.service.FoodDeliveryJobs jobs;
    @Autowired private com.costonomy.mp.payment.service.GatewayRefundService refundService;
    @Autowired private com.costonomy.mp.delivery.service.DeliveryRecoveryService recovery;
    @Autowired private com.costonomy.mp.fooddelivery.service.FoodCourierPayableSync payableSync;
    @Autowired private com.costonomy.mp.delivery.service.CourierPayableService payables;
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

    /** A GATEWAY delivery paid and booked, ready for the money paths: its id, payment id and what was captured. */
    private record Paid(long id, String paymentId, BigDecimal captured) {
    }

    private Paid paidAndBooked() throws Exception {
        var res = createDelivery(UUID.randomUUID().toString(), deliveryPayload("GATEWAY"));
        long deliveryId = res.at("/data/id").asLong();
        var payment = paymentProvider.completeCheckout(res.at("/data/providerOrderId").asText(), "upi");
        api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/confirm", Map.of(
                "paymentId", payment.providerPaymentId(), "signature", MockPaymentProvider.TEST_SIGNATURE));
        return new Paid(deliveryId, payment.providerPaymentId(),
                jdbc.queryForObject("select max_fee from food_delivery where id = ?", BigDecimal.class, deliveryId));
    }

    private java.util.List<java.util.Map<String, Object>> gatewayRefunds(long deliveryId) {
        return jdbc.queryForList("select purpose, status, amount, idempotency_key from gateway_refund "
                + "where source_type = 'FOOD_DELIVERY' and source_id = ? order by id", deliveryId);
    }

    @Test
    @DisplayName("cancelling a paid GATEWAY delivery refunds the whole fee to the gateway, not the wallet, once")
    void cancelPaidGatewayDeliveryRefundsTheGateway() throws Exception {
        var paid = paidAndBooked();
        assertThat(paid.captured()).isGreaterThan(BigDecimal.ZERO);

        String cancelUrl = "/api/v1/outlets/" + outletId + "/food-deliveries/" + paid.id() + "/cancel";
        var cancelRes = api.post(token, cancelUrl, Map.of("reason", "Food burned"));
        api.post(token, cancelUrl, Map.of("reason", "Food burned"));      // a repeat

        assertThat(cancelRes.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(outletId)).as("the wallet is not touched by a gateway payment").isZero();
        assertThat(paymentProvider.refundedOf(paid.paymentId())).isEqualByComparingTo(paid.captured());
        var refunds = gatewayRefunds(paid.id());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0)).containsEntry("purpose", "CANCELLED").containsEntry("status", "SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference like ?", Integer.class,
                "food-delivery-" + paid.id() + "-%")).isZero();
        assertThat(jdbc.queryForObject("select fee_refunded_amount from food_delivery where id = ?", BigDecimal.class,
                paid.id())).isEqualByComparingTo(paid.captured());
    }

    @Test
    @DisplayName("settling the fee after pickup refunds only the unused part, to the gateway, once")
    void settlingRefundsThePartialUnusedFeeToTheGateway() throws Exception {
        var paid = paidAndBooked();
        jdbc.update("update food_delivery set status = 'PICKED_UP', fee = 49.00 where id = ?", paid.id());

        BigDecimal returned = service.settleFee(paid.id());
        BigDecimal again = service.settleFee(paid.id());

        BigDecimal unused = paid.captured().subtract(new BigDecimal("49.00"));
        assertThat(returned).isEqualByComparingTo(unused);
        assertThat(again).isZero();
        assertThat(paymentProvider.refundedOf(paid.paymentId())).as("a partial refund").isEqualByComparingTo(unused);
        assertThat(gatewayRefunds(paid.id())).hasSize(1);
        assertThat(gatewayRefunds(paid.id()).get(0)).containsEntry("purpose", "FEE_ADJUSTMENT");
        assertThat(wallet.balanceOf(outletId)).isZero();
    }

    @Test
    @DisplayName("when no courier is found the fee goes back to the gateway and the delivery ends")
    void noCourierRefundsTheGateway() throws Exception {
        saver.arm(com.costonomy.mp.delivery.provider.MockDeliveryProvider.Failure.BOOKING_FAILS);
        express.arm(com.costonomy.mp.delivery.provider.MockDeliveryProvider.Failure.BOOKING_FAILS);
        var paid = paidAndBooked();
        assertThat(jdbc.queryForObject("select status from food_delivery where id = ?", String.class, paid.id()))
                .isEqualTo("PROVIDER_UNAVAILABLE");

        jdbc.update("update food_delivery set requested_at = utc_timestamp(6) - interval 10 minute, "
                + "round_started_at = utc_timestamp(6) - interval 10 minute, paid_at = utc_timestamp(6) - interval 10 minute where id = ?", paid.id());
        jobs.runRetry();
        jobs.runRetry();

        assertThat(jdbc.queryForObject("select status from food_delivery where id = ?", String.class, paid.id()))
                .isEqualTo("FAILED");
        assertThat(paymentProvider.refundedOf(paid.paymentId())).isEqualByComparingTo(paid.captured());
        assertThat(gatewayRefunds(paid.id())).hasSize(1);
        assertThat(gatewayRefunds(paid.id()).get(0)).containsEntry("purpose", "NO_COURIER");
        assertThat(wallet.balanceOf(outletId)).isZero();
    }

    @Test
    @DisplayName("a refund the gateway did not answer is recorded, looked up, and not paid twice")
    void anUnansweredRefundIsReconciled() throws Exception {
        var paid = paidAndBooked();
        paymentProvider.loseNextRefundAnswer(com.costonomy.mp.payment.provider.PaymentProviderException
                .unreachable("timeout", new RuntimeException("x")));

        api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + paid.id() + "/cancel", Map.of("reason", "x"));
        assertThat(gatewayRefunds(paid.id()).get(0)).as("recorded before the gateway was asked")
                .containsEntry("status", "AMBIGUOUS");

        refundService.sweep();

        assertThat(gatewayRefunds(paid.id()).get(0)).containsEntry("status", "SUCCEEDED");
        assertThat(paymentProvider.refundedOf(paid.paymentId())).isEqualByComparingTo(paid.captured());
        assertThat(paymentProvider.listRefunds(paid.paymentId())).hasSize(1);
    }

    private com.fasterxml.jackson.databind.JsonNode financials(long deliveryId) throws Exception {
        return api.get(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + deliveryId + "/financials").path("data");
    }

    @Test
    @DisplayName("the restaurant sees the fee held while the delivery runs, then refunded to its card, with the refund's progress")
    void theRestaurantSeesWhatItPaidAndWhatCameBack() throws Exception {
        var paid = paidAndBooked();

        var held = financials(paid.id());
        assertThat(held.path("chargeState").asText()).isEqualTo("HELD");
        assertThat(held.path("keptByCostonomy").decimalValue()).isEqualByComparingTo(paid.captured());
        assertThat(held.path("refundState").asText()).isEqualTo("NONE");
        assertThat(held.has("quotedCost")).as("the courier's cost is never shown").isFalse();

        paymentProvider.loseNextRefundAnswer(com.costonomy.mp.payment.provider.PaymentProviderException
                .unreachable("timeout", new RuntimeException("x")));
        api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + paid.id() + "/cancel", Map.of("reason", "x"));

        var pending = financials(paid.id());
        assertThat(pending.path("refundState").asText()).as("recorded, not yet confirmed").isEqualTo("IN_PROGRESS");
        assertThat(pending.path("refundInProgress").decimalValue()).isEqualByComparingTo(paid.captured());
        assertThat(pending.path("refundedToWallet").decimalValue()).isEqualByComparingTo("0");

        refundService.sweep();

        var done = financials(paid.id());
        assertThat(done.path("chargeState").asText()).isEqualTo("RELEASED");
        assertThat(done.path("refundState").asText()).isEqualTo("COMPLETE");
        assertThat(done.path("refundedToOriginalPayment").decimalValue()).isEqualByComparingTo(paid.captured());
        assertThat(done.path("refundedToWallet").decimalValue()).isEqualByComparingTo("0");
        assertThat(done.path("refundInProgress").decimalValue()).isEqualByComparingTo("0");
        assertThat(done.path("keptByCostonomy").decimalValue()).isEqualByComparingTo("0");
        assertThat(api.getStatus(api.loginFresh(), "/api/v1/outlets/" + outletId + "/food-deliveries/" + paid.id()
                + "/financials")).as("another restaurant cannot read it").isIn(403, 404);
    }

    private com.costonomy.mp.delivery.domain.CourierPayable foodPayable(long deliveryId) {
        payableSync.sync(deliveryId);
        var all = payables.forDelivery("FOOD_DELIVERY", deliveryId);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    @DisplayName("a food delivery's courier is accrued at booking, owed at pickup, and the restaurant's fee is the whole of it")
    void foodCourierIsOwedAtPickup() throws Exception {
        var paid = paidAndBooked();

        var booked = foodPayable(paid.id());
        assertThat(booked.getState()).isEqualTo("ACCRUED");
        assertThat(booked.getAmountDue()).isEqualByComparingTo("0");

        jdbc.update("update food_delivery set status = 'PICKED_UP', picked_up_at = utc_timestamp(6) where id = ?", paid.id());
        var due = foodPayable(paid.id());
        assertThat(due.getState()).isEqualTo("DUE");
        BigDecimal fee = jdbc.queryForObject("select fee from food_delivery where id = ?", BigDecimal.class, paid.id());
        assertThat(due.getAmountDue()).isEqualByComparingTo(fee);
        assertThat(due.getBuyerContribution()).isEqualByComparingTo(fee);
        assertThat(due.getSupplierContribution()).isEqualByComparingTo("0");
        assertThat(due.getPlatformFunded()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a delivery that fails after the courier had the food still owes the courier, and the restaurant keeps paying the fee")
    void failureAfterPickupStillOwesTheCourier() throws Exception {
        var paid = paidAndBooked();
        jdbc.update("update food_delivery set status = 'PICKED_UP', picked_up_at = utc_timestamp(6) where id = ?", paid.id());
        service.settleFee(paid.id());
        jdbc.update("update food_delivery set status = 'FAILED', failure_code = 'CUSTOMER_UNREACHABLE' where id = ?", paid.id());

        var due = foodPayable(paid.id());
        assertThat(due.getState()).as("the courier did the work").isEqualTo("DUE");
        assertThat(due.getAmountDue()).isGreaterThan(BigDecimal.ZERO);

        jdbc.update("update food_delivery set status = 'RETURNED' where id = ?", paid.id());
        var returned = foodPayable(paid.id());
        assertThat(returned.getId()).isEqualTo(due.getId());
        assertThat(returned.getState()).isEqualTo("DUE");
        assertThat(financials(paid.id()).path("chargeState").asText()).isEqualTo("CHARGED");
        assertThat(financials(paid.id()).path("refundState").asText()).as("no refund is owed").isIn("NONE", "COMPLETE");
        assertThat(gatewayRefunds(paid.id()).stream().filter(r -> "CANCELLED".equals(r.get("purpose"))).count()).isZero();
    }

    @Test
    @DisplayName("a delivery cancelled before pickup owes the courier nothing, and one that no courier took has no payable at all")
    void cancelledBeforePickupOwesNothing() throws Exception {
        var paid = paidAndBooked();
        api.post(token, "/api/v1/outlets/" + outletId + "/food-deliveries/" + paid.id() + "/cancel", Map.of("reason", "x"));

        var p = foodPayable(paid.id());
        assertThat(p.getState()).isEqualTo("VOID");
        assertThat(p.getAmountDue()).isEqualByComparingTo("0");

        saver.arm(com.costonomy.mp.delivery.provider.MockDeliveryProvider.Failure.BOOKING_FAILS);
        express.arm(com.costonomy.mp.delivery.provider.MockDeliveryProvider.Failure.BOOKING_FAILS);
        var nobody = paidAndBooked();
        payableSync.sync(nobody.id());
        assertThat(payables.forDelivery("FOOD_DELIVERY", nobody.id())).as("nothing was ever booked").isEmpty();
    }

    @Test
    @DisplayName("a wallet-paid delivery is still refunded to the wallet, and no gateway refund is made")
    void walletPaidStillRefundsTheWallet() {
        wallet.topUp(outletId, new BigDecimal("500.00"), "test top-up");
        BigDecimal before = wallet.balanceOf(outletId);
        var d = service.create(new com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService.CreateRequest(
                restaurantId, outletId, UUID.randomUUID().toString(), "POS-9", "Rohit", "+919876543210",
                "Flat 102, Himayatnagar", "Near signal", new BigDecimal("17.4042"), new BigDecimal("78.4870"),
                "Hyderabad", "Telangana", "500029", null, new BigDecimal("500"), new BigDecimal("2.0"), 45, null));
        assertThat(wallet.balanceOf(outletId)).isLessThan(before);

        service.cancel(d.getId(), "changed mind");

        assertThat(wallet.balanceOf(outletId)).as("every rupee back in the wallet").isEqualByComparingTo(before);
        assertThat(gatewayRefunds(d.getId())).isEmpty();
    }

    @Test
    @DisplayName("an old wallet-refund recovery task for a gateway-paid delivery becomes a gateway refund and never credits the wallet")
    void recoveryTaskForAGatewayPaymentDoesNotCreditTheWallet() throws Exception {
        var paid = paidAndBooked();
        jdbc.update("update food_delivery set status = 'CANCELLED', fee = 0 where id = ?", paid.id());
        var task = recovery.scheduleRefund(paid.id(), outletId, "refund", paid.captured(), "Food delivery cancelled");

        recovery.processDueTasks();

        assertThat(wallet.balanceOf(outletId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?", Integer.class,
                task.getIdempotencyRef())).isZero();
        assertThat(paymentProvider.refundedOf(paid.paymentId())).isEqualByComparingTo(paid.captured());
        assertThat(gatewayRefunds(paid.id())).hasSize(1);
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
