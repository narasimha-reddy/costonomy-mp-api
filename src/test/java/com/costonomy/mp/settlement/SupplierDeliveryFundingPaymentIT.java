package com.costonomy.mp.settlement;

import com.costonomy.mp.support.LocalCommon;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.settlement.domain.SupplierDeduction;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.settlement.service.SupplierDeliveryFundingPaymentService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestCheckout;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class SupplierDeliveryFundingPaymentIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private SettlementService settlementService;
    @Autowired private com.costonomy.mp.settlement.service.SupplierDeliveryFundingPaymentService fundingPayments;
    @Autowired private SupplierDeliveryFundingPaymentService fundingPaymentService;

    private ApiClient api;
    private TestOrder orders;
    private TestCheckout checkout;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(paymentProvider, api);
        orders = new TestOrder(mvc, json, api);
    }

    private record Buyer(String token, long outletId) {}
    private record Seller(String token, long supplierId, long storeId) {}
    private record OrderSetup(Buyer buyer, Seller seller, long orderId, long deliveryId) {}

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    private Seller newSeller() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Food Express " + UUID.randomUUID().toString().substring(0, 8),
                "displayName", "Food Express",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Express Store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long supplierId = created.get("id").asLong();
        LocalCommon.activate(supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);
        long storeId = created.get("stores").get(0).get("id").asLong();
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);
        return new Seller(token, supplierId, storeId);
    }

    private OrderSetup createOrderWithDelivery(String fundedAmount) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "100.00", "gstRate", "0")).at("/data/id").asLong();

        var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                skuId, 10, 10, "SUPPLIER_DELIVERY", null, null);
        checkout.pay(buyer.token(), placed.paymentId(), placed.providerOrderId());
        long orderId = placed.orderId();

        jdbc.update("update supplier_order set status = 'COMPLETED', "
                + "delivery_funded_amount = ?, version = version + 1 where id = ?",
                new BigDecimal(fundedAmount), orderId);

        jdbc.update("""
                insert into delivery (supplier_order_id, outlet_id, supplier_store_id,
                                      mode, status, fee, currency, provider_code, provider_delivery_id,
                                      pickup_address, drop_address, requested_at, created_at, updated_at, version)
                values (?, ?, ?, 'COSTONOMY', 'DELIVERED', 50.00, 'INR', 'SHADOWFAX', ?,
                        'Pickup Address', 'Drop Address', now(6), now(6), now(6), 0)
                """, orderId, buyer.outletId(), seller.storeId(), "sf-" + orderId);
        long deliveryId = jdbc.queryForObject(
                "select id from delivery where supplier_order_id = ?", Long.class, orderId);

        return new OrderSetup(buyer, seller, orderId, deliveryId);
    }

    private long createFundedDeduction(long storeId, long orderId, long deliveryId, String amount) {
        jdbc.update("""
                insert into supplier_deduction (supplier_store_id, supplier_order_id, dispute_refund_id, amount,
                                                reason, status, kind, delivery_id, created_at, updated_at, version)
                values (?, ?, null, ?, 'Costonomy delivery fee', 'PENDING', 'DELIVERY_FUNDING', ?, now(6), now(6), 0)
                """, storeId, orderId, new BigDecimal(amount), deliveryId);
        return jdbc.queryForObject(
                "select id from supplier_deduction where supplier_order_id = ? and kind = 'DELIVERY_FUNDING'",
                Long.class, orderId);
    }

    @Test
    @DisplayName("supplier can list pending delivery funding deductions")
    void supplierCanListPendingDeductions() throws Exception {
        var setup = createOrderWithDelivery("65.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "65.00");

        String url = "/api/v1/supplier-stores/" + setup.seller().storeId() + "/delivery-funding/pending";
        var res = api.get(setup.seller().token(), url);

        assertThat(res.get("error").isNull()).isTrue();
        var data = res.get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isGreaterThanOrEqualTo(1);

        JsonNode row = null;
        for (JsonNode item : data) {
            if (item.get("deductionId").asLong() == deductionId) {
                row = item;
                break;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("amount").asDouble()).isEqualTo(65.00);
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("deliveryId").asLong()).isEqualTo(setup.deliveryId());
    }

    @Test
    @DisplayName("supplier can initiate checkout and confirm direct payment via payment provider")
    void fullDirectPaymentFlow() throws Exception {
        var setup = createOrderWithDelivery("80.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "80.00");

        // 1. Initiate checkout
        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        var initRes = api.post(setup.seller().token(), payUrl, Map.of("clientKey", "key-123"));
        assertThat(initRes.get("error").isNull()).isTrue();

        String providerOrderId = initRes.at("/data/razorpayOrderId").asText();
        assertThat(providerOrderId).isNotBlank();
        assertThat(initRes.at("/data/amount").asDouble()).isEqualTo(80.00);
        assertThat(initRes.at("/data/currency").asText()).isEqualTo("INR");

        // Verify provider_order_id is saved on deduction
        String savedOrderId = jdbc.queryForObject(
                "select provider_order_id from supplier_deduction where id = ?", String.class, deductionId);
        assertThat(savedOrderId).isEqualTo(providerOrderId);

        // Replay initiate: returns the same order id idempotently
        var replayRes = api.post(setup.seller().token(), payUrl, Map.of("clientKey", "key-123"));
        assertThat(replayRes.at("/data/razorpayOrderId").asText()).isEqualTo(providerOrderId);

        // 2. Complete payment at provider (UPI/card)
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String paymentId = payment.providerPaymentId();

        // 3. Confirm payment
        String confirmUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm";
        var confirmRes = api.post(setup.seller().token(), confirmUrl, Map.of(
                "paymentId", paymentId,
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));

        assertThat(confirmRes.get("error").isNull()).isTrue();
        assertThat(confirmRes.at("/data/status").asText()).isEqualTo("PAID_DIRECTLY");
        assertThat(confirmRes.at("/data/paymentId").asText()).isEqualTo(paymentId);
        assertThat(confirmRes.at("/data/amount").asDouble()).isEqualTo(80.00);

        // Verify database state: status is PAID_DIRECTLY, payment_id and paid_at are populated
        var deductionRow = jdbc.queryForMap(
                "select status, payment_id, paid_at from supplier_deduction where id = ?", deductionId);
        assertThat(deductionRow.get("status")).isEqualTo(SupplierDeduction.PAID_DIRECTLY);
        assertThat(deductionRow.get("payment_id")).isEqualTo(paymentId);
        assertThat(deductionRow.get("paid_at")).isNotNull();

        // Verify delivery_ledger entry was inserted
        var ledgerEntries = jdbc.queryForList("""
                select entry_type, amount, description from delivery_ledger
                 where delivery_id = ? and entry_type = 'SUPPLIER_PAID' and provider_code = 'SUPPLIER'
                """, setup.deliveryId());
        assertThat(ledgerEntries).hasSize(1);
        assertThat(((BigDecimal) ledgerEntries.get(0).get("amount")).doubleValue()).isEqualTo(80.00);
        assertThat((String) ledgerEntries.get(0).get("description")).contains(paymentId);

        // Idempotent retry of confirm returns success
        var replayConfirm = api.post(setup.seller().token(), confirmUrl, Map.of(
                "paymentId", paymentId,
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));
        assertThat(replayConfirm.get("error").isNull()).isTrue();
        assertThat(replayConfirm.at("/data/status").asText()).isEqualTo("PAID_DIRECTLY");
    }

    @Test
    @DisplayName("a directly paid delivery fee is not deducted again in periodic settlement")
    void settledDeductionIsNotDeductedFromPayout() throws Exception {
        var setup = createOrderWithDelivery("95.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "95.00");

        // Settle directly via payment provider
        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        var initRes = api.post(setup.seller().token(), payUrl, Map.of());
        String providerOrderId = initRes.at("/data/razorpayOrderId").asText();
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");

        String confirmUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm";
        api.post(setup.seller().token(), confirmUrl, Map.of(
                "paymentId", payment.providerPaymentId(),
                "signature", MockPaymentProvider.TEST_SIGNATURE
        ));

        // Generate periodic settlement
        settlementService.generate(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)));

        // Deduction must remain PAID_DIRECTLY (never moved to APPLIED)
        String deductionStatus = jdbc.queryForObject(
                "select status from supplier_deduction where id = ?", String.class, deductionId);
        assertThat(deductionStatus).isEqualTo("PAID_DIRECTLY");

        // No adjustment exists for DELIVERY_FUNDING on this order
        int adjustmentCount = jdbc.queryForObject("""
                select count(*) from settlement_adjustment
                 where supplier_order_id = ? and reason_code = 'DELIVERY_FUNDING'
                """, Integer.class, setup.orderId());
        assertThat(adjustmentCount).isZero();
    }

    @Test
    @DisplayName("confirm is rejected if signature does not verify")
    void invalidSignatureRejected() throws Exception {
        var setup = createOrderWithDelivery("50.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "50.00");

        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        var initRes = api.post(setup.seller().token(), payUrl, Map.of());
        String providerOrderId = initRes.at("/data/razorpayOrderId").asText();
        var payment = paymentProvider.completeCheckout(providerOrderId, "card");

        String confirmUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm";
        int status = api.postStatus(setup.seller().token(), confirmUrl, Map.of(
                "paymentId", payment.providerPaymentId(),
                "signature", "bad-signature"
        ));

        assertThat(status).isEqualTo(400);

        String deductionStatus = jdbc.queryForObject(
                "select status from supplier_deduction where id = ?", String.class, deductionId);
        assertThat(deductionStatus).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("another supplier cannot initiate or confirm payment for this store's delivery fee")
    void crossStoreAccessRejected() throws Exception {
        var setup = createOrderWithDelivery("50.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "50.00");

        var otherSeller = newSeller();

        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        int status = api.postStatus(otherSeller.token(), payUrl, Map.of());
        assertThat(status).isIn(403, 404);
    }

    @Test
    @DisplayName("webhook reconciles supplier delivery funding when client closes browser before confirm")
    void webhookReconcilesSupplierDeliveryFunding() throws Exception {
        var setup = createOrderWithDelivery("85.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "85.00");

        // 1. Open checkout order
        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        var initRes = api.post(setup.seller().token(), payUrl, Map.of());
        String providerOrderId = initRes.at("/data/razorpayOrderId").asText();

        // 2. Customer pays on UPI/card at Razorpay
        var payment = paymentProvider.completeCheckout(providerOrderId, "upi");
        String paymentId = payment.providerPaymentId();

        // 3. Webhook arrives from Razorpay
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
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", MockPaymentProvider.TEST_SIGNATURE)
                        .header("X-Razorpay-Event-Id", "evt_" + UUID.randomUUID())
                        .content(webhookBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // 4. Deduction was automatically marked PAID_DIRECTLY and settled in ledger
        var row = jdbc.queryForMap(
                "select status, payment_id, paid_at from supplier_deduction where id = ?", deductionId);
        assertThat(row.get("status")).isEqualTo(SupplierDeduction.PAID_DIRECTLY);
        assertThat(row.get("payment_id")).isEqualTo(paymentId);
        assertThat(row.get("paid_at")).isNotNull();

        var ledger = jdbc.queryForList("select entry_type from delivery_ledger where delivery_id = ? and entry_type = 'SUPPLIER_PAID'", setup.deliveryId());
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("background sweep job reconciles stale supplier delivery funding with captured payment")
    void sweepReconcilesStaleDeliveryFunding() throws Exception {
        var setup = createOrderWithDelivery("120.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "120.00");

        // 1. Open checkout order
        String payUrl = "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/pay";
        var initRes = api.post(setup.seller().token(), payUrl, Map.of());
        String providerOrderId = initRes.at("/data/razorpayOrderId").asText();

        // 2. Customer pays on Razorpay, but confirm and webhook are lost
        var payment = paymentProvider.completeCheckout(providerOrderId, "card");
        String paymentId = payment.providerPaymentId();

        // 3. Age the deduction row past the stale threshold
        jdbc.update("update supplier_deduction set checkout_opened_at = date_sub(now(6), interval 2 minute) where id = ?", deductionId);

        // 4. Run background sweep
        int recovered = fundingPaymentService.sweepStaleGatewayPayments();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // 5. Deduction was recovered!
        var row = jdbc.queryForMap(
                "select status, payment_id, paid_at from supplier_deduction where id = ?", deductionId);
        assertThat(row.get("status")).isEqualTo(SupplierDeduction.PAID_DIRECTLY);
        assertThat(row.get("payment_id")).isEqualTo(paymentId);
        assertThat(row.get("paid_at")).isNotNull();
    }

    // ── A payment that cannot be used is refunded, never stranded ────────────────

    private String payAtGateway(OrderSetup setup, long deductionId) throws Exception {
        String base = "/api/v1/supplier-stores/" + setup.seller().storeId() + "/delivery-funding/" + deductionId;
        var init = api.post(setup.seller().token(), base + "/pay", Map.of());
        return paymentProvider.completeCheckout(init.at("/data/razorpayOrderId").asText(), "upi").providerPaymentId();
    }

    private void settle() {
        settlementService.generate(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("an open checkout keeps its charge out of the settlement, so paying it is not also deducted")
    void openCheckoutKeepsTheChargeOutOfTheSettlement() throws Exception {
        var setup = createOrderWithDelivery("95.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "95.00");
        String paymentId = payAtGateway(setup, deductionId);   // checkout opened a moment ago

        settle();

        assertThat(jdbc.queryForObject("select status from supplier_deduction where id = ?", String.class, deductionId))
                .as("held back while the supplier is paying").isEqualTo("PENDING");
        var confirm = api.post(setup.seller().token(), "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm",
                Map.of("paymentId", paymentId, "signature", MockPaymentProvider.TEST_SIGNATURE));
        assertThat(confirm.at("/data/status").asText()).isEqualTo("PAID_DIRECTLY");
        assertThat(paymentProvider.refundedOf(paymentId)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from settlement_adjustment where supplier_order_id = ? "
                + "and reason_code = 'DELIVERY_FUNDING'", Integer.class, setup.orderId())).isZero();
    }

    @Test
    @DisplayName("a payment confirmed after the settlement already took the charge is refunded to its source")
    void paymentAfterSettlementIsRefundedOnConfirm() throws Exception {
        var setup = createOrderWithDelivery("95.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "95.00");
        String paymentId = payAtGateway(setup, deductionId);
        // The checkout was abandoned long ago and the supplier only now finishes it.
        jdbc.update("update supplier_deduction set checkout_opened_at = date_sub(now(6), interval 2 hour) where id = ?", deductionId);
        settle();
        assertThat(jdbc.queryForObject("select status from supplier_deduction where id = ?", String.class, deductionId))
                .isEqualTo("APPLIED");

        var confirm = api.post(setup.seller().token(), "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm",
                Map.of("paymentId", paymentId, "signature", MockPaymentProvider.TEST_SIGNATURE));

        assertThat(confirm.at("/error/code").asText()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(confirm.at("/error/message").asText()).contains("refunded");
        assertThat(paymentProvider.refundedOf(paymentId)).as("the gateway payment went back").isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("select gateway_refunded_at is not null from supplier_deduction where id = ?",
                Boolean.class, deductionId)).isTrue();
        assertThat(jdbc.queryForObject("select status from supplier_deduction where id = ?", String.class, deductionId))
                .as("still taken from the payout once").isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("a payment nobody confirmed, on a charge the settlement already took, is refunded by the sweep and only once")
    void paymentAfterSettlementIsRefundedBySweep() throws Exception {
        var setup = createOrderWithDelivery("95.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "95.00");
        String paymentId = payAtGateway(setup, deductionId);
        jdbc.update("update supplier_deduction set checkout_opened_at = date_sub(now(6), interval 2 hour) where id = ?", deductionId);
        settle();
        // Opened two hours ago, so the sweep (which looks at the last day) sees it.

        fundingPayments.sweepStaleGatewayPayments();
        fundingPayments.sweepStaleGatewayPayments();

        assertThat(paymentProvider.refundedOf(paymentId)).isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("select count(*) from settlement_adjustment where supplier_order_id = ? "
                + "and reason_code = 'DELIVERY_FUNDING'", Integer.class, setup.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the sweep finds a checkout opened long after the charge was created")
    void sweepKeysOnWhenTheCheckoutWasOpened() throws Exception {
        var setup = createOrderWithDelivery("120.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "120.00");
        // The charge is days old (a credit order waits for a settlement); the supplier only now opens checkout.
        jdbc.update("update supplier_deduction set created_at = date_sub(now(6), interval 3 day) where id = ?", deductionId);
        String paymentId = payAtGateway(setup, deductionId);
        jdbc.update("update supplier_deduction set checkout_opened_at = date_sub(now(6), interval 2 minute) where id = ?", deductionId);

        fundingPayments.sweepStaleGatewayPayments();

        assertThat(jdbc.queryForObject("select status from supplier_deduction where id = ?", String.class, deductionId))
                .isEqualTo("PAID_DIRECTLY");
        assertThat(jdbc.queryForObject("select payment_id from supplier_deduction where id = ?", String.class, deductionId))
                .isEqualTo(paymentId);
    }

    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("a directly paid share is partly refunded at the gateway when the courier cost less, once however often it is told")
    void directPaymentIsPartlyRefundedWhenTheCourierCostLess() throws Exception {
        var setup = createOrderWithDelivery("95.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "95.00");
        String paymentId = payAtGateway(setup, deductionId);
        api.post(setup.seller().token(), "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm",
                Map.of("paymentId", paymentId, "signature", MockPaymentProvider.TEST_SIGNATURE));

        var lower = new com.costonomy.mp.delivery.domain.SupplierShareReduced(setup.deliveryId(), setup.orderId(),
                new BigDecimal("60.00"));
        eventPublisher.publishEvent(lower);
        eventPublisher.publishEvent(lower);

        assertThat(paymentProvider.refundedOf(paymentId)).as("the 35 that was not needed").isEqualByComparingTo("35.00");
        var refunds = jdbc.queryForList("select purpose, status, amount from gateway_refund where source_type = "
                + "'SUPPLIER_DELIVERY_FUNDING' and source_id = ?", deductionId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0)).containsEntry("purpose", "FEE_ADJUSTMENT").containsEntry("status", "SUCCEEDED");

        // A later, lower figure refunds only the further difference.
        eventPublisher.publishEvent(new com.costonomy.mp.delivery.domain.SupplierShareReduced(
                setup.deliveryId(), setup.orderId(), new BigDecimal("50.00")));
        assertThat(paymentProvider.refundedOf(paymentId)).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("the supplier's payment is not entered against the courier in the delivery ledger")
    void supplierPaymentIsNotACourierCost() throws Exception {
        var setup = createOrderWithDelivery("80.00");
        long deductionId = createFundedDeduction(setup.seller().storeId(), setup.orderId(), setup.deliveryId(), "80.00");
        String paymentId = payAtGateway(setup, deductionId);
        api.post(setup.seller().token(), "/api/v1/supplier-stores/" + setup.seller().storeId()
                + "/delivery-funding/" + deductionId + "/confirm",
                Map.of("paymentId", paymentId, "signature", MockPaymentProvider.TEST_SIGNATURE));

        assertThat(jdbc.queryForObject("select count(*) from delivery_ledger where delivery_id = ? "
                + "and provider_code <> 'SUPPLIER' and entry_type not in ('QUOTED','BOOKED','ADJUSTED','REFUNDED')",
                Integer.class, setup.deliveryId())).as("nothing the courier totals would pick up").isZero();
    }
}
