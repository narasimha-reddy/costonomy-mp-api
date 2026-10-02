package com.costonomy.mp.payment;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestOrder;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    // A spy, so the hardening cases can see what surrounds each provider call.
    // Every other case uses it exactly as the real mock.
    @SpyBean private MockPaymentProvider mockProvider;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private RefundService refundService;

    private ApiClient api;
    private TestOrder orders;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
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
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000", "name", name + " store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());
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
        return submit(newBuyer(), unitPrice, quantity);
    }

    private Submitted submit(Buyer buyer, String unitPrice, int quantity) throws Exception {
        var seller = newSeller("ABC Foods");
        long productId = TestCatalog.freshProduct(jdbc, "paneer");

        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
        // Through the request, which is how an order is made now (D-091). Pickup,
        // so the amount charged is the goods alone -- these cases are about what
        // happens to that figure, and a delivery fee folded into it would make
        // every assertion here about arithmetic rather than about payment.
        var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                skuId, quantity, quantity, "PICKUP", null, null);

        return new Submitted(buyer, seller,
                placed.orderId(), placed.paymentId(), placed.providerOrderId());
    }

    /** Simulate the customer finishing checkout, then tell the API about it. */
    private JsonNode payAndConfirm(Submitted submitted) throws Exception {
        var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());
        return api.post(submitted.buyer().token(),
                "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                Map.of("providerPaymentId", providerPayment.providerPaymentId())).at("/data");
    }

    /**
     * The supplier marks the order preparing, then ready — the moment its money
     * is taken since D-103. Before it the payment is only held.
     */
    private void dispatch(Submitted submitted) throws Exception {
        dispatch(submitted.orderId(), submitted.seller().token());
    }

    private int supplierStep(Submitted submitted, String step) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + submitted.orderId() + "/" + step)
                        .header("Authorization", "Bearer " + submitted.seller().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
    }

    private void dispatch(long orderId, String sellerToken) throws Exception {
        for (String step : List.of("preparing", "ready")) {
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + orderId + "/" + step)
                            .header("Authorization", "Bearer " + sellerToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getStatus();
            assertThat(status).describedAs("supplier " + step).isEqualTo(200);
        }
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

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
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
        @DisplayName("paying confirms the order and puts it in front of the supplier")
        void payingReleasesTheOrder() throws Exception {
            var submitted = submit("400", 10);
            var payment = payAndConfirm(submitted);

            // CAPTURE_PENDING, not AUTHORIZED. D-091 moved capture to
            // confirmation: it used to wait for the supplier to accept, and
            // there is no longer an acceptance to wait for. The money is marked
            // the instant the order is confirmed and taken by the job.
            // Held, not yet taken: since D-103 the money is taken when the supplier
            // marks the order ready, so the order is funded but a cancellation
            // before then only drops the hold.
            assertThat(payment.get("status").asText()).isEqualTo("AUTHORIZED");
            assertThat(payment.get("fundsSecured").asBoolean()).isTrue();
            assertThat(payment.get("authorizedAmount").asDouble()).isEqualTo(4000.00);

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");

            var inbox = api.get(submitted.seller().token(),
                    "/api/v1/supplier-stores/" + submitted.seller().storeId() + "/orders/pending")
                    .at("/data");
            assertThat(inbox).hasSize(1);
            // No countdown. D-091: the order arrives agreed, so there is nothing
            // for the supplier to answer and no clock against them. The clock
            // that mattered ran on the request, before any of this.
            assertThat(inbox.get(0).get("secondsRemaining").asLong()).isZero();
        }

        @Test
        @DisplayName("a declined card leaves the supplier with nothing, and the order still payable")
        void declinedCardLeavesTheOrderPayable() throws Exception {
            // .13 makes the mock decline — see MockPaymentProvider. A test asks for
            // a failure by ordering one, so the failing path is the same code path.
            var submitted = submit("400.13", 1);

            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());
            var confirmed = api.post(submitted.buyer().token(),
                    "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", providerPayment.providerPaymentId())).at("/data");

            // The supplier sees nothing, as before.
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");
            assertThat(api.get(submitted.seller().token(),
                    "/api/v1/supplier-stores/" + submitted.seller().storeId() + "/orders/pending")
                    .at("/data")).isEmpty();
            // But one decline no longer ends it (D-101): Razorpay lets the customer
            // try again on the same order, so the payment waits, with the reason.
            assertThat(confirmed.at("/status").asText()).isEqualTo("CREATED");
            assertThat(confirmed.at("/fundsSecured").asBoolean()).isFalse();
            assertThat(confirmed.at("/failureReason").asText()).isNotBlank();
            assertThat(jdbc.queryForObject("select count(*) from payment_transaction where payment_id = ? "
                    + "and transaction_type = 'AUTHORIZE' and status = 'FAILED'",
                    Integer.class, submitted.paymentId())).isEqualTo(1);
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

            // Get as far as an answered request, then try to order it on credit.
            // After D-091 order creation is the only place an order is made, so
            // it is the only place this can be refused -- and an unfunded credit
            // order would be the same guardrail-16 violation prepaid orders used
            // to have.
            long intentId = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/intent-items",
                    Map.of("supplierSkuId", skuId, "quantity", 5)).at("/data/id").asLong();
            long itemId = api.get(buyer.token(), "/api/v1/intents/" + intentId)
                    .at("/data/items/0/id").asLong();
            api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());

            keyed(seller.token(), "/api/v1/intents/" + intentId + "/respond",
                    Map.of("lines", List.of(
                            Map.of("intentItemId", itemId, "offeredQuantity", 5))));

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/intents/" + intentId + "/orders")
                            .header("Authorization", "Bearer " + buyer.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT"))))
                    .andReturn().getResponse().getStatus();

            // 422: the request is well formed and the credit simply is not
            // there, which is a state refusal rather than a bad field.
            assertThat(status).isEqualTo(422);
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_order where outlet_id = ?",
                    Integer.class, buyer.outletId())).isZero();
        }
    }

    // ── Capture ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("confirming only holds the money; marking the order ready takes it")
        void heldUntilReadyThenCaptured() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            // Held, not taken (D-103): the order is confirmed and the supplier can
            // see it, but a cancellation now only drops the hold.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("AUTHORIZED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
            paymentJobs.capturePending();
            assertThat(decimal(submitted.paymentId(), "captured_amount")).isEqualByComparingTo("0");

            dispatch(submitted);

            // Marked at "ready", not yet taken — the provider call happens outside
            // the transaction that moved the order.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURE_PENDING");

            paymentJobs.capturePending();

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(decimal(submitted.paymentId(), "captured_amount"))
                    .isEqualByComparingTo("4000.00");
        }

        @Test
        @DisplayName("a short answer captures only what the supplier offered")
        void shortAnswerCapturesOnlyOffered() throws Exception {
            // Doc 01 §14, by a different route. D-091 moved the partial onto the
            // request: ten are asked for, six offered, and the order is created
            // for six. There is nothing to release, because nothing was ever
            // authorised for the other four — which is strictly better than
            // authorising ten and handing four back.
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long skuId = api.post(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "P-" + productId,
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();

            var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                    skuId, 10, 6, "PICKUP", null, null);
            var providerPayment = mockProvider.completeCheckout(placed.providerOrderId());
            api.post(buyer.token(), "/api/v1/payments/" + placed.paymentId() + "/confirm",
                    Map.of("providerPaymentId", providerPayment.providerPaymentId()));
            dispatch(placed.orderId(), seller.token());
            paymentJobs.capturePending();

            assertThat(decimal(placed.paymentId(), "captured_amount"))
                    .isEqualByComparingTo("2400.00");
            assertThat(decimal(placed.paymentId(), "authorized_amount"))
                    .as("only the offered six were ever authorised")
                    .isEqualByComparingTo("2400.00");
            assertThat(decimal(placed.paymentId(), "refunded_amount"))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a supplier cancelling returns the money")
        void supplierCancellationReturnsTheMoney() throws Exception {
            // What rejection became. Since D-103 the money is only held until the
            // order is ready, and a supplier can back out only before that — so
            // the hold is dropped and nothing is ever charged or refunded.
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            paymentJobs.capturePending();

            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + submitted.orderId() + "/supplier-cancel")
                    .header("Authorization", "Bearer " + submitted.seller().token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))));

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject(
                    "select cancelled_by from supplier_order where id = ?",
                    String.class, submitted.orderId())).isEqualTo("SUPPLIER");
            // The side effect: released, never taken, nothing to refund.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("RELEASED");
            assertThat(decimal(submitted.paymentId(), "captured_amount")).isEqualByComparingTo("0");
            assertThat(decimal(submitted.paymentId(), "released_amount")).isEqualByComparingTo("4000");
            assertThat(jdbc.queryForObject("select count(*) from refund where payment_id = ?",
                    Integer.class, submitted.paymentId())).isZero();
            // And the capture job has nothing to take.
            paymentJobs.capturePending();
            assertThat(decimal(submitted.paymentId(), "captured_amount")).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an order cannot be cancelled once ready, and its money is taken there")
        void readyIsWhereMoneyIsTaken() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + submitted.orderId() + "/supplier-cancel")
                            .header("Authorization", "Bearer " + submitted.seller().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))))
                    .andReturn().getResponse().getStatus();

            // Past "ready" the path is a dispute, not a cancellation (doc 01 §13),
            // which is why taking the money here can never race a cancellation.
            assertThat(status).isGreaterThanOrEqualTo(400);
            paymentJobs.capturePending();
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("an order whose hold is about to lapse cannot be marked ready")
        void expiringHoldBlocksDispatch() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            // Five days is Razorpay's limit; past its margin the goods must not
            // leave against money that is about to go back to the restaurant.
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), interval 5 day) "
                    + "where id = ?", submitted.paymentId());
            supplierStep(submitted, "preparing");

            int status = supplierStep(submitted, "ready");

            assertThat(status).isEqualTo(409);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("PREPARING");
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("an order cancelled after its money was taken is refunded in full, once")
        void cancelledAfterCaptureIsRefundedOnce() throws Exception {
            // Only reachable for an order captured before D-103 moved capture to
            // "ready" — the safety net for it, which used to log and stop.
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            jdbc.update("update supplier_order set status = 'CONFIRMED' where id = ?", submitted.orderId());

            for (int event = 0; event < 2; event++) {
                mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + submitted.orderId() + "/supplier-cancel")
                        .header("Authorization", "Bearer " + submitted.seller().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))));
            }
            paymentJobs.processRefunds();

            assertThat(jdbc.queryForList("select amount, status, idempotency_key from refund where payment_id = ?",
                    submitted.paymentId()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("400.00");
                        assertThat(row.get("status")).isEqualTo("COMPLETED");
                        assertThat(row.get("idempotency_key")).isEqualTo("cancel-order-" + submitted.orderId());
                    });
            // To the wallet, once (D-104).
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.00");
            assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where refund_id is not null "
                    + "and supplier_order_id = ?", Integer.class, submitted.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a retryable capture failure stays queued, and the next run really tries again")
        void captureFailureIsRetried() throws Exception {
            // .17 makes the mock fail the capture.
            var submitted = submit("400.17", 1);
            payAndConfirm(submitted);
            dispatch(submitted);

            paymentJobs.capturePending();
            paymentJobs.capturePending();

            // The side effect, not the status (D-099): this test used to assert
            // AUTHORIZED and call it retried, while nothing ever queued the
            // capture again. Two runs must mean two attempts.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CAPTURE_PENDING");
            assertThat(jdbc.queryForObject("select count(*) from payment_transaction "
                    + "where payment_id = ? and transaction_type = 'CAPTURE' and status = 'FAILED'",
                    Integer.class, submitted.paymentId())).isEqualTo(2);
            // And the order stands: a gateway problem is not the supplier's.
            assertThat(orderStatus(submitted.orderId())).isEqualTo("READY_FOR_PICKUP");
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
                    .isEqualTo("CONFIRMED");
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
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");

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
            dispatch(submitted);

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

        @Test
        @DisplayName("another order's payment cannot confirm this one")
        void foreignPaymentCannotConfirm() throws Exception {
            var cheap = submit("10", 1);
            var dear = submit("400", 10);
            // Paid, genuinely — but for the other order.
            var cheapPayment = mockProvider.completeCheckout(cheap.providerOrderId());

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/payments/" + dear.paymentId() + "/confirm")
                            .header("Authorization", "Bearer " + dear.buyer().token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("providerPaymentId", cheapPayment.providerPaymentId()))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(400);
            // The side effect, not just the refusal: the dear order must still be
            // unfunded, and invisible to its supplier.
            assertThat(orderStatus(dear.orderId())).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, dear.paymentId())).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("a Razorpay-shaped webhook is deduplicated on its header event id")
        void webhookDedupedOnHeader() throws Exception {
            var submitted = submit("400", 10);
            var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId());

            // Razorpay's shape: the event id is a header, and the body has none.
            String eventId = "evt_" + UUID.randomUUID();
            String body = """
                    {"entity":"event","event":"payment.authorized","payload":{"payment":{"entity":
                      {"id":"%s","order_id":"%s"}}}}
                    """.formatted(providerPayment.providerPaymentId(), submitted.providerOrderId());

            for (int delivery = 0; delivery < 2; delivery++) {
                int status = mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                                .header("X-Razorpay-Signature", MockPaymentProvider.TEST_SIGNATURE)
                                .header("X-Razorpay-Event-Id", eventId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
                assertThat(status).isEqualTo(200);
            }

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject(
                    "select count(*) from payment_webhook_event where provider_event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        }

        @Test
        @DisplayName("reconciliation finds a payment it only knows the intent of")
        void reconcileByIntent() throws Exception {
            var submitted = submit("400", 10);
            // Paid, and then nothing: no confirm, no webhook, so we never learned
            // the payment id. All we hold is the intent.
            mockProvider.completeCheckout(submitted.providerOrderId());
            jdbc.update("update payment set updated_at = "
                    + "date_sub(utc_timestamp(6), interval 10 minute) where id = ?",
                    submitted.paymentId());

            paymentJobs.reconcileStale();

            assertThat(orderStatus(submitted.orderId()))
                    .describedAs("the order the customer paid for must reach its supplier")
                    .isEqualTo("CONFIRMED");
        }
    }

    // ── Refunds ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refunds go to the wallet (D-104)")
    class Refunds {

        @Test
        @DisplayName("a refund credits the wallet at once and counts against the payment, with no provider call")
        void refundReturnsMoney() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            var refund = creditWallet(submitted, "1000.00");

            assertThat(jdbc.queryForMap("select status, destination from refund where id = ?", refund.getId()))
                    .containsEntry("status", "COMPLETED").containsEntry("destination", "WALLET");
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("1000.00");
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("PARTIALLY_REFUNDED");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("1000.00");
            assertThat(jdbc.queryForMap("select kind, direction, reference, refund_id, supplier_order_id "
                    + "from wallet_transaction where refund_id = ?", refund.getId()))
                    .containsEntry("kind", "REFUND").containsEntry("direction", "CREDIT")
                    .containsEntry("reference", "refund-" + refund.getId())
                    .containsEntry("supplier_order_id", submitted.orderId());
            // The money has not left the platform: nothing was sent to the provider.
            paymentJobs.processRefunds();
            String providerPaymentId = jdbc.queryForObject("select provider_payment_id from payment where id = ?",
                    String.class, submitted.paymentId());
            verify(mockProvider, times(0)).refund(eq(providerPaymentId), any(), anyString());
        }

        @Test
        @DisplayName("a repeated refund returns the original and credits once")
        void refundIsIdempotent() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            String key = UUID.randomUUID().toString();
            long first = creditWallet(submitted, "1000.00", key).getId();
            long second = creditWallet(submitted, "1000.00", key).getId();

            // Doc 22. A duplicate refund is money leaving twice.
            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject("select count(*) from refund where payment_id = ?",
                    Integer.class, submitted.paymentId())).isEqualTo(1);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a refund cannot exceed what was captured")
        void cannotRefundMoreThanCaptured() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            // Returning money we never took.
            assertThat(refusal(() -> creditWallet(submitted, "4000.01"))).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an uncaptured payment cannot be refunded")
        void cannotRefundAnAuthorization() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);

            // Money merely held is released, not refunded.
            assertThat(refusal(() -> creditWallet(submitted, "100.00"))).isEqualTo(ErrorCode.PAYMENT_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a restaurant cannot refund itself: the endpoint is gone")
        void noSelfRefund() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/payments/" + submitted.paymentId() + "/refund")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("amount", "100.00", "reason", "DISPUTE_RESOLVED"))))
                    .andReturn().getResponse().getStatus();

            // The finding this closes: the buyer could refund their own captured
            // payment for any reason, with no one on the supplier's side agreeing.
            assertThat(status).isIn(404, 405);
            assertThat(jdbc.queryForObject("select count(*) from refund where payment_id = ?",
                    Integer.class, submitted.paymentId())).isZero();
        }
    }

    @Nested
    @DisplayName("withdrawals go back to the card (D-104)")
    class Withdrawals {

        @Test
        @DisplayName("a withdrawal is a provider refund on the source payment, counted once")
        void withdrawalGoesBackToTheCard() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            creditWallet(submitted, "1000.00");

            var withdrawal = withdraw(submitted.buyer(), "600.00", UUID.randomUUID().toString()).at("/data");
            assertThat(withdrawal.get("balance").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(withdrawal.at("/parts/0/paymentId").asLong()).isEqualTo(submitted.paymentId());
            long refundId = withdrawal.at("/parts/0/refundId").asLong();

            paymentJobs.processRefunds();

            assertThat(jdbc.queryForMap("select status, destination, reason, amount from refund where id = ?", refundId))
                    .containsEntry("status", "COMPLETED").containsEntry("destination", "ORIGINAL")
                    .containsEntry("reason", "WALLET_WITHDRAWAL");
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId));
            // Counted when it reached the wallet; the trip to the card is not a
            // second refund of the same money.
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("1000.00");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.00");

            var statement = api.get(submitted.buyer().token(),
                    "/api/v1/outlets/" + submitted.buyer().outletId() + "/wallet").at("/data/recent");
            assertThat(statement.get(0).get("kind").asText()).isEqualTo("WITHDRAWAL");
            assertThat(statement.get(0).get("refundStatus").asText()).isEqualTo("COMPLETED");
            assertThat(statement.get(1).get("kind").asText()).isEqualTo("REFUND");
        }

        @Test
        @DisplayName("more than the balance is refused and nothing moves")
        void cannotWithdrawMoreThanTheBalance() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            creditWallet(submitted, "100.00");

            assertThat(withdrawStatus(submitted.buyer(), "100.01", UUID.randomUUID().toString())).isEqualTo(400);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("100.00");
            assertThat(withdrawalsOf(submitted)).isZero();
        }

        @Test
        @DisplayName("only refund money can leave: a top-up has no card to go back to")
        void onlyRefundMoneyLeaves() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            creditWallet(submitted, "100.00");
            api.post(submitted.buyer().token(), "/api/v1/outlets/" + submitted.buyer().outletId()
                    + "/wallet/top-up", Map.of("amount", "500.00"));

            assertThat(withdrawStatus(submitted.buyer(), "200.00", UUID.randomUUID().toString())).isEqualTo(400);
            assertThat(withdraw(submitted.buyer(), "100.00", UUID.randomUUID().toString())
                    .at("/data/balance").decimalValue()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("a withdrawal is split across the payments it came from, oldest first")
        void splitAcrossPayments() throws Exception {
            var buyer = newBuyer();
            var older = submit(buyer, "400", 1);
            payAndConfirm(older);
            acceptAndCapture(older);
            var newer = submit(buyer, "400", 1);
            payAndConfirm(newer);
            acceptAndCapture(newer);
            creditWallet(older, "150.00");
            creditWallet(newer, "300.00");

            var parts = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts");

            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).get("paymentId").asLong()).isEqualTo(older.paymentId());
            assertThat(parts.get(0).get("amount").decimalValue()).isEqualByComparingTo("150.00");
            assertThat(parts.get(1).get("paymentId").asLong()).isEqualTo(newer.paymentId());
            assertThat(parts.get(1).get("amount").decimalValue()).isEqualByComparingTo("250.00");
            // Each card gets back no more than was refunded from it.
            assertThat(withdrawStatus(buyer, "50.01", UUID.randomUUID().toString())).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("the same key returns the first withdrawal; a different amount on it is refused")
        void withdrawalIsIdempotent() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            creditWallet(submitted, "500.00");

            String key = UUID.randomUUID().toString();
            long first = withdraw(submitted.buyer(), "200.00", key).at("/data/parts/0/refundId").asLong();
            long again = withdraw(submitted.buyer(), "200.00", key).at("/data/parts/0/refundId").asLong();

            assertThat(again).isEqualTo(first);
            assertThat(withdrawStatus(submitted.buyer(), "300.00", key)).isEqualTo(409);
            assertThat(withdrawalsOf(submitted)).isEqualTo(1);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("another restaurant cannot withdraw from this wallet")
        void anotherTenantCannotWithdraw() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            creditWallet(submitted, "500.00");
            var stranger = newBuyer();

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/outlets/" + submitted.buyer().outletId() + "/wallet/withdraw")
                            .header("Authorization", "Bearer " + stranger.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("amount", "100.00"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(404);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("two withdrawals of the whole balance at once: one succeeds, five races in a row")
        void concurrentWithdrawalsSpendOnce() throws Exception {
            for (int race = 0; race < 5; race++) {
                var submitted = submit("400", 1);
                payAndConfirm(submitted);
                acceptAndCapture(submitted);
                creditWallet(submitted, "400.00");

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(2);
                var a = pool.submit(() -> { start.await(); return withdrawStatus(submitted.buyer(), "400.00", UUID.randomUUID().toString()); });
                var b = pool.submit(() -> { start.await(); return withdrawStatus(submitted.buyer(), "400.00", UUID.randomUUID().toString()); });
                start.countDown();
                var outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
                pool.shutdown();

                // The wallet lock decides it: the second finds nothing left, rather
                // than both sending the same 400 back to the card — and is told
                // that, not that something changed underneath it.
                assertThat(outcomes).containsExactlyInAnyOrder(200, 400);
                assertThat(withdrawalsOf(submitted)).isEqualTo(1);
                assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            }
        }

        @Test
        @DisplayName("a cancelled order whose money was taken is refunded to the wallet")
        void cancellationRefundsToTheWallet() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            jdbc.update("update supplier_order set status = 'CONFIRMED' where id = ?", submitted.orderId());

            int cancelled = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + submitted.orderId() + "/supplier-cancel")
                            .header("Authorization", "Bearer " + submitted.seller().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))))
                    .andReturn().getResponse().getStatus();
            assertThat(cancelled).isEqualTo(200);

            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.00");
            assertThat(jdbc.queryForObject("select destination from refund where payment_id = ?",
                    String.class, submitted.paymentId())).isEqualTo("WALLET");
        }
    }

    // ── Hardening (D-099) ────────────────────────────────────────────────

    @Nested
    @DisplayName("hardening")
    class Hardening {

        /** Records, for each provider call, whether a database transaction was open. */
        private final List<String> openDuring = new CopyOnWriteArrayList<>();

        @org.junit.jupiter.api.AfterEach
        void unspy() {
            reset(mockProvider);
        }

        private void recordTransactionState(String call) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                openDuring.add(call);
            }
        }

        @Test
        @DisplayName("confirm, capture and refund call the provider with no transaction open")
        void providerCallsHoldNoConnection() throws Exception {
            doAnswer(call -> { recordTransactionState("fetchPayment"); return call.callRealMethod(); })
                    .when(mockProvider).fetchPayment(anyString());
            doAnswer(call -> { recordTransactionState("capture"); return call.callRealMethod(); })
                    .when(mockProvider).capture(anyString(), any(), anyString());
            doAnswer(call -> { recordTransactionState("refund"); return call.callRealMethod(); })
                    .when(mockProvider).refund(anyString(), any(), anyString());

            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            refundToCard(submitted, "100.00");
            paymentJobs.processRefunds();

            // The pool is ten connections; a provider call holding one is how ten
            // slow checkouts stall every endpoint. It must be none of them.
            assertThat(openDuring).isEmpty();
            // And the work still happened.
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("PARTIALLY_REFUNDED");
        }

        @Test
        @DisplayName("a refund left PROCESSING by a crash is resent once, with its own key")
        void stuckRefundIsResent() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            long refundId = refundToCard(submitted, "50.00");

            // What a process that died mid-call leaves behind.
            jdbc.update("update refund set status = 'PROCESSING', updated_at = "
                    + "date_sub(utc_timestamp(6), interval 10 minute) where id = ?", refundId);

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("COMPLETED");
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId));
        }

        @Test
        @DisplayName("a refund the provider declines goes to a person after one try, never resent")
        void declinedRefundNeedsReview() throws Exception {
            // .19 makes the mock decline the refund.
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            long refundId = refundToCard(submitted, "10.00");

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            // It was retried every thirty seconds for ever before (D-101); the
            // provider's "no" is an answer for a person, not a transient error.
            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("NEEDS_REVIEW");
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId));
            assertThat(withdrawalsOf(submitted)).isEqualTo(1);
            // Out of the wallet and not on the card: exactly the state a person
            // has to see, which is why it is NEEDS_REVIEW and logged at error.
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("two partial refunds that add up to the capture leave it fully refunded")
        void partialThenPartial() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();

            creditWallet(submitted, "150.00");
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("PARTIALLY_REFUNDED");

            creditWallet(submitted, "250.00");

            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("400.00");
            // And nothing more can go back than came in.
            assertThat(refusal(() -> creditWallet(submitted, "0.01"))).isNotNull();
        }

        @Test
        @DisplayName("confirm and a webhook arriving together authorise once, five races in a row")
        void confirmAndWebhookRace() throws Exception {
            // One race proves little: which thread wins changes run to run. Five
            // give both orders of arrival a fair chance to show up.
            for (int race = 0; race < 5; race++) {
                raceOnce();
            }
        }

        private void raceOnce() throws Exception {
            var submitted = submit("400", 1);
            var paid = mockProvider.completeCheckout(submitted.providerOrderId());
            String body = webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    paid.providerPaymentId(), submitted.providerOrderId());

            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            var confirm = pool.submit(() -> {
                start.await();
                return mvc.perform(MockMvcRequestBuilders
                                .post("/api/v1/payments/" + submitted.paymentId() + "/confirm")
                                .header("Authorization", "Bearer " + submitted.buyer().token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(
                                        Map.of("providerPaymentId", paid.providerPaymentId()))))
                        .andReturn().getResponse().getStatus();
            });
            var webhook = pool.submit(() -> { start.await(); return postWebhook(body); });
            start.countDown();
            int confirmStatus = confirm.get(30, TimeUnit.SECONDS);
            int webhookStatus = webhook.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // One of them won; neither may double-count, and neither may fail the
            // customer who really paid.
            assertThat(confirmStatus).isEqualTo(200);
            assertThat(webhookStatus).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from payment_transaction "
                    + "where payment_id = ? and transaction_type = 'AUTHORIZE'",
                    Integer.class, submitted.paymentId())).isEqualTo(1);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a provider that is down changes nothing, and the sweep recovers later")
        void providerDownThenRecovers() throws Exception {
            var submitted = submit("400", 1);
            var paid = mockProvider.completeCheckout(submitted.providerOrderId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).fetchPayment(anyString());

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/payments/" + submitted.paymentId() + "/confirm")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("providerPaymentId", paid.providerPaymentId()))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(503);
            assertThat(jdbc.queryForObject("select status from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo("CREATED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            // The provider comes back; the sweep finds the payment by its intent.
            reset(mockProvider);
            jdbc.update("update payment set updated_at = "
                    + "date_sub(utc_timestamp(6), interval 10 minute) where id = ?", submitted.paymentId());
            paymentJobs.reconcileStale();

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
        }
    }

    // ── Review fixes (D-101) ─────────────────────────────────────────────

    @Nested
    @DisplayName("review fixes")
    class ReviewFixes {

        @org.junit.jupiter.api.AfterEach
        void restore() {
            reset(mockProvider);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    (Object) org.springframework.test.util.AopTestUtils.getTargetObject(paymentJobs),
                    "reconcileBatch", 200);
        }

        private String status(long paymentId) {
            return jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId);
        }

        private String providerPaymentIdOf(long paymentId) {
            return jdbc.queryForObject("select provider_payment_id from payment where id = ?",
                    String.class, paymentId);
        }

        @Test
        @DisplayName("a declined attempt, then a paid one on the same order: the order is funded")
        void declineThenPay() throws Exception {
            var submitted = submit("400", 1);
            var declined = mockProvider.declineAttempt(submitted.providerOrderId());
            api.post(submitted.buyer().token(), "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", declined.providerPaymentId()));
            assertThat(status(submitted.paymentId())).isEqualTo("CREATED");

            var paid = mockProvider.completeCheckout(submitted.providerOrderId());
            api.post(submitted.buyer().token(), "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", paid.providerPaymentId()));

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
            assertThat(providerPaymentIdOf(submitted.paymentId())).isEqualTo(paid.providerPaymentId());
        }

        @Test
        @DisplayName("a declined attempt arriving after authorisation cannot fail the payment")
        void lateDeclineCannotFail() throws Exception {
            var submitted = submit("400", 1);
            var paid = mockProvider.completeCheckout(submitted.providerOrderId());
            api.post(submitted.buyer().token(), "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", paid.providerPaymentId()));
            // Held until the order is ready (D-103).
            assertThat(status(submitted.paymentId())).isEqualTo("AUTHORIZED");

            // Another attempt on the same order, declined, reported afterwards.
            var declined = mockProvider.declineAttempt(submitted.providerOrderId());
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.failed",
                    declined.providerPaymentId(), submitted.providerOrderId()));

            // Before (D-101) this failed the payment: the order went ahead, capture
            // never ran, and the supplier delivered for nothing.
            assertThat(status(submitted.paymentId())).isEqualTo("AUTHORIZED");
            assertThat(providerPaymentIdOf(submitted.paymentId())).isEqualTo(paid.providerPaymentId());
            dispatch(submitted);
            paymentJobs.capturePending();
            assertThat(status(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("READY_FOR_PICKUP");
        }

        @Test
        @DisplayName("abandoned checkouts past a full batch cannot stop the sweep finding a lost payment")
        void sweepSurvivesAFullBatch() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    (Object) org.springframework.test.util.AopTestUtils.getTargetObject(paymentJobs),
                    "reconcileBatch", 2);
            // Three checkouts nobody completed, two days old — more than a batch.
            var abandoned = List.of(submit("400", 1), submit("400", 1), submit("400", 1));
            for (var a : abandoned) {
                jdbc.update("update payment set created_at = date_sub(utc_timestamp(6), interval 2 day), "
                        + "updated_at = date_sub(utc_timestamp(6), interval 2 day) where id = ?", a.paymentId());
            }
            // And one the customer paid for, whose confirm and webhook were lost.
            var lost = submit("400", 1);
            mockProvider.completeCheckout(lost.providerOrderId());
            jdbc.update("update payment set created_at = date_sub(utc_timestamp(6), interval 23 hour), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 23 hour) where id = ?", lost.paymentId());

            paymentJobs.reconcileStale();
            paymentJobs.reconcileStale();

            // The abandoned ones are ended rather than left at the front for ever...
            for (var a : abandoned) {
                assertThat(status(a.paymentId())).isEqualTo("FAILED");
                assertThat(orderStatus(a.orderId())).isEqualTo("CANCELLED");
            }
            // ...so the paid one is found.
            assertThat(orderStatus(lost.orderId())).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a refund the provider accepts as pending is completed only when it finishes")
        void pendingRefundWaits() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            // .23 makes the mock accept the refund as pending.
            long refundId = refundToCard(submitted, "10.23");

            paymentJobs.processRefunds();
            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("PROCESSING");
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("10.23");

            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 5 minute) "
                    + "where id = ?", refundId);
            paymentJobs.processRefunds();

            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("COMPLETED");
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("10.23");
            // Asked about, never sent twice.
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId));
        }

        @Test
        @DisplayName("refunds still on their way count against what can be refunded")
        void inFlightRefundsCount() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();

            // A provider refund from before D-104, requested and not yet sent.
            jdbc.update("insert into refund (payment_id, supplier_order_id, amount, reason, status, "
                    + "idempotency_key, attempts) values (?, ?, 300.00, 'CANCELLATION', 'REQUESTED', ?, 0)",
                    submitted.paymentId(), submitted.orderId(), UUID.randomUUID().toString());
            // Not sent yet — and still, only 100.00 is left to promise.
            assertThat(refusal(() -> creditWallet(submitted, "200.00"))).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(creditWallet(submitted, "100.00").getStatus().name()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("a withdrawal on its way does not block a refund the payment can still cover")
        void withdrawalsAreNotCountedTwice() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();

            refundToCard(submitted, "300.00");
            // 300 is refunded (to the wallet, then on to the card); 100 is left.
            assertThat(creditWallet(submitted, "100.00").getStatus().name()).isEqualTo("COMPLETED");
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("400.00");
        }

        @Test
        @DisplayName("a refund key belongs to its payment")
        void refundKeyIsScoped() throws Exception {
            var first = submit("400", 1);
            payAndConfirm(first);
            dispatch(first);
            var second = submit("400", 1);
            payAndConfirm(second);
            dispatch(second);
            paymentJobs.capturePending();

            String key = UUID.randomUUID().toString();
            creditWallet(first, "10.00", key);
            assertThat(refusal(() -> creditWallet(second, "10.00", key))).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }

        @Test
        @DisplayName("a transient refund failure is retried with the same key, then handed to a person")
        void transientRefundFailureIsCapped() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            long refundId = refundToCard(submitted, "10.00");
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).refund(anyString(), any(), anyString());

            for (int run = 0; run < 7; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("NEEDS_REVIEW");
            verify(mockProvider, times(5)).refund(anyString(), any(), eq("mandi-refund-" + refundId));
        }

        @Test
        @DisplayName("confirm refuses something that isn't a payment id")
        void confirmValidatesTheId() throws Exception {
            var submitted = submit("400", 1);
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/payments/" + submitted.paymentId() + "/confirm")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"providerPaymentId\":\"pay_1\\r\\nINFO forged\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(400);
            assertThat(status(submitted.paymentId())).isEqualTo("CREATED");
        }
    }

    // ── Payment intent lookup (D-102) ────────────────────────────────────

    @Nested
    @DisplayName("payment intent lookup")
    class IntentLookup {

        private JsonNode intent(Submitted submitted, String token) throws Exception {
            return api.get(token, "/api/v1/supplier-orders/" + submitted.orderId() + "/payment-intent")
                    .at("/data");
        }

        @Test
        @DisplayName("an unpaid order's checkout can be fetched again, the same one, not a new one")
        void unpaidIsPayable() throws Exception {
            var submitted = submit("400", 1);

            var first = intent(submitted, submitted.buyer().token());
            var second = intent(submitted, submitted.buyer().token());

            assertThat(first.at("/payable").asBoolean()).isTrue();
            assertThat(first.at("/fundsSecured").asBoolean()).isFalse();
            // The order the payment was created with — asking never mints another.
            assertThat(first.at("/providerOrderId").asText()).isEqualTo(submitted.providerOrderId());
            assertThat(second.at("/providerOrderId").asText()).isEqualTo(submitted.providerOrderId());
            assertThat(first.at("/publicKey").asText()).isNotBlank();
        }

        @Test
        @DisplayName("a paid order says so and offers no checkout")
        void paidIsNotPayable() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);

            var intent = intent(submitted, submitted.buyer().token());
            assertThat(intent.at("/payable").asBoolean()).isFalse();
            assertThat(intent.at("/fundsSecured").asBoolean()).isTrue();
            assertThat(intent.at("/publicKey").isNull()).isTrue();
        }

        @Test
        @DisplayName("another restaurant cannot see it")
        void otherTenantGets404() throws Exception {
            var submitted = submit("400", 1);
            var stranger = newBuyer();
            assertThat(api.getStatus(stranger.token(),
                    "/api/v1/supplier-orders/" + submitted.orderId() + "/payment-intent")).isEqualTo(404);
        }
    }

    // ── Traceability (D-100) ─────────────────────────────────────────────

    @Nested
    @DisplayName("traceability")
    @ExtendWith(OutputCaptureExtension.class)
    class Traceability {

        @Autowired private TaskScheduler scheduler;

        @Test
        @DisplayName("one payment's story can be read from the logs and the audit trail, in order")
        void onePaymentReadsAsASequence(CapturedOutput output) throws Exception {
            var submitted = submit("400", 1);
            var paid = mockProvider.completeCheckout(submitted.providerOrderId());
            long pid = submitted.paymentId();

            // Confirm, as the app does — with its own request id.
            String requestId = "trace-" + UUID.randomUUID();
            mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/payments/" + pid + "/confirm")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .header("X-Request-Id", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("providerPaymentId", paid.providerPaymentId()))))
                    .andExpect(status().isOk());

            // The supplier marks it ready — the moment its money is taken (D-103).
            dispatch(submitted);

            // Capture through the real scheduler, so the run gets its job id the
            // way production does — not by calling the job directly.
            scheduler.schedule(new ScheduledMethodRunnable(paymentJobs,
                            PaymentJobs.class.getMethod("capturePending")), Instant.now())
                    .get(30, TimeUnit.SECONDS);

            // A webhook for the same payment, arriving late.
            String eventId = "evt_" + UUID.randomUUID();
            postWebhook(webhookBody(eventId, "payment.captured",
                    paid.providerPaymentId(), submitted.providerOrderId()));

            // Logs: every step names the payment, and says who moved it.
            String log = output.getOut();
            String authorised = lineWith(log, "Payment " + pid + " CREATED → AUTHORIZED via CONFIRM");
            assertThat(authorised).contains("[costonomy-mp-api," + requestId + "]")
                    .contains(" payment=" + pid + " ").contains(" order=" + submitted.orderId() + " ");
            // Marked by the supplier's "ready", so it carries their request, and the order.
            assertThat(lineWith(log, "Payment " + pid + " AUTHORIZED → CAPTURE_PENDING"))
                    .contains(" order=" + submitted.orderId());
            String captured = lineWith(log, "Payment " + pid + " CAPTURE_PENDING → CAPTURED via CAPTURE_JOB");
            assertThat(captured).containsPattern("\\[costonomy-mp-api,job-capturePending-[0-9a-f]{8}\\]")
                    .contains(" payment=" + pid + " ");
            assertThat(lineWith(log, "Webhook " + eventId + " payment.captured received"))
                    .contains(" rzp_event=" + eventId);
            assertThat(lineWith(log, "Webhook " + eventId + " PROCESSED")).contains(" rzp_event=" + eventId);

            // Audit: the same run ids, so a row leads back to the lines around it.
            var audit = jdbc.queryForList("select action, request_id from audit_log "
                    + "where entity_type = 'PAYMENT' and entity_id = ? order by id", pid);
            assertThat(audit).extracting(row -> row.get("action"))
                    .containsSubsequence("PAYMENT_AUTHORIZED", "PAYMENT_CAPTURE_PENDING", "PAYMENT_CAPTURED");
            assertThat(requestIdOf(audit, "PAYMENT_AUTHORIZED")).isEqualTo(requestId);
            assertThat(requestIdOf(audit, "PAYMENT_CAPTURED")).startsWith("job-capturePending-");
        }

        private String lineWith(String log, String text) {
            return log.lines().filter(line -> line.contains(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("no log line containing: " + text));
        }

        private String requestIdOf(List<Map<String, Object>> audit, String action) {
            return audit.stream().filter(row -> action.equals(row.get("action")))
                    .map(row -> String.valueOf(row.get("request_id"))).findFirst().orElseThrow();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void acceptAndCapture(Submitted submitted) throws Exception {
        dispatch(submitted);
        paymentJobs.capturePending();
    }

    /** Refund to the outlet's wallet, as an approved dispute or a cancellation does (D-104). */
    private Refund creditWallet(Submitted submitted, String amount) {
        return creditWallet(submitted, amount, UUID.randomUUID().toString());
    }

    private Refund creditWallet(Submitted submitted, String amount, String key) {
        return refundService.refundToWallet(null, submitted.paymentId(), new BigDecimal(amount),
                RefundReason.DISPUTE_RESOLVED, "test", key);
    }

    private org.springframework.mock.web.MockHttpServletResponse withdrawCall(Buyer buyer, String amount, String key)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/outlets/" + buyer.outletId() + "/wallet/withdraw")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", amount))))
                .andReturn().getResponse();
    }

    private JsonNode withdraw(Buyer buyer, String amount, String key) throws Exception {
        var response = withdrawCall(buyer, amount, key);
        assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private int withdrawStatus(Buyer buyer, String amount, String key) throws Exception {
        return withdrawCall(buyer, amount, key).getStatus();
    }

    /**
     * A provider refund, the only kind left (D-104): the money is credited to the
     * wallet and sent straight back to the card.
     *
     * @return the withdrawal's refund id
     */
    private long refundToCard(Submitted submitted, String amount) throws Exception {
        creditWallet(submitted, amount);
        return withdraw(submitted.buyer(), amount, UUID.randomUUID().toString())
                .at("/data/parts/0/refundId").asLong();
    }

    private BigDecimal balance(Buyer buyer) {
        return jdbc.queryForObject("select coalesce((select balance from wallet where outlet_id = ?), 0)",
                BigDecimal.class, buyer.outletId());
    }

    private int withdrawalsOf(Submitted submitted) {
        return jdbc.queryForObject("select count(*) from refund where payment_id = ? "
                + "and reason = 'WALLET_WITHDRAWAL'", Integer.class, submitted.paymentId());
    }

    /** The error code a refused call failed with. */
    private ErrorCode refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        var thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).code();
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


    /** A POST that needs an idempotency key, which ApiClient does not send. */
    private JsonNode keyed(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }
}
