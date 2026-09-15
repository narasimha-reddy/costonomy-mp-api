package com.costonomy.mp.payment;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payments end to end, including doc 46's edge cases.
 *
 * <p>The property under test throughout is guardrail 16: <b>a supplier never sees
 * an order that is not paid for.</b> Everything else — capture amounts, releases,
 * refunds, webhook ordering — is downstream of getting that right.
 */
@AutoConfigureMockMvc
class PaymentFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider mockProvider;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentJobs paymentJobs;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    /** A submitted order, its payment intent, and both sides' tokens. */
    private record Submitted(Buyer buyer, Seller seller, long orderId, long paymentId,
                             String providerOrderId) {
    }

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

    private Seller newSeller(String name) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd", "displayName", name,
                "firstStore", Map.of("name", name + " store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /**
     * Place an order for a total the mock provider will treat in a given way.
     *
     * <p>{@code unitPrice} drives the mock's scenario — see MockPaymentProvider for
     * which paise mean what. Choosing the price is how a test asks for a declined
     * card, which keeps the failure path identical to the success path.
     */
    private Submitted submit(String unitPrice, int quantity) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("ABC Foods");
        long productId = TestCatalog.freshProduct(jdbc, "paneer");

        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
        long offerId = jdbc.queryForObject(
                "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                Long.class, skuId);

        long procurementId = api.post(buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                Map.of("supplierOfferId", offerId, "quantity", quantity)).at("/data/id").asLong();

        api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                Map.of("acceptPriceChanges", false));

        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/procurements/" + procurementId + "/submit")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json.readTree(body).at("/data");
        return new Submitted(buyer, seller,
                response.at("/supplierOrders/0/id").asLong(),
                response.at("/paymentIntents/0/paymentId").asLong(),
                response.at("/paymentIntents/0/providerOrderId").asText());
    }

    /** Simulate the customer finishing checkout, then tell the API about it. */
    private JsonNode payAndConfirm(Submitted submitted) throws Exception {
        var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());
        return api.post(submitted.buyer().token(),
                "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                Map.of("providerPaymentId", providerPayment.providerPaymentId())).at("/data");
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }

    private BigDecimal decimal(long paymentId, String column) {
        return jdbc.queryForObject(
                "select " + column + " from payment where id = ?", BigDecimal.class, paymentId);
    }

    // ── The guardrail ────────────────────────────────────────────────────

    @Nested
    @DisplayName("mock checkout simulation")
    class CheckoutSimulation {

        @Test
        @DisplayName("stands in for the hosted checkout a mock provider does not have")
        void completesCheckout() throws Exception {
            var submitted = submit("400", 10);

            String providerPaymentId = api.post(submitted.buyer().token(),
                            "/api/v1/internal/payments/" + submitted.paymentId() + "/simulate-checkout",
                            Map.of())
                    .at("/data/providerPaymentId").asText();

            assertThat(providerPaymentId).isNotBlank();

            // It stops at authorisation on purpose: the caller still goes through
            // the real confirm, so the path production takes stays exercised.
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            api.post(submitted.buyer().token(),
                    "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", providerPaymentId));

            assertThat(orderStatus(submitted.orderId())).isEqualTo("PENDING_ACCEPTANCE");
        }

        @Test
        @DisplayName("is refused to someone who cannot pay for that order")
        void refusesAnotherTenant() throws Exception {
            var submitted = submit("400", 10);
            // A restaurant with no grant on this payment's outlet. Denial of a
            // tenant-owned resource is a 404, not a 403 (doc 09 §3).
            var stranger = newBuyer();

            mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/internal/payments/" + submitted.paymentId()
                                    + "/simulate-checkout")
                            .header("Authorization", "Bearer " + stranger.token())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("funding gate")
    class FundingGate {

        @Test
        @DisplayName("an unpaid order is not visible to its supplier and has no deadline")
        void unpaidOrderNeverReachesTheSupplier() throws Exception {
            var submitted = submit("400", 10);

            // Guardrail 16 and doc 01 §14, which this phase exists to close.
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            assertThat(api.get(submitted.seller().token(),
                    "/api/v1/supplier-stores/" + submitted.seller().storeId() + "/orders/pending")
                    .at("/data"))
                    .describedAs("the supplier's inbox must be empty")
                    .isEmpty();

            // And the clock has not started — a deadline running during checkout
            // could hand the supplier an already-expired order.
            var deadline = jdbc.queryForObject(
                    "select acceptance_deadline from supplier_order where id = ?",
                    java.sql.Timestamp.class, submitted.orderId());
            assertThat(deadline).isNull();
        }

        @Test
        @DisplayName("paying releases the order and starts its countdown")
        void payingReleasesTheOrder() throws Exception {
            var submitted = submit("400", 10);
            var payment = payAndConfirm(submitted);

            assertThat(payment.get("status").asText()).isEqualTo("AUTHORIZED");
            assertThat(payment.get("fundsSecured").asBoolean()).isTrue();
            assertThat(payment.get("authorizedAmount").asDouble()).isEqualTo(4000.00);

            assertThat(orderStatus(submitted.orderId())).isEqualTo("PENDING_ACCEPTANCE");

            var inbox = api.get(submitted.seller().token(),
                    "/api/v1/supplier-stores/" + submitted.seller().storeId() + "/orders/pending")
                    .at("/data");
            assertThat(inbox).hasSize(1);
            // The countdown starts now, not at submission.
            assertThat(inbox.get(0).get("secondsRemaining").asLong()).isPositive();
        }

        @Test
        @DisplayName("a declined card leaves the supplier with nothing")
        void declinedCardAbandonsTheOrder() throws Exception {
            // .13 makes the mock decline — see MockPaymentProvider. A test asks for
            // a failure by ordering one, so the failing path is the same code path.
            var submitted = submit("400.13", 1);

            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());
            api.post(submitted.buyer().token(),
                    "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", providerPayment.providerPaymentId()));

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertThat(api.get(submitted.seller().token(),
                    "/api/v1/supplier-stores/" + submitted.seller().storeId() + "/orders/pending")
                    .at("/data")).isEmpty();
        }

        @Test
        @DisplayName("credit is refused until credit funding exists")
        void creditIsRefusedForNow() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");

            long skuId = api.post(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "C-" + productId,
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            long offerId = jdbc.queryForObject(
                    "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                    Long.class, skuId);

            long procurementId = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                    Map.of("supplierOfferId", offerId, "quantity", 5)).at("/data/id").asLong();

            api.patchStatus(buyer.token(), "/api/v1/procurements/" + procurementId
                    + "/payment-method", Map.of("paymentMethod", "CREDIT"));
            api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                    Map.of("acceptPriceChanges", false));

            // Refused rather than quietly allowed. An unfunded credit order would be
            // the same guardrail-16 violation prepaid orders used to have.
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/procurements/" + procurementId + "/submit")
                            .header("Authorization", "Bearer " + buyer.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(422);
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_order where procurement_id = ?",
                    Integer.class, procurementId)).isZero();
        }
    }

    // ── Capture ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("accepting captures the full amount")
        void fullAcceptanceCapturesEverything() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/accept")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON));

            // Marked, not yet taken — the provider call happens outside the
            // acceptance transaction.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURE_PENDING");

            paymentJobs.capturePending();

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(decimal(submitted.paymentId(), "captured_amount"))
                    .isEqualByComparingTo("4000.00");
        }

        @Test
        @DisplayName("a partial acceptance captures only what was accepted")
        void partialAcceptanceCapturesOnlyAccepted() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, submitted.orderId());

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/partial-accept")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "acceptedQuantity", 6))))));

            paymentJobs.capturePending();

            // Doc 01 §14: the restaurant pays for six, not ten.
            assertThat(decimal(submitted.paymentId(), "captured_amount"))
                    .isEqualByComparingTo("2400.00");
            // The rest was never taken, so it is released rather than refunded —
            // no reversal on the customer's statement.
            assertThat(decimal(submitted.paymentId(), "released_amount"))
                    .isEqualByComparingTo("1600.00");
            assertThat(decimal(submitted.paymentId(), "refunded_amount"))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a rejection releases the authorisation and charges nothing")
        void rejectionReleasesEverything() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/reject")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))));

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("RELEASED");
            assertThat(decimal(submitted.paymentId(), "captured_amount"))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a retryable capture failure returns the payment to AUTHORIZED")
        void captureFailureIsRetried() throws Exception {
            // .17 makes the mock fail the capture.
            var submitted = submit("400.17", 1);
            payAndConfirm(submitted);

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/accept")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON));

            paymentJobs.capturePending();

            // The money is still held, so the payment is exactly where it was and
            // the next sweep tries again — rather than being stranded.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("AUTHORIZED");
            // And the acceptance stands: a gateway problem is not the supplier's.
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
        }
    }

    // ── Doc 46 edge cases ────────────────────────────────────────────────

    @Nested
    @DisplayName("recovery")
    class Recovery {

        @Test
        @DisplayName("a client that dies after paying still gets its order placed")
        void lostCallbackIsRecovered() throws Exception {
            // Doc 46: "client timeout after successful payment → server state
            // recovery". The customer paid; the app never came back to tell us.
            var submitted = submit("400", 10);
            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());

            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            // Nothing told us, so we ask. Age the payment past the staleness
            // threshold and record the provider's id, as a real reconciliation
            // would find it from the intent.
            jdbc.update("update payment set provider_payment_id = ?, updated_at = "
                            + "date_sub(utc_timestamp(6), interval 10 minute) where id = ?",
                    providerPayment.providerPaymentId(), submitted.paymentId());

            paymentJobs.reconcileStale();

            assertThat(orderStatus(submitted.orderId()))
                    .describedAs("the order the customer paid for must reach its supplier")
                    .isEqualTo("PENDING_ACCEPTANCE");
        }

        @Test
        @DisplayName("a duplicate webhook is accepted once and acted on once")
        void duplicateWebhookIsIdempotent() throws Exception {
            var submitted = submit("400", 10);
            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());

            String eventId = "evt_" + UUID.randomUUID();
            String body = webhookBody(eventId, "payment.authorized",
                    providerPayment.providerPaymentId(), submitted.providerOrderId());

            assertThat(postWebhook(body)).isEqualTo(200);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("PENDING_ACCEPTANCE");

            // Providers retry. The second delivery must change nothing — and must
            // still return 200, or they will keep retrying.
            assertThat(postWebhook(body)).isEqualTo(200);

            assertThat(jdbc.queryForObject(
                    "select count(*) from payment_webhook_event where provider_event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        }

        @Test
        @DisplayName("an out-of-order webhook cannot drag a payment backwards")
        void outOfOrderWebhookIsIgnored() throws Exception {
            var submitted = submit("400", 10);
            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());

            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    providerPayment.providerPaymentId(), submitted.providerOrderId()));

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/accept")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON));
            paymentJobs.capturePending();

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURED");

            // A late 'authorized' event now arrives. Doc 03 §6 and doc 46: it
            // describes the past, and must not undo the capture.
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    providerPayment.providerPaymentId(), submitted.providerOrderId()));

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("a webhook with a bad signature is refused")
        void unsignedWebhookIsRefused() throws Exception {
            // Doc 09 §5. Without this, anyone could mark any payment captured by
            // posting JSON at us.
            int status = mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                            .header("X-Razorpay-Signature", "not-the-right-signature")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"evt_forged\",\"event\":\"payment.captured\"}"))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(400);
            assertThat(jdbc.queryForObject(
                    "select count(*) from payment_webhook_event where provider_event_id = ?",
                    Integer.class, "evt_forged")).isZero();
        }
    }

    // ── Refunds ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refunds")
    class Refunds {

        @Test
        @DisplayName("a refund returns captured money and updates the payment")
        void refundReturnsMoney() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            var refund = requestRefund(submitted, "1000.00", "DISPUTE_RESOLVED",
                    UUID.randomUUID().toString()).at("/data");
            assertThat(refund.get("status").asText()).isEqualTo("REQUESTED");

            paymentJobs.processRefunds();

            assertThat(decimal(submitted.paymentId(), "refunded_amount"))
                    .isEqualByComparingTo("1000.00");
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("PARTIALLY_REFUNDED");
        }

        @Test
        @DisplayName("a repeated refund request returns the original, not a second refund")
        void refundIsIdempotent() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            String key = UUID.randomUUID().toString();
            long first = requestRefund(submitted, "1000.00", "DISPUTE_RESOLVED", key)
                    .at("/data/id").asLong();
            long second = requestRefund(submitted, "1000.00", "DISPUTE_RESOLVED", key)
                    .at("/data/id").asLong();

            // Doc 22. A duplicate refund is money leaving twice.
            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject(
                    "select count(*) from refund where payment_id = ?",
                    Integer.class, submitted.paymentId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a refund cannot exceed what was captured")
        void cannotRefundMoreThanCaptured() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            // Returning money we never took.
            assertThat(requestRefundStatus(submitted, "9999.00", "DISPUTE_RESOLVED",
                    UUID.randomUUID().toString()))
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an uncaptured payment cannot be refunded")
        void cannotRefundAnAuthorization() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            // Money merely held is released, not refunded — a refund would appear
            // on the customer's statement as a reversal of a charge that never was.
            assertThat(requestRefundStatus(submitted, "100.00", "CANCELLATION",
                    UUID.randomUUID().toString()))
                    .isEqualTo(409);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void acceptAndCapture(Submitted submitted) throws Exception {
        mvc.perform(MockMvcRequestBuilders
                .post("/api/v1/supplier-orders/" + submitted.orderId() + "/accept")
                .header("Authorization", "Bearer " + submitted.seller().token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON));
        paymentJobs.capturePending();
    }

    private JsonNode requestRefund(Submitted submitted, String amount, String reason, String key)
            throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/payments/" + submitted.paymentId() + "/refund")
                        .header("Authorization", "Bearer " + submitted.buyer().token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("amount", amount, "reason", reason))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int requestRefundStatus(Submitted submitted, String amount, String reason, String key)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/payments/" + submitted.paymentId() + "/refund")
                        .header("Authorization", "Bearer " + submitted.buyer().token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("amount", amount, "reason", reason))))
                .andReturn().getResponse().getStatus();
    }

    private int postWebhook(String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                        .header("X-Razorpay-Signature", MockPaymentProvider.TEST_SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private static String webhookBody(String eventId, String eventType,
                                      String providerPaymentId, String providerOrderId) {
        return """
                {"id":"%s","event":"%s","payment_id":"%s","order_id":"%s"}
                """.formatted(eventId, eventType, providerPaymentId, providerOrderId);
    }

}
