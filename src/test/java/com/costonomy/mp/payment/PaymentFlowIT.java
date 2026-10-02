package com.costonomy.mp.payment;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.payment.service.CancellationService;
import com.costonomy.mp.payment.service.OrderFundingAdapter;
import com.costonomy.mp.common.outbox.OutboxPublisher;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
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
    @Autowired private CancellationService cancellations;
    @Autowired private com.costonomy.mp.payment.service.AlertThrottle alertThrottle;
    @Autowired private OrderFundingAdapter funding;
    @Autowired private com.costonomy.mp.payment.service.PaymentHoldPolicy holdPolicy;
    @Autowired private com.costonomy.mp.payment.service.CancelRefundSpeed cancelRefundSpeed;
    @Autowired private com.costonomy.mp.notification.NotificationRelayAccess relay;

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

    /** As {@link #payAndConfirm(Submitted)}, paid by the given method: upi, netbanking, card… */
    private JsonNode payAndConfirm(Submitted submitted, String method) throws Exception {
        var providerPayment = mockProvider.completeCheckout(submitted.providerOrderId(), method);
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
        @DisplayName("an order shows where its money is: held, then taken; or released when cancelled")
        void orderShowsItsPaymentLive() throws Exception {
            var taken = submit("400", 1);
            payAndConfirm(taken);
            assertThat(orderPaymentStatus(taken)).isEqualTo("AUTHORIZED");
            dispatch(taken);
            paymentJobs.capturePending();
            // It used to stay "AUTHORIZED" for good: the order kept a copy written
            // once, at release.
            assertThat(orderPaymentStatus(taken)).isEqualTo("CAPTURED");

            var cancelled = submit("400", 1);
            payAndConfirm(cancelled);
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + cancelled.orderId() + "/supplier-cancel")
                            .header("Authorization", "Bearer " + cancelled.seller().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(200);
            // Not "Authorized" on a cancelled order: the hold is gone.
            assertThat(orderPaymentStatus(cancelled)).isEqualTo("RELEASED");
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
            verify(mockProvider, times(0)).refund(eq(providerPaymentId), any(), anyString(), any());
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
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
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
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

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
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
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
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
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
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
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
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 7; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("NEEDS_REVIEW");
            verify(mockProvider, times(5)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
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

    // ── A debited payment on a cancelled order (D-109) ───────────────────

    @Nested
    @DisplayName("cancelling an order whose money was already debited (D-109)")
    @ExtendWith(OutputCaptureExtension.class)
    class CancelDebited {

        /**
         * The database is shared by every test in the class, and the cancellation job
         * settles every CANCEL_PENDING payment it finds, not only this test's. A payment
         * an earlier test left waiting would be captured under this one's spy stubs and
         * counts, so it is put aside for a person, which is what the job skips.
         */
        @BeforeEach
        void putAsideWhatEarlierTestsLeft() {
            jdbc.update("update payment set review_required_at = utc_timestamp(6), "
                    + "review_reason = 'left by an earlier test' "
                    + "where status = 'CANCEL_PENDING' and review_required_at is null");
            // The same for refunds: the refund job sends every refund it finds, and since a rate limit
            // stops its run, one that an earlier test left failing would be sent first and hide this test's.
            jdbc.update("update refund set status = 'NEEDS_REVIEW' where status in ('REQUESTED', 'FAILED')");
            // The log throttle is in memory and the application context is shared by every test:
            // what one test logged must not silence the line the next one looks for.
            alertThrottle.clear();
        }

        /** Local stand-ins for Razorpay, closed after each test. */
        private final List<com.sun.net.httpserver.HttpServer> razorpayStubs = new java.util.ArrayList<>();

        @org.junit.jupiter.api.AfterEach
        void stopRazorpayStubs() {
            razorpayStubs.forEach(server -> server.stop(0));
            razorpayStubs.clear();
        }

        /**
         * The real Razorpay adapter talking over HTTP to a server that answers every request with
         * this status and body, so what is under test is what Razorpay really sends and what the
         * adapter makes of it, not what a mock says it would throw.
         */
        private com.costonomy.mp.payment.provider.RazorpayPaymentProvider razorpayAnswering(int status, String body)
                throws java.io.IOException {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            razorpayStubs.add(server);
            return new com.costonomy.mp.payment.provider.RazorpayPaymentProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "rzp_test_key", "rzp_test_secret", "whsec_test_only", 4320);
        }

        /** Lookups now go to the real adapter, which is answered by {@code razorpay}. */
        private void lookupsGoTo(com.costonomy.mp.payment.provider.RazorpayPaymentProvider razorpay) {
            doAnswer(call -> razorpay.inspect(call.getArgument(0))).when(mockProvider).inspect(anyString());
        }

        private int cancelAs(Submitted submitted, boolean bySupplier) throws Exception {
            return mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + submitted.orderId()
                                    + (bySupplier ? "/supplier-cancel" : "/cancel"))
                            .header("Authorization", "Bearer "
                                    + (bySupplier ? submitted.seller().token() : submitted.buyer().token()))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))))
                    .andReturn().getResponse().getStatus();
        }

        private Map<String, Object> paymentRow(long paymentId) {
            return jdbc.queryForMap("select * from payment where id = ?", paymentId);
        }

        private String pstatus(long paymentId) {
            return String.valueOf(paymentRow(paymentId).get("status"));
        }

        private String providerPaymentId(Submitted submitted) {
            return jdbc.queryForObject("select provider_payment_id from payment where id = ?",
                    String.class, submitted.paymentId());
        }

        private List<Map<String, Object>> refundRows(Submitted submitted) {
            return jdbc.queryForList("select * from refund where payment_id = ? order by id",
                    submitted.paymentId());
        }

        private int count(String sql, Object... args) {
            return jdbc.queryForObject(sql, Integer.class, args);
        }

        private JsonNode order(Submitted submitted) throws Exception {
            return api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId())
                    .at("/data");
        }

        /** A UPI payment, held, its order confirmed and not yet ready. */
        private Submitted paidByUpi() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted, "upi");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("AUTHORIZED");
            return submitted;
        }

        /** Every row the ledger says about this payment must agree with the payment. */
        private void assertBooksAgree(Submitted submitted) {
            var payment = paymentRow(submitted.paymentId());
            BigDecimal captured = (BigDecimal) payment.get("captured_amount");
            BigDecimal refunded = (BigDecimal) payment.get("refunded_amount");
            BigDecimal completed = jdbc.queryForObject("select coalesce(sum(amount), 0) from refund "
                    + "where payment_id = ? and status = 'COMPLETED' and reason <> 'WALLET_WITHDRAWAL'",
                    BigDecimal.class, submitted.paymentId());
            assertThat(refunded).describedAs("refunded_amount is exactly the completed refunds")
                    .isEqualByComparingTo(completed);
            assertThat(refunded).isLessThanOrEqualTo(captured);
            assertThat(count("select count(*) from payment_transaction where payment_id = ? "
                    + "and transaction_type = 'CAPTURE' and status = 'SUCCESS'", submitted.paymentId()))
                    .isLessThanOrEqualTo(1);
            assertThat(count("select count(*) from refund where supplier_order_id = ? "
                    + "and idempotency_key = ?", submitted.orderId(), "cancel-order-" + submitted.orderId()))
                    .describedAs("at most one cancellation refund per order").isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a UPI order cancelled before ready is captured, then refunded to where it came from")
        void upiCancelledBeforeReadyIsCapturedAndRefundedToSource() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);

            assertThat(cancelAs(submitted, true)).isEqualTo(200);

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            var waiting = paymentRow(submitted.paymentId());
            assertThat(waiting.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(waiting.get("cancel_requested_at")).isNotNull();
            assertThat(waiting.get("provider_method")).isEqualTo("upi");
            // Nothing has reached Razorpay from the cancel itself (D-099).
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            verify(mockProvider, never()).release(anyString(), anyString());

            paymentJobs.settleCancellations();

            var captured = paymentRow(submitted.paymentId());
            assertThat(captured.get("status")).isEqualTo("CAPTURED");
            assertThat((BigDecimal) captured.get("captured_amount"))
                    .isEqualByComparingTo((BigDecimal) captured.get("authorized_amount"));
            assertThat((BigDecimal) captured.get("provider_fee"))
                    .describedAs("what Razorpay kept is recorded").isPositive();
            assertThat(jdbc.queryForList("select idempotency_key, provider_reference from payment_transaction "
                    + "where payment_id = ? and transaction_type = 'CAPTURE' and status = 'SUCCESS'",
                    submitted.paymentId()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.get("idempotency_key")).isEqualTo("cancel-capture-" + submitted.paymentId());
                        assertThat(row.get("provider_reference")).isEqualTo(pid);
                    });
            assertThat(refundRows(submitted)).singleElement().satisfies(row -> {
                assertThat(row.get("destination")).isEqualTo("ORIGINAL");
                assertThat(row.get("reason")).isEqualTo("CANCELLATION");
                assertThat(row.get("idempotency_key")).isEqualTo("cancel-order-" + submitted.orderId());
                assertThat(row.get("status")).isEqualTo("REQUESTED");
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("4000.00");
                assertThat((String) row.get("note")).contains("paid by upi");
            });

            paymentJobs.processRefunds();

            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("COMPLETED");
            var done = paymentRow(submitted.paymentId());
            assertThat(done.get("status")).isEqualTo("FULLY_REFUNDED");
            assertThat((BigDecimal) done.get("refunded_amount")).isEqualByComparingTo("4000.00");
            // The money goes back to the source, never into the wallet.
            assertThat(count("select count(*) from wallet_transaction where supplier_order_id = ?",
                    submitted.orderId())).isZero();
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            verify(mockProvider, times(1)).capture(eq(pid), any(), eq("cancel-capture-" + submitted.paymentId()));
            verify(mockProvider, times(1)).refund(eq(pid), any(), eq("mandi-refund-" + refundId), any());
            verify(mockProvider, never()).release(anyString(), anyString());
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("a card cancelled before ready is released exactly as before: no capture, no refund, no provider call")
        void cardCancelledBeforeReadyIsReleasedAsToday() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            assertThat(paymentRow(submitted.paymentId()).get("provider_method")).isEqualTo("card");
            clearInvocations(mockProvider);

            assertThat(cancelAs(submitted, true)).isEqualTo(200);

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("RELEASED");
            assertThat(row.get("release_reason")).isEqualTo("CARD_HOLD_DROPPED");
            assertThat((BigDecimal) row.get("released_amount")).isEqualByComparingTo("4000.00");
            assertThat((BigDecimal) row.get("captured_amount")).isEqualByComparingTo("0");
            assertThat(count("select count(*) from payment_transaction where payment_id = ? "
                    + "and transaction_type = 'RELEASE' and status = 'SUCCESS'", submitted.paymentId())).isEqualTo(1);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            paymentJobs.capturePending();
            assertThat(refundRows(submitted)).isEmpty();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("RELEASED");
            // Not one call to Razorpay: not in the cancel request, and not after it.
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            verify(mockProvider, never()).inspect(anyString());
            verify(mockProvider, never()).release(anyString(), anyString());
            verify(mockProvider, never()).fetchPayment(anyString());
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RELEASED");
            assertThat(order(submitted).at("/paymentInstrument").asText()).isEqualTo("card");
        }

        @Test
        @DisplayName("an unknown method is decided by asking Razorpay: a card is released")
        void unknownMethodThatIsACardIsReleased() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted, "card");
            jdbc.update("update payment set provider_method = null where id = ?", submitted.paymentId());

            assertThat(cancelAs(submitted, true)).isEqualTo(200);
            // Not knowing is not a reason to release: it waits for the answer.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("RELEASED");
            assertThat(row.get("release_reason")).isEqualTo("CARD_HOLD_DROPPED");
            assertThat(row.get("provider_method")).describedAs("learned from the live answer").isEqualTo("card");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            assertThat(refundRows(submitted)).isEmpty();
        }

        @Test
        @DisplayName("an unknown method is decided by asking Razorpay: UPI is captured and refunded")
        void unknownMethodThatIsUpiIsReturned() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted, "upi");
            jdbc.update("update payment set provider_method = null where id = ?", submitted.paymentId());

            assertThat(cancelAs(submitted, true)).isEqualTo(200);
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(paymentRow(submitted.paymentId()).get("provider_method")).isEqualTo("upi");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("netbanking, a wallet app and an unfamiliar method are returned like UPI, never left to lapse")
        void everyNonCardMethodIsReturned() throws Exception {
            for (String method : List.of("netbanking", "wallet", "emi", "paylater", "somethingnew")) {
                var submitted = submit("400", 1);
                payAndConfirm(submitted, method);

                cancelAs(submitted, true);

                assertThat(pstatus(submitted.paymentId())).describedAs(method).isEqualTo("CANCEL_PENDING");
                paymentJobs.settleCancellations();
                assertThat(pstatus(submitted.paymentId())).describedAs(method).isEqualTo("CAPTURED");
                assertThat(refundRows(submitted)).describedAs(method).hasSize(1);
            }
        }

        @Test
        @DisplayName("a restaurant cancelling a UPI order gets its money back the same way")
        void restaurantCancelOfUpiOrderReturnsMoney() throws Exception {
            var submitted = paidByUpi();

            assertThat(cancelAs(submitted, false)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select cancelled_by from supplier_order where id = ?",
                    String.class, submitted.orderId())).isEqualTo("RESTAURANT");
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(refundRows(submitted)).singleElement()
                    .satisfies(row -> assertThat(row.get("status")).isEqualTo("COMPLETED"));
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("cancelling twice and running each job twice still captures once and refunds once")
        void cancelTwiceAndJobTwiceRefundOnce() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);

            assertThat(cancelAs(submitted, true)).isEqualTo(200);
            assertThat(cancelAs(submitted, true)).isEqualTo(200);
            assertThat(cancelAs(submitted, false)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(refundRows(submitted)).hasSize(1);
            verify(mockProvider, times(1)).capture(eq(pid), any(), anyString());
            verify(mockProvider, times(1)).refund(eq(pid), any(), anyString(), any());
            assertThat(mockProvider.refundedOf(pid)).isEqualByComparingTo("4000.00");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("a capture whose answer was lost is found captured next run, and not sent again")
        void lostCaptureResponseRecovers() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            doAnswer(call -> {
                call.callRealMethod();
                throw PaymentProviderException.unreachable("answer lost", new RuntimeException());
            }).doCallRealMethod().when(mockProvider).capture(anyString(), any(), anyString());
            cancelAs(submitted, true);

            paymentJobs.settleCancellations();

            // Money was taken at Razorpay, and we do not know it yet.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_attempts")).isEqualTo(1);
            assertThat(mockProvider.inspect(pid).captured()).isTrue();
            assertThat(refundRows(submitted)).isEmpty();

            paymentJobs.settleCancellations();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_attempts")).isEqualTo(2);
            assertThat(refundRows(submitted)).hasSize(1);
            verify(mockProvider, times(1)).capture(eq(pid), any(), anyString());
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("Razorpay returning the money before the job runs is recorded, and nothing is captured")
        void providerExpiredBeforeJobReleases() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            mockProvider.expire(pid);

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("RELEASED");
            assertThat(row.get("release_reason")).isEqualTo("PROVIDER_AUTO_REFUND");
            assertThat((BigDecimal) row.get("released_amount")).isEqualByComparingTo("4000.00");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            assertThat(refundRows(submitted)).isEmpty();
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURNED");
            assertThat(order(submitted).at("/paymentInstrument").asText()).isEqualTo("upi");
        }

        @Test
        @DisplayName("a capture refused because the hold just expired converges on the provider's own return")
        void captureRefusedAfterExpiryResolves() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            doAnswer(call -> {
                mockProvider.expire(pid);
                throw new PaymentProviderException("Cannot capture a payment in state RELEASED",
                        false, "400");
            }).doCallRealMethod().when(mockProvider).capture(anyString(), any(), anyString());

            paymentJobs.settleCancellations();
            // Refused, not failed: it waits, and the next look at Razorpay decides.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(refundRows(submitted)).isEmpty();

            paymentJobs.settleCancellations();

            assertThat(paymentRow(submitted.paymentId()).get("release_reason")).isEqualTo("PROVIDER_AUTO_REFUND");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("RELEASED");
            verify(mockProvider, times(1)).capture(eq(pid), any(), anyString());
        }

        @Test
        @DisplayName("a capture that keeps failing at the gateway is retried each run and never refunded early")
        void persistentCaptureFailureKeepsRetrying() throws Exception {
            // .17 makes the mock fail every capture.
            var submitted = submit("400.17", 1);
            payAndConfirm(submitted, "upi");
            cancelAs(submitted, true);

            for (int run = 0; run < 3; run++) {
                paymentJobs.settleCancellations();
            }

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_attempts")).isEqualTo(3);
            assertThat(refundRows(submitted)).isEmpty();
            verify(mockProvider, times(3)).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a transient failure at the provider is retried until it works, then one refund")
        void transientCaptureFailureRetries() throws Exception {
            var submitted = paidByUpi();
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .doCallRealMethod().when(mockProvider).capture(anyString(), any(), anyString());
            cancelAs(submitted, true);

            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            paymentJobs.settleCancellations();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_attempts")).isEqualTo(3);
            assertThat(refundRows(submitted)).hasSize(1);
        }

        @Test
        @DisplayName("an unreachable provider changes nothing, and the payment waits for the next run")
        void unreachableInspectWaits() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNull();
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a provider capture webhook for our own cancel-capture leaves the payment to the job")
        void webhookCapturedDuringCancelPendingIsLeftToTheJob(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            // The capture happened at Razorpay, and its webhook beats our job to the row.
            mockProvider.capture(pid, new BigDecimal("4000.00"), "elsewhere");

            int webhook = postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.captured",
                    pid, submitted.providerOrderId()));

            assertThat(webhook).isEqualTo(200);
            assertThat(pstatus(submitted.paymentId())).describedAs("no CAPTURED without its refund")
                    .isEqualTo("CANCEL_PENDING");
            assertThat(refundRows(submitted)).isEmpty();
            assertThat(output.getOut()).contains("Payment " + submitted.paymentId()
                    + " is CANCEL_PENDING; WEBHOOK state CAPTURED left to the cancellation job");

            paymentJobs.settleCancellations();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(refundRows(submitted)).hasSize(1);
            // Our job never sent a capture of its own: it was already taken.
            verify(mockProvider, never()).capture(eq(pid), any(),
                    eq("cancel-capture-" + submitted.paymentId()));
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("money that arrives for a draft the restaurant cancelled is returned, and the supplier never sees it")
        void lateAuthorizationOnCancelledDraftIsReturned() throws Exception {
            var submitted = submit("400", 10);
            assertThat(cancelAs(submitted, false)).isEqualTo(200);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            var unpaid = paymentRow(submitted.paymentId());
            assertThat(unpaid.get("status")).isEqualTo("CREATED");
            assertThat(unpaid.get("cancel_requested_at")).isNotNull();

            payAndConfirm(submitted, "upi");

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertThat(count("select count(*) from outbox_event where event_type = 'SupplierOrderConfirmed' "
                    + "and aggregate_id = ?", submitted.orderId())).isZero();
            assertThat(api.get(submitted.seller().token(), "/api/v1/supplier-stores/"
                    + submitted.seller().storeId() + "/orders/pending").at("/data")).isEmpty();

            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("the sweep finds money that reached a cancelled draft when nobody told us")
        void lateAuthorizationFoundBySweep() throws Exception {
            var submitted = submit("400", 10);
            cancelAs(submitted, false);
            mockProvider.completeCheckout(submitted.providerOrderId(), "upi");
            jdbc.update("update payment set created_at = date_sub(utc_timestamp(6), interval 10 minute), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?",
                    submitted.paymentId());

            paymentJobs.reconcileStale();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("money that reached a cancelled draft already taken is refunded without a second capture")
        void capturedMoneyOnACancelledDraftIsReturned() throws Exception {
            var submitted = submit("400", 10);
            cancelAs(submitted, false);
            var paid = mockProvider.completeCheckout(submitted.providerOrderId(), "upi");
            mockProvider.capture(paid.providerPaymentId(), new BigDecimal("4000.00"), "elsewhere");

            api.post(submitted.buyer().token(), "/api/v1/payments/" + submitted.paymentId() + "/confirm",
                    Map.of("providerPaymentId", paid.providerPaymentId()));

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            verify(mockProvider, never()).capture(eq(paid.providerPaymentId()), any(),
                    eq("cancel-capture-" + submitted.paymentId()));
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("a cancelled draft nobody paid for stays unpaid, and asks the provider nothing")
        void cancelledDraftNeverPaidStaysCreated() throws Exception {
            var submitted = submit("400", 10);
            clearInvocations(mockProvider);

            cancelAs(submitted, false);
            paymentJobs.settleCancellations();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CREATED");
            verify(mockProvider, never()).inspect(anyString());
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("money that arrives after the intent expired is returned instead of left to lapse")
        void lateAuthorizationAfterIntentExpiredIsReturned(CapturedOutput output) throws Exception {
            var submitted = submit("400", 10);
            jdbc.update("update payment set created_at = date_sub(utc_timestamp(6), interval 2 day), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 2 day) where id = ?", submitted.paymentId());
            paymentJobs.reconcileStale();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FAILED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");

            var paid = mockProvider.completeCheckout(submitted.providerOrderId(), "upi");
            int webhook = postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    paid.providerPaymentId(), submitted.providerOrderId()));

            assertThat(webhook).isEqualTo(200);
            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("failure_code")).isNull();
            assertThat(output.getOut()).contains("after its intent expired");
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("a payment that failed for any other reason is never reopened by later money")
        void otherFailuresStayFailed() throws Exception {
            var submitted = submit("400", 10);
            jdbc.update("update payment set status = 'FAILED', failure_code = 'GATEWAY_ERROR' where id = ?",
                    submitted.paymentId());

            var paid = mockProvider.completeCheckout(submitted.providerOrderId(), "upi");
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    paid.providerPaymentId(), submitted.providerOrderId()));

            assertThat(pstatus(submitted.paymentId())).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("cancel against a supplier marking ready at the same moment: exactly one wins, five rounds")
        void concurrentCancelAndReadyOneWins() throws Exception {
            for (int round = 0; round < 5; round++) {
                var submitted = paidByUpi();
                assertThat(supplierStep(submitted, "preparing")).isEqualTo(200);

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(2);
                var cancel = pool.submit(() -> { start.await(); return cancelAs(submitted, true); });
                var ready = pool.submit(() -> { start.await(); return supplierStep(submitted, "ready"); });
                start.countDown();
                int cancelStatus = cancel.get(30, TimeUnit.SECONDS);
                int readyStatus = ready.get(30, TimeUnit.SECONDS);
                pool.shutdown();

                String order = orderStatus(submitted.orderId());
                String payment = pstatus(submitted.paymentId());
                String where = "round " + round + ": cancel " + cancelStatus + ", ready " + readyStatus
                        + ", order " + order + ", payment " + payment;
                if (order.equals("READY_FOR_PICKUP")) {
                    assertThat(readyStatus).describedAs(where).isEqualTo(200);
                    assertThat(cancelStatus).describedAs(where).isBetween(400, 499);
                    assertThat(payment).describedAs(where).isEqualTo("CAPTURE_PENDING");
                    assertThat(paymentRow(submitted.paymentId()).get("cancel_requested_at")).describedAs(where).isNull();
                    paymentJobs.capturePending();
                    paymentJobs.settleCancellations();
                    assertThat(pstatus(submitted.paymentId())).describedAs(where).isEqualTo("CAPTURED");
                    assertThat(refundRows(submitted)).describedAs(where).isEmpty();
                } else {
                    assertThat(order).describedAs(where).isEqualTo("CANCELLED");
                    assertThat(cancelStatus).describedAs(where).isEqualTo(200);
                    assertThat(readyStatus).describedAs(where).isBetween(400, 499);
                    assertThat(payment).describedAs(where).isEqualTo("CANCEL_PENDING");
                    paymentJobs.capturePending();
                    assertThat(pstatus(submitted.paymentId())).describedAs("the capture job leaves it alone")
                            .isEqualTo("CANCEL_PENDING");
                    paymentJobs.settleCancellations();
                    paymentJobs.processRefunds();
                    assertThat(pstatus(submitted.paymentId())).describedAs(where).isEqualTo("FULLY_REFUNDED");
                }
                assertBooksAgree(submitted);
            }
        }

        @Test
        @DisplayName("two runs settling the same payment at once capture at most twice and refund once, five rounds")
        void concurrentSettleRunsRefundOnce() throws Exception {
            for (int round = 0; round < 5; round++) {
                var submitted = paidByUpi();
                String pid = providerPaymentId(submitted);
                cancelAs(submitted, true);

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(3);
                var one = pool.submit(() -> { start.await(); cancellations.settle(submitted.paymentId()); return 1; });
                var two = pool.submit(() -> { start.await(); cancellations.settle(submitted.paymentId()); return 2; });
                var three = pool.submit(() -> { start.await(); paymentJobs.settleCancellations(); return 3; });
                start.countDown();
                one.get(30, TimeUnit.SECONDS);
                two.get(30, TimeUnit.SECONDS);
                three.get(30, TimeUnit.SECONDS);
                pool.shutdown();

                String where = "round " + round;
                assertThat(pstatus(submitted.paymentId())).describedAs(where).isEqualTo("CAPTURED");
                assertThat(refundRows(submitted)).describedAs(where).hasSize(1);
                assertThat(count("select count(*) from payment_transaction where payment_id = ? "
                        + "and transaction_type = 'CAPTURE' and status = 'SUCCESS'", submitted.paymentId()))
                        .describedAs(where).isEqualTo(1);
                assertThat(count("select count(*) from audit_log where entity_type = 'PAYMENT' and entity_id = ? "
                        + "and action = 'PAYMENT_CAPTURED'", submitted.paymentId())).describedAs(where).isEqualTo(1);
                verify(mockProvider, org.mockito.Mockito.atMost(3)).capture(eq(pid), any(), anyString());
                paymentJobs.processRefunds();
                assertThat(mockProvider.refundedOf(pid)).describedAs(where).isEqualByComparingTo("4000.00");
                assertBooksAgree(submitted);
                clearInvocations(mockProvider);
            }
        }

        @Test
        @DisplayName("N6: two threads sending the same refund at once send it to Razorpay once")
        void concurrentRefundRunsSendOnce() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            // A Razorpay slow enough that the second thread's claim arrives while the first is still
            // inside its call. Without that the first could finish before the second began, and
            // the test would prove only that a completed refund is not sent again.
            doAnswer(call -> {
                Thread.sleep(400);
                return call.callRealMethod();
            }).when(mockProvider).refund(anyString(), any(), anyString(), any());

            // Straight at RefundService.process, not through the job: the job is serialised by its
            // ShedLock, so two runs of it never overlap and would prove nothing about the claim.
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            var results = new java.util.concurrent.CopyOnWriteArrayList<String>();
            java.util.concurrent.Callable<Integer> run = () -> {
                start.await();
                try {
                    refundService.process(refundId);
                    results.add("done");
                } catch (RuntimeException lostTheClaim) {
                    // The claim is a versioned write: a run that loses it fails its save instead of sending.
                    results.add("lost the claim: " + lostTheClaim.getClass().getSimpleName());
                }
                return 1;
            };
            var a = pool.submit(run);
            var b = pool.submit(run);
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(results).hasSize(2);
            // Counted on the call itself, not on the mock's answer: the mock replays a repeated
            // key, so a refund sent twice would still show one refund. It must not be sent twice.
            verify(mockProvider, times(1)).refund(eq(pid), any(), anyString(), any());
            assertThat(mockProvider.refundedOf(pid)).isEqualByComparingTo("4000.00");
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("F9: a second run that arrives while the first is inside Razorpay's answer does not send the refund again")
        void secondRunDuringTheFirstRunsProviderCallDoesNotSend() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            var entered = new CountDownLatch(1);
            // A slow Razorpay, and a signal that the first run is inside its call.
            doAnswer(call -> {
                entered.countDown();
                Thread.sleep(500);
                return call.callRealMethod();
            }).when(mockProvider).refund(anyString(), any(), anyString(), any());

            var pool = Executors.newFixedThreadPool(2);
            var first = pool.submit(() -> { paymentJobs.processRefunds(); return 1; });
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                // Straight at the refund the first run has claimed, then a whole run.
                refundService.process(refundId);
                paymentJobs.processRefunds();
                return 2;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // Counted on the call itself: the mock replays a repeated key, so a second send
            // would leave "one refund" on its side and still be money asked for twice.
            verify(mockProvider, times(1)).refund(eq(pid), any(), anyString(), any());
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(refundRows(submitted).get(0).get("attempts")).isEqualTo(1);
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("a refund Razorpay declines goes to a person, never into the wallet")
        void cancellationRefundRejectedGoesToReview(CapturedOutput output) throws Exception {
            // .19 makes the mock decline the refund.
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            var refund = refundRows(submitted).get(0);
            assertThat(refund.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(refund.get("attempts")).describedAs("not retried blindly").isEqualTo(1);
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat((BigDecimal) paymentRow(submitted.paymentId()).get("refunded_amount")).isEqualByComparingTo("0");
            assertThat(count("select count(*) from wallet_transaction where supplier_order_id = ?",
                    submitted.orderId())).isZero();
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURN_DELAYED");
            assertThat(output.getOut()).contains("Refund " + refund.get("id") + " → NEEDS_REVIEW");
        }

        @Test
        @DisplayName("a payment that is not the order's own is never captured, and waits for a person")
        void unmatchedPaymentIsNeverCaptured(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set authorized_amount = authorized_amount + 1 where id = ?",
                    submitted.paymentId());

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("review_required_at")).isNotNull();
            assertThat((String) row.get("review_reason")).contains("does not match");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            assertThat(output.getOut()).contains("Cancellation of payment " + submitted.paymentId()
                    + " needs a person");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURN_DELAYED");
            // Skipped from now on: a payment waiting for a person is not retried in a loop.
            clearInvocations(mockProvider);
            paymentJobs.settleCancellations();
            verify(mockProvider, never()).inspect(anyString());
            assertThat(paymentRow(submitted.paymentId()).get("cancel_attempts")).isEqualTo(1);
        }

        @Test
        @DisplayName("money already refunded at Razorpay behind our back is not captured or refunded again")
        void refundedOutsideMandiGoesToReview() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            mockProvider.capture(pid, new BigDecimal("4000.00"), "elsewhere");
            mockProvider.refund(pid, new BigDecimal("100.00"), "elsewhere-refund",
                    com.costonomy.mp.payment.provider.PaymentProvider.RefundOptions.none());
            clearInvocations(mockProvider);

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat((String) row.get("review_reason")).contains("outside Mandi");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            assertThat(refundRows(submitted)).isEmpty();
        }

        @Test
        @DisplayName("a payment Razorpay says is failed for a cancelled order is stopped for a person, not moved")
        void failedAtProviderGoesToReview() throws Exception {
            var submitted = paidByUpi();
            String pid = providerPaymentId(submitted);
            cancelAs(submitted, true);
            var current = mockProvider.fetchPayment(pid);
            doReturn(new com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentFacts(pid,
                    current.providerOrderId(),
                    com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus.FAILED,
                    false, current.authorizedAmount(), BigDecimal.ZERO, "upi", null, null, null))
                    .when(mockProvider).inspect(pid);

            paymentJobs.settleCancellations();

            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNotNull();
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a payment being captured when its order is cancelled is refunded once it is captured")
        void cancelOfCapturePendingIsRefundedOnCapture(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            supplierStep(submitted, "preparing");
            supplierStep(submitted, "ready");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURE_PENDING");

            // Not reachable through the API (a ready order cannot be cancelled); the
            // funding hook itself must still leave nothing forgotten.
            funding.onOrderUnfulfilled(submitted.orderId(), "Cancelled: test");
            assertThat(output.getOut()).contains("found payment " + submitted.paymentId() + " CAPTURE_PENDING");
            paymentJobs.capturePending();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(refundRows(submitted)).singleElement().satisfies(row -> {
                assertThat(row.get("idempotency_key")).isEqualTo("cancel-order-" + submitted.orderId());
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("4000.00");
            });
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("an order captured before D-103 and then cancelled is still refunded to the wallet, once")
        void legacyCapturedCancelStillRefundsToWallet() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted, "upi");
            dispatch(submitted);
            paymentJobs.capturePending();
            jdbc.update("update supplier_order set status = 'CONFIRMED' where id = ?", submitted.orderId());

            cancelAs(submitted, true);
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            assertThat(refundRows(submitted)).singleElement().satisfies(row -> {
                assertThat(row.get("destination")).isEqualTo("WALLET");
                assertThat(row.get("idempotency_key")).isEqualTo("cancel-order-" + submitted.orderId());
            });
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.00");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_requested_at")).isNotNull();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
        }

        @Test
        @DisplayName("an authorised hold Razorpay has already returned is noticed by the sweep, and the order can't be readied")
        void heldPaymentExpiredAtProviderIsDetected(CapturedOutput output) throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted, "card");
            supplierStep(submitted, "preparing");
            mockProvider.expire(providerPaymentId(submitted));
            jdbc.update("update payment set updated_at = date_sub(utc_timestamp(6), interval 7 hour), "
                    + "reconciled_at = date_sub(utc_timestamp(6), interval 7 hour) where id = ?",
                    submitted.paymentId());

            paymentJobs.reconcileStale();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("RELEASED");
            assertThat(row.get("release_reason")).isEqualTo("PROVIDER_AUTO_REFUND");
            assertThat(output.getOut()).contains("Order " + submitted.orderId()
                    + " can no longer be paid for: the hold on payment " + submitted.paymentId() + " lapsed");
            // The goods must not leave against money that has gone back to the payer.
            assertThat(supplierStep(submitted, "ready")).isEqualTo(409);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("PREPARING");
        }

        @Test
        @DisplayName("a provider 'refunded' does not set FULLY_REFUNDED ahead of our own refunds")
        void refundWebhookDoesNotSetFullyRefundedAhead(CapturedOutput output) throws Exception {
            var captured = submit("400", 10);
            payAndConfirm(captured, "upi");
            dispatch(captured);
            paymentJobs.capturePending();
            String cpid = providerPaymentId(captured);
            var was = mockProvider.fetchPayment(cpid);
            doReturn(new com.costonomy.mp.payment.provider.PaymentProvider.ProviderPayment(cpid,
                    was.providerOrderId(),
                    com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus.REFUNDED,
                    was.authorizedAmount(), BigDecimal.ZERO, null, null, "upi", null))
                    .when(mockProvider).fetchPayment(cpid);

            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.refunded", cpid, was.providerOrderId()));

            assertThat(pstatus(captured.paymentId())).describedAs("no refund row says it is").isEqualTo("CAPTURED");
            assertThat(output.getOut()).contains("Ignoring WEBHOOK \"refunded\" for payment " + captured.paymentId());

            // And after part of it really was refunded, it is partly refunded, not fully.
            creditWallet(captured, "1000.00");
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.refunded", cpid, was.providerOrderId()));
            assertThat(pstatus(captured.paymentId())).isEqualTo("PARTIALLY_REFUNDED");
        }

        @Test
        @DisplayName("the order says returning, then refunded; a card says released, and the instrument is exposed")
        void orderShowsReturningThenRefunded() throws Exception {
            var submitted = paidByUpi();
            assertThat(order(submitted).at("/paymentInstrument").asText()).isEqualTo("upi");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("AUTHORIZED");

            cancelAs(submitted, true);
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURNING");
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(orderPaymentStatus(submitted)).describedAs("captured, refund still open").isEqualTo("RETURNING");
            paymentJobs.processRefunds();
            assertThat(orderPaymentStatus(submitted)).isEqualTo("FULLY_REFUNDED");
            assertThat(order(submitted).at("/paymentInstrument").asText()).isEqualTo("upi");
        }

        @Test
        @DisplayName("a paid order that was not cancelled shows its instrument and its plain status")
        void instrumentIsExposedOnAnyPaidOrder() throws Exception {
            var submitted = submit("400", 1);
            assertThat(order(submitted).at("/paymentInstrument").isNull()).isTrue();
            payAndConfirm(submitted, "netbanking");
            assertThat(order(submitted).at("/paymentInstrument").asText()).isEqualTo("netbanking");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("a hold is usable for the provider's limit less six hours: 66 hours of three days")
        void holdLimitDrivesTheGuard() throws Exception {
            var late = submit("400", 1);
            payAndConfirm(late);
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), interval 67 hour) "
                    + "where id = ?", late.paymentId());
            supplierStep(late, "preparing");
            assertThat(supplierStep(late, "ready")).isEqualTo(409);

            var inTime = submit("400", 1);
            payAndConfirm(inTime);
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), interval 65 hour) "
                    + "where id = ?", inTime.paymentId());
            supplierStep(inTime, "preparing");
            assertThat(supplierStep(inTime, "ready")).isEqualTo(200);
        }

        @Test
        @DisplayName("the refund carries our receipt, notes and speed, and the default speed is normal")
        void refundCarriesReceiptNotesAndSpeed() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            var options = org.mockito.ArgumentCaptor.forClass(
                    com.costonomy.mp.payment.provider.PaymentProvider.RefundOptions.class);

            paymentJobs.processRefunds();

            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            verify(mockProvider).refund(anyString(), any(), eq("mandi-refund-" + refundId), options.capture());
            assertThat(options.getValue().receipt()).isEqualTo("mandi-refund-" + refundId);
            assertThat(options.getValue().receipt().length()).isLessThanOrEqualTo(40);
            assertThat(options.getValue().notes())
                    .containsEntry("mandi_refund_id", String.valueOf(refundId))
                    .containsEntry("mandi_payment_id", String.valueOf(submitted.paymentId()))
                    .containsEntry("purpose", "cancellation");
            assertThat(options.getValue().speed()).isEqualTo("normal");
        }

        @Test
        @DisplayName("instant refund, when the owner switches it on, applies to cancellations and never to withdrawals")
        void instantRefundIsAConfigurableChoice() throws Exception {
            var target = org.springframework.test.util.AopTestUtils.getTargetObject(cancelRefundSpeed);
            org.springframework.test.util.ReflectionTestUtils.setField(target, "speed", "optimum");
            try {
                var submitted = paidByUpi();
                cancelAs(submitted, true);
                paymentJobs.settleCancellations();
                paymentJobs.processRefunds();
                var options = org.mockito.ArgumentCaptor.forClass(
                        com.costonomy.mp.payment.provider.PaymentProvider.RefundOptions.class);
                verify(mockProvider, times(1)).refund(anyString(), any(), anyString(), options.capture());
                assertThat(options.getValue().speed()).isEqualTo("optimum");

                clearInvocations(mockProvider);
                var card = submit("400", 1);
                payAndConfirm(card);
                dispatch(card);
                paymentJobs.capturePending();
                refundToCard(card, "10.00");
                paymentJobs.processRefunds();
                var withdrawal = org.mockito.ArgumentCaptor.forClass(
                        com.costonomy.mp.payment.provider.PaymentProvider.RefundOptions.class);
                verify(mockProvider, times(1)).refund(anyString(), any(), anyString(), withdrawal.capture());
                assertThat(withdrawal.getValue().speed()).isEqualTo("normal");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(target, "speed", "normal");
            }
        }

        @Test
        @DisplayName("the restaurant is told when the refund starts and when it lands, with the order and the words for it")
        void restaurantIsNotifiedOfTheRefund() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            String orderNumber = jdbc.queryForObject("select order_number from supplier_order where id = ?",
                    String.class, submitted.orderId());

            // This order's own refund events, handed to the relay as the outbox would. The
            // drain is not used: it takes the oldest hundred of every test's events, and by
            // this point in a full run this test's are well behind them.
            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            for (var event : jdbc.queryForList("select event_id, event_type, aggregate_type, aggregate_id, "
                    + "payload_version, payload, actor_id, correlation_id, occurred_at from outbox_event "
                    + "where aggregate_type = 'REFUND' and aggregate_id = ? order by id", refundId)) {
                relay.publish(new OutboxPublisher.DomainEventEnvelope(
                        (String) event.get("event_id"), (String) event.get("event_type"),
                        (String) event.get("aggregate_type"), ((Number) event.get("aggregate_id")).longValue(),
                        ((Number) event.get("payload_version")).intValue(), String.valueOf(event.get("payload")),
                        null, (String) event.get("correlation_id"), Instant.now()));
            }

            var started = jdbc.queryForList("select body, target_type from notification "
                    + "where target_id = ? and event_type = 'RefundRequested'", submitted.orderId());
            assertThat(started).isNotEmpty();
            assertThat((String) started.get(0).get("body"))
                    .contains("Refund started:").contains("for order " + orderNumber)
                    .contains("is on its way back to the account you paid from (5–7 working days).");
            var done = jdbc.queryForList("select body from notification "
                    + "where target_id = ? and event_type = 'RefundCompleted'", submitted.orderId());
            assertThat(done).isNotEmpty();
            assertThat((String) done.get(0).get("body")).contains("for order " + orderNumber
                    + " has been refunded to the account you paid from.");
        }

        @Test
        @DisplayName("a cancellation still waiting after fifteen minutes is an error line, and only then")
        void cancelPendingAlertAfterFifteenMinutes(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();
            assertThat(output.getOut()).doesNotContain("Payment " + submitted.paymentId() + " still CANCEL_PENDING");

            // Both dates: it began waiting at the later of the two (F7), and a real cancel is
            // always after the authorisation it cancels.
            jdbc.update("update payment set cancel_requested_at = date_sub(utc_timestamp(6), interval 20 minute), "
                    + "authorized_at = date_sub(utc_timestamp(6), interval 30 minute) where id = ?",
                    submitted.paymentId());
            paymentJobs.settleCancellations();

            // Minutes are the job's clock against the database's, so a second of skew
            // reads 19: the line is what matters, not the rounding.
            assertThat(output.getOut()).containsPattern("Payment " + submitted.paymentId()
                    + " still CANCEL_PENDING after (19|20|21) min \\(2 attempts\\)");
        }

        @Test
        @DisplayName("a CANCEL_PENDING payment past the provider's own hold limit is an error: it should be gone")
        void cancelPendingPastTheHoldLimitIsAnError(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), interval 80 hour) "
                    + "where id = ?", submitted.paymentId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            assertThat(output.getOut()).contains("Payment " + submitted.paymentId()
                    + " is still CANCEL_PENDING past the provider's hold limit of 72 hours");
        }

        @Test
        @DisplayName("the whole story of a cancelled UPI order reads from the logs, with its ids and the job's run id")
        void cancelStoryIsTraceable(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            long pid = submitted.paymentId();
            cancelAs(submitted, true);

            // Through the real scheduler, so each run gets its job id the way production does.
            taskScheduler.schedule(new ScheduledMethodRunnable(paymentJobs,
                    PaymentJobs.class.getMethod("settleCancellations")), Instant.now()).get(30, TimeUnit.SECONDS);
            taskScheduler.schedule(new ScheduledMethodRunnable(paymentJobs,
                    PaymentJobs.class.getMethod("processRefunds")), Instant.now()).get(30, TimeUnit.SECONDS);

            String log = output.getOut();
            assertThat(lineWith(log, "Payment " + pid + " AUTHORIZED → CANCEL_PENDING"))
                    .contains(" payment=" + pid + " ").contains(" order=" + submitted.orderId() + " ");
            assertThat(lineWith(log, "Capturing payment " + pid + " to return it"))
                    .containsPattern("\\[costonomy-mp-api,job-settleCancellations-[0-9a-f]{8}\\]")
                    .contains(" payment=" + pid + " ");
            assertThat(lineWith(log, "Payment " + pid + " CANCEL_PENDING → CAPTURED; refund"))
                    .contains(" payment=" + pid + " ").contains("to the original upi");
            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            assertThat(lineWith(log, "Refund " + refundId + " PROCESSING → COMPLETED"))
                    .contains(" refund=" + refundId + " ");

            var audit = jdbc.queryForList("select action, request_id from audit_log "
                    + "where entity_type = 'PAYMENT' and entity_id = ? order by id", pid);
            assertThat(audit).extracting(row -> row.get("action"))
                    .containsSubsequence("PAYMENT_AUTHORIZED", "PAYMENT_CANCEL_PENDING", "PAYMENT_CAPTURED");
            assertThat(audit.stream().filter(r -> "PAYMENT_CAPTURED".equals(r.get("action")))
                    .map(r -> String.valueOf(r.get("request_id"))).findFirst().orElseThrow())
                    .startsWith("job-settleCancellations-");
        }

        // ── Review of the cancel refund: F1 to F9 ────────────────────────────

        /** This refund's own outbox events, handed to the relay as the outbox would. */
        private void relayRefundEvents(long refundId) {
            for (var event : jdbc.queryForList("select event_id, event_type, aggregate_type, aggregate_id, "
                    + "payload_version, payload, actor_id, correlation_id, occurred_at from outbox_event "
                    + "where aggregate_type = 'REFUND' and aggregate_id = ? order by id", refundId)) {
                relay.publish(new OutboxPublisher.DomainEventEnvelope(
                        (String) event.get("event_id"), (String) event.get("event_type"),
                        (String) event.get("aggregate_type"), ((Number) event.get("aggregate_id")).longValue(),
                        ((Number) event.get("payload_version")).intValue(), String.valueOf(event.get("payload")),
                        null, (String) event.get("correlation_id"), Instant.now()));
            }
        }

        private int occurrences(String text, String needle) {
            int found = 0;
            for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
                found++;
            }
            return found;
        }

        private String operator(String roleCode) throws Exception {
            String phone = ApiClient.freshPhone();
            String token = api.login(phone);
            Long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
            jdbc.update("""
                    insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                           granted_at, created_at, updated_at, version)
                    select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                      from role r where r.code = ?
                    """, userId, roleCode);
            return token;
        }

        // F1: a payment captured only to be refunded is never "funded"

        @Test
        @DisplayName("F1: a draft paid late after its intent expired is never released or made ready while its money is returned")
        void draftPaidLateAfterExpiryIsNeverReleasedWhileItsMoneyIsReturned() throws Exception {
            var submitted = submit("400", 10);
            // The sweep expired the intent, and the abandon that follows it never ran (a
            // crash, or an error the sweep logs and moves on from): payment FAILED, order DRAFT.
            jdbc.update("update payment set status = 'FAILED', failure_code = 'INTENT_EXPIRED' where id = ?",
                    submitted.paymentId());
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            // The payer finishes a UPI checkout late, and the app confirms.
            var confirmed = payAndConfirm(submitted, "upi");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(confirmed.get("fundsSecured").asBoolean()).isFalse();
            assertThat(orderStatus(submitted.orderId()))
                    .describedAs("confirm ends the draft, as the webhook and the sweep do").isEqualTo("CANCELLED");

            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            // Razorpay's payment.captured for our own capture arrives.
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.captured",
                    providerPaymentId(submitted), submitted.providerOrderId()));

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertThat(supplierStep(submitted, "preparing")).isNotEqualTo(200);
            assertThat(supplierStep(submitted, "ready")).isNotEqualTo(200);
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            assertThat(count("select count(*) from outbox_event where event_type = 'SupplierOrderConfirmed' "
                    + "and aggregate_id = ?", submitted.orderId())).isZero();
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("F1: even a draft that was never abandoned is not released by the webhook for our own capture, and cannot take funds")
        void webhookForOurOwnCaptureDoesNotReleaseADraft() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            // The abandon of a draft that never ran, as a crash would leave it.
            jdbc.update("update supplier_order set status = 'DRAFT' where id = ?", submitted.orderId());

            assertThat(funding.isFundingSecured(submitted.orderId()))
                    .describedAs("money captured only to be refunded funds nothing").isFalse();
            assertThat(funding.canTakeFunds(submitted.orderId()))
                    .describedAs("and nothing may be handed over against it").isFalse();
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.captured",
                    providerPaymentId(submitted), submitted.providerOrderId()));

            assertThat(orderStatus(submitted.orderId())).isIn("DRAFT", "CANCELLED");
        }

        @Test
        @DisplayName("F1: the payment API does not say funds are secured for money being returned")
        void paymentApiDoesNotReportReturnedMoneyAsSecured() throws Exception {
            var submitted = paidByUpi();
            assertThat(api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId()
                    + "/payment-intent").at("/data/fundsSecured").asBoolean()).isTrue();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");

            assertThat(api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId()
                    + "/payment-intent").at("/data/fundsSecured").asBoolean()).isFalse();
            assertThat(api.get(submitted.buyer().token(), "/api/v1/payments/" + submitted.paymentId())
                    .at("/data/fundsSecured").asBoolean()).isFalse();
        }

        // F2: only a 404 sends a payment to a person, and a person's list has an exit

        private void assertLookupWaits(PaymentProviderException failure, CapturedOutput output,
                                       String logged) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(failure).when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("review_required_at")).describedAs("not stopped for a person").isNull();
            assertThat(row.get("review_reason")).isNull();
            assertThat(output.getOut()).contains(logged);
            verify(mockProvider, never()).capture(anyString(), any(), anyString());

            // Razorpay recovers: the very next run does the job.
            reset(mockProvider);
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("F2: a rate-limited lookup (429) waits for the next run: no review, a warning, then it settles")
        void rateLimitedLookupWaits(CapturedOutput output) throws Exception {
            assertLookupWaits(new PaymentProviderException(
                    "Razorpay refused the lookup: 429 TOO_MANY_REQUESTS", false, "429"), output, "rate limiting");
        }

        @Test
        @DisplayName("F2: refused credentials (401) wait, and are an error line, not a review")
        void refusedCredentialsWait(CapturedOutput output) throws Exception {
            assertLookupWaits(new PaymentProviderException(
                    "Razorpay refused the lookup: 401 UNAUTHORIZED", false, "401"), output,
                    "refused our credentials");
            assertThat(output.getOut()).containsPattern("ERROR.*refused our credentials");
        }

        @Test
        @DisplayName("F2: a forbidden lookup (403) waits like a refused credential")
        void forbiddenLookupWaits(CapturedOutput output) throws Exception {
            assertLookupWaits(new PaymentProviderException(
                    "Razorpay refused the lookup: 403 FORBIDDEN", false, "403"), output,
                    "refused our credentials");
        }

        @Test
        @DisplayName("F2: a Razorpay outage (5xx) waits with no state change")
        void serverErrorLookupWaits(CapturedOutput output) throws Exception {
            assertLookupWaits(new PaymentProviderException(
                    "Razorpay is unavailable: 503 SERVICE_UNAVAILABLE", true, "503"), output,
                    "waiting on provider");
        }

        @Test
        @DisplayName("F2: any other refusal of the lookup (400) is not proof the payment is unknown: it waits too")
        void otherClientErrorLookupWaits(CapturedOutput output) throws Exception {
            assertLookupWaits(new PaymentProviderException(
                    "Razorpay refused the lookup: 400 BAD_REQUEST", false, "400"), output, "waiting");
        }

        @Test
        @DisplayName("F2: a lookup Razorpay answers 404 (it does not know the payment) is stopped for a person")
        void notFoundLookupGoesToReview(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(new PaymentProviderException("Razorpay refused the lookup: 404 NOT_FOUND", false, "404"))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("review_required_at")).isNotNull();
            assertThat((String) row.get("review_reason")).contains("404");
            assertThat(output.getOut()).contains("Cancellation of payment " + submitted.paymentId()
                    + " needs a person");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("F2: a rate limit stops the rest of the run: one call, not one per waiting payment")
        void rateLimitStopsTheBatch() throws Exception {
            var first = paidByUpi();
            var second = paidByUpi();
            cancelAs(first, true);
            cancelAs(second, true);
            doThrow(new PaymentProviderException("Razorpay refused the lookup: 429", false, "429"))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            verify(mockProvider, times(1)).inspect(anyString());
            assertThat(paymentRow(first.paymentId()).get("review_required_at")).isNull();
            assertThat(paymentRow(second.paymentId()).get("review_required_at")).isNull();
            reset(mockProvider);
            paymentJobs.settleCancellations();
            assertThat(pstatus(first.paymentId())).isEqualTo("CAPTURED");
            assertThat(pstatus(second.paymentId())).isEqualTo("CAPTURED");
        }

        /** A payment stopped for a person on a 404 that Razorpay then turned out to know. */
        private Submitted reviewedAfterANotFound() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(new PaymentProviderException("Razorpay refused the lookup: 404 NOT_FOUND", false, "404"))
                    .when(mockProvider).inspect(anyString());
            paymentJobs.settleCancellations();
            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNotNull();
            reset(mockProvider);
            return submitted;
        }

        private void reviewedLongAgo(Submitted submitted) {
            jdbc.update("update payment set review_required_at = date_sub(utc_timestamp(6), interval 5 hour), "
                    + "reconciled_at = date_sub(utc_timestamp(6), interval 5 hour) where id = ?",
                    submitted.paymentId());
        }

        @Test
        @DisplayName("F2: a payment in review that Razorpay then returned itself reaches RELEASED on a slow re-check")
        void reviewedPaymentRefundedByRazorpayReachesReleased() throws Exception {
            var submitted = reviewedAfterANotFound();
            mockProvider.expire(providerPaymentId(submitted));

            // Not asked again straight away: a payment waiting for a person is not polled in a loop.
            clearInvocations(mockProvider);
            paymentJobs.settleCancellations();
            verify(mockProvider, never()).inspect(anyString());
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");

            reviewedLongAgo(submitted);
            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("RELEASED");
            assertThat(row.get("release_reason")).isEqualTo("PROVIDER_AUTO_REFUND");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURNED");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("F2: a payment in review that turns out captured at Razorpay, and matches, is refunded")
        void reviewedPaymentCapturedAtRazorpayIsRefunded() throws Exception {
            var submitted = reviewedAfterANotFound();
            mockProvider.capture(providerPaymentId(submitted), new BigDecimal("4000.00"), "dashboard");
            reviewedLongAgo(submitted);

            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("F2: a re-check changes nothing that is not final: an authorised payment stays with the person, uncaptured")
        void reviewRecheckNeverCapturesOnAGuess() throws Exception {
            var submitted = reviewedAfterANotFound();
            reviewedLongAgo(submitted);

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("review_required_at")).isNotNull();
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
            // And it was noted as asked, so the next re-check is hours away, not seconds.
            clearInvocations(mockProvider);
            paymentJobs.settleCancellations();
            verify(mockProvider, never()).inspect(anyString());
        }

        @Test
        @DisplayName("F2: a payment that does not match the order stays in review however often it is re-checked")
        void mismatchedPaymentStaysInReview() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set authorized_amount = authorized_amount + 1 where id = ?",
                    submitted.paymentId());
            paymentJobs.settleCancellations();
            reviewedLongAgo(submitted);

            paymentJobs.settleCancellations();

            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNotNull();
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("F2: every payment still in review is an error line once a day, not every run")
        void reviewIsAnErrorOncePerDay(CapturedOutput output) throws Exception {
            var submitted = reviewedAfterANotFound();
            reviewedLongAgo(submitted);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();

            String line = "Payment " + submitted.paymentId() + " has been waiting for a person";
            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        @Test
        @DisplayName("F2: ops clears the review flag with a reason; it is audited and the job picks the payment up again")
        void opsClearsTheReviewFlag() throws Exception {
            var submitted = reviewedAfterANotFound();
            String finance = operator("OPS_FINANCE");

            api.post(finance, "/api/v1/admin/payments/" + submitted.paymentId() + "/clear-review",
                    Map.of("reason", "Checked in the Razorpay dashboard: it is ours"));

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("review_required_at")).isNull();
            assertThat(row.get("review_reason")).isNull();
            var audit = jdbc.queryForList("select actor_id, reason from audit_log where entity_type = 'PAYMENT' "
                    + "and entity_id = ? and action = 'PAYMENT_REVIEW_CLEARED'", submitted.paymentId());
            assertThat(audit).singleElement().satisfies(entry -> {
                assertThat(entry.get("actor_id")).isNotNull();
                assertThat((String) entry.get("reason")).contains("Checked in the Razorpay dashboard");
            });
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("F2: clearing needs the permission and a reason, and only a payment that is in review")
        void clearingReviewIsGuarded() throws Exception {
            var submitted = reviewedAfterANotFound();
            String path = "/api/v1/admin/payments/" + submitted.paymentId() + "/clear-review";

            assertThat(api.postStatus(submitted.buyer().token(), path, Map.of("reason", "me"))).isEqualTo(403);
            assertThat(api.postStatus(operator("OPS_SUPPORT"), path, Map.of("reason", "read only"))).isEqualTo(403);
            String finance = operator("OPS_FINANCE");
            assertThat(api.postStatus(finance, path, Map.of())).isEqualTo(400);
            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNotNull();

            assertThat(api.postStatus(finance, path, Map.of("reason", "ok"))).isEqualTo(200);
            // Nothing left to clear.
            assertThat(api.postStatus(finance, path, Map.of("reason", "again"))).isBetween(400, 499);
            var fine = paidByUpi();
            assertThat(api.postStatus(finance, "/api/v1/admin/payments/" + fine.paymentId() + "/clear-review",
                    Map.of("reason", "not in review"))).isBetween(400, 499);
        }

        @Test
        @DisplayName("F2: a rate-limited (429) cancellation refund simply retries, past the attempt limit, and never goes to review")
        void rateLimitedRefundRetries() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 429 TOO_MANY_REQUESTS",
                    false, "429")).when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 7; run++) {
                paymentJobs.processRefunds();
            }

            var refund = refundRows(submitted).get(0);
            assertThat(refund.get("status")).describedAs("never NEEDS_REVIEW for a rate limit").isEqualTo("FAILED");
            // Not counted: a send refused for a reason that is not the refund's is not an attempt (N2).
            assertThat(((Number) refund.get("attempts")).intValue()).isZero();
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("F2: a cancellation refund Razorpay refuses our credentials for waits with an error line, never goes to review")
        void refundWithRefusedCredentialsWaits(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 401 UNAUTHORIZED", false, "401"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 6; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("FAILED");
            assertThat(output.getOut()).containsPattern("ERROR.*refused our credentials sending refund");
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        // ── Second review: N1 to N6 ──────────────────────────────────────────

        /** Razorpay's answer to GET /v1/payments/{id} for an id it does not know: HTTP 400, not 404. */
        private static final String UNKNOWN_ID_BODY = """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist",\
                "source":"business","step":"payment_initiation","reason":"input_validation_failed","metadata":{}}}""";

        /** A payment Razorpay answers 400 "the id does not exist" for, already stopped for a person. */
        private Submitted reviewedAfterAnUnknownId() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            lookupsGoTo(razorpayAnswering(400, UNKNOWN_ID_BODY));
            paymentJobs.settleCancellations();
            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNotNull();
            reset(mockProvider);
            return submitted;
        }

        @Test
        @DisplayName("N1: a payment Razorpay answers 400 \"the id does not exist\" goes to a person and the order shows RETURN_DELAYED")
        void unknownIdAnswered400GoesToReview(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            // The real adapter over real HTTP: what Razorpay sends, not what a mock would throw.
            lookupsGoTo(razorpayAnswering(400, UNKNOWN_ID_BODY));

            paymentJobs.settleCancellations();

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("review_required_at")).describedAs("stopped for a person").isNotNull();
            assertThat((String) row.get("review_reason")).contains("does not know");
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURN_DELAYED");
            assertThat(output.getOut()).containsPattern("ERROR.*Cancellation of payment "
                    + submitted.paymentId() + " needs a person");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());

            // And no longer asked about every fifteen seconds for ever.
            clearInvocations(mockProvider);
            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();
            verify(mockProvider, never()).inspect(anyString());
        }

        @Test
        @DisplayName("N1: a payment stopped on the 400 is re-checked after three hours and ends when Razorpay's answer is final")
        void unknownIdReviewIsRecheckedAndCanEnd() throws Exception {
            var submitted = reviewedAfterAnUnknownId();
            // Razorpay knows the payment after all (a key mix-up fixed), and returned it itself.
            mockProvider.expire(providerPaymentId(submitted));
            clearInvocations(mockProvider);
            paymentJobs.settleCancellations();
            verify(mockProvider, never()).inspect(anyString());

            reviewedLongAgo(submitted);
            paymentJobs.settleCancellations();

            assertThat(paymentRow(submitted.paymentId()).get("status")).isEqualTo("RELEASED");
            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNull();
            assertThat(orderPaymentStatus(submitted)).isEqualTo("RETURNED");
            verify(mockProvider, never()).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("N1: a payment stopped on the 400 can be cleared by operations and is then settled")
        void unknownIdReviewCanBeCleared() throws Exception {
            var submitted = reviewedAfterAnUnknownId();
            String finance = operator("OPS_FINANCE");

            assertThat(api.postStatus(finance, "/api/v1/admin/payments/" + submitted.paymentId()
                    + "/clear-review", Map.of("reason", "Right account now; checked in the dashboard"))).isEqualTo(200);

            assertThat(paymentRow(submitted.paymentId()).get("review_required_at")).isNull();
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("N1: any other 400 keeps waiting, is not stopped for a person, and its warning is once an hour")
        void otherBadRequestWaitsWithAThrottledWarning(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            lookupsGoTo(razorpayAnswering(400, """
                    {"error":{"code":"BAD_REQUEST_ERROR","description":"The requested URL was not found on the server"}}"""));

            for (int run = 0; run < 10; run++) {
                paymentJobs.settleCancellations();
            }

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("status")).isEqualTo("CANCEL_PENDING");
            assertThat(row.get("review_required_at")).describedAs("not stopped for a person").isNull();
            // Still asked every run: it waits, it is not forgotten.
            verify(mockProvider, times(10)).inspect(anyString());
            assertThat(occurrences(output.getOut(), "Cancellation of payment " + submitted.paymentId()
                    + " waiting on provider")).describedAs("once, not once per run").isEqualTo(1);
            reset(mockProvider);
            paymentJobs.settleCancellations();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("N2: five rate-limited sends then one 503 leave the refund FAILED, then it completes, never NEEDS_REVIEW")
        void rateLimitsDoNotUseUpTheRetriesOfALaterOutage() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 429", false, "429"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            for (int run = 0; run < 5; run++) {
                paymentJobs.processRefunds();
            }
            doThrow(new PaymentProviderException("Razorpay is unavailable: 503", true, "503"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            paymentJobs.processRefunds();

            var refund = refundRows(submitted).get(0);
            assertThat(refund.get("status")).describedAs("a busy minute must not strand the refund")
                    .isEqualTo("FAILED");
            assertThat(((Number) refund.get("attempts")).intValue()).isEqualTo(1);
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertBooksAgree(submitted);
        }

        @Test
        @DisplayName("N2: refused credentials (401) do not count toward the retry limit either")
        void refusedCredentialsDoNotUseUpTheRetries() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 401", false, "401"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            for (int run = 0; run < 5; run++) {
                paymentJobs.processRefunds();
            }
            doThrow(new PaymentProviderException("Razorpay is unavailable: 503", true, "503"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            paymentJobs.processRefunds();

            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("N3: a cancelled draft's payment is not payable and the checkout key is not handed out")
        void cancelledDraftIsNotPayable() throws Exception {
            var submitted = submit("400", 1);
            assertThat(cancelAs(submitted, false)).isEqualTo(200);
            // Nothing was paid, so the payment is still CREATED: this is the case where a pay screen,
            // an older app or a deep link would open checkout on an order that no longer exists.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CREATED");
            assertThat(paymentRow(submitted.paymentId()).get("cancel_requested_at")).isNotNull();

            var intent = api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId()
                    + "/payment-intent").at("/data");

            assertThat(intent.at("/payable").asBoolean()).isFalse();
            assertThat(intent.at("/publicKey").isNull()).isTrue();
            assertThat(intent.at("/fundsSecured").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("N4: a rate limit on a refund stops the whole run: one call, not one per waiting refund")
        void rateLimitStopsTheRefundRun(CapturedOutput output) throws Exception {
            var first = paidByUpi();
            var second = paidByUpi();
            for (var submitted : List.of(first, second)) {
                cancelAs(submitted, true);
            }
            paymentJobs.settleCancellations();
            assertThat(refundRows(first)).hasSize(1);
            assertThat(refundRows(second)).hasSize(1);
            clearInvocations(mockProvider);
            doThrow(new PaymentProviderException("Razorpay rejected the request: 429", false, "429"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            paymentJobs.processRefunds();
            verify(mockProvider, times(1)).refund(anyString(), any(), anyString(), any());
            paymentJobs.processRefunds();
            paymentJobs.processRefunds();
            verify(mockProvider, times(3)).refund(anyString(), any(), anyString(), any());

            // The warning is once per interval, not once per run.
            assertThat(occurrences(output.getOut(), "Refund run stopped after refund")).isEqualTo(1);
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(pstatus(first.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(pstatus(second.paymentId())).isEqualTo("FULLY_REFUNDED");
        }

        @Test
        @DisplayName("N4: a refund FAILED for more than an hour is an error, once an hour, even while the run keeps stopping")
        void failedRefundIsAnErrorOncePerHour(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 429", false, "429"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            String line = "Refund " + refundId + " of payment " + submitted.paymentId() + " has been FAILED";

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();
            assertThat(refundRows(submitted).get(0).get("status")).isEqualTo("FAILED");
            assertThat(occurrences(output.getOut(), line)).describedAs("not yet an hour").isZero();

            jdbc.update("update refund set created_at = date_sub(utc_timestamp(6), interval 2 hour) where id = ?",
                    refundId);
            for (int run = 0; run < 3; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(occurrences(output.getOut(), line)).describedAs("once, not once per run").isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        @Test
        @DisplayName("N5: refused credentials on the lookup are one error an interval, not one per run")
        void credentialsErrorOnTheLookupIsThrottled(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            doThrow(new PaymentProviderException("Razorpay refused the lookup: 401 UNAUTHORIZED", false, "401"))
                    .when(mockProvider).inspect(anyString());

            for (int run = 0; run < 5; run++) {
                paymentJobs.settleCancellations();
            }

            String line = "refused our credentials on the lookup of payment " + submitted.paymentId();
            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
            assertThat(occurrences(output.getOut(), "Cancellation run stopped after payment "
                    + submitted.paymentId())).describedAs("the stop is one warning an interval too").isEqualTo(1);
            // Every run still asked: the throttle silences the line, not the work.
            verify(mockProvider, times(5)).inspect(anyString());
        }

        @Test
        @DisplayName("N5: refused credentials on a refund send are one error an interval, not one per run")
        void credentialsErrorOnARefundIsThrottled(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            paymentJobs.settleCancellations();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 401", false, "401"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 5; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(occurrences(output.getOut(), "refused our credentials sending refund")).isEqualTo(1);
            verify(mockProvider, times(5)).refund(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("N5: a run that stops on a rate limit still writes the reminder about payments waiting for a person")
        void remindersSurviveARateLimit(CapturedOutput output) throws Exception {
            var waiting = reviewedAfterANotFound();
            reviewedLongAgo(waiting);
            var pending = paidByUpi();
            cancelAs(pending, true);
            clearInvocations(mockProvider);
            doThrow(new PaymentProviderException("Razorpay refused the lookup: 429", false, "429"))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();

            String line = "Payment " + waiting.paymentId() + " has been waiting for a person";
            assertThat(occurrences(output.getOut(), line)).describedAs("daily, and not silenced by the outage")
                    .isEqualTo(1);
            // The reminder needs no call: only the one payment being settled was asked about.
            verify(mockProvider, times(2)).inspect(anyString());
        }

        @Test
        @DisplayName("N5: a run that stops on its time budget still writes the reminder about payments waiting for a person")
        void remindersSurviveASpentBudget(CapturedOutput output) throws Exception {
            var waiting = reviewedAfterANotFound();
            reviewedLongAgo(waiting);
            for (int n = 0; n < 2; n++) {
                cancelAs(paidByUpi(), true);
            }
            Object jobs = org.springframework.test.util.AopTestUtils.getTargetObject(paymentJobs);
            var before = org.springframework.test.util.ReflectionTestUtils.getField(jobs, "cancelBudget");
            org.springframework.test.util.ReflectionTestUtils.setField(jobs, "cancelBudget", java.time.Duration.ZERO);
            try {
                paymentJobs.settleCancellations();
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(jobs, "cancelBudget", before);
            }

            assertThat(output.getOut()).contains("Cancellation run stopped at its time budget");
            assertThat(occurrences(output.getOut(), "Payment " + waiting.paymentId()
                    + " has been waiting for a person")).isEqualTo(1);
        }

        // ── Third review: R1 to R4 ───────────────────────────────────────────

        private static final String RAZORPAY_429 =
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Too many requests\"}}";

        /** A payment that is ready and waiting to be captured, with every other pending capture put aside. */
        private Submitted readyToCapture() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURE_PENDING");
            // The capture job takes every CAPTURE_PENDING payment in the shared database, and a
            // stop on one ends the run: one an earlier test left would hide this test's calls.
            jdbc.update("update payment set status = 'FAILED' where status = 'CAPTURE_PENDING' and id <> ?",
                    submitted.paymentId());
            return submitted;
        }

        /** Capture goes to the real Razorpay adapter, which is answered by {@code razorpay}. */
        private void capturesGoTo(com.costonomy.mp.payment.provider.RazorpayPaymentProvider razorpay) {
            doAnswer(call -> razorpay.capture(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                    .when(mockProvider).capture(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("R1: a 429 on the capture at ready leaves the payment CAPTURE_PENDING, and the next run captures it")
        void captureRateLimitedOnceThenSucceeds() throws Exception {
            var submitted = readyToCapture();
            capturesGoTo(razorpayAnswering(429, RAZORPAY_429));

            paymentJobs.capturePending();

            // It used to be FAILED, which nothing reads again: the hold lapsed, the goods had gone.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURE_PENDING");
            assertThat(paymentRow(submitted.paymentId()).get("failure_code")).isNull();
            assertThat(orderStatus(submitted.orderId())).isEqualTo("READY_FOR_PICKUP");
            reset(mockProvider);

            paymentJobs.capturePending();

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            assertThat(decimal(submitted.paymentId(), "captured_amount")).isEqualByComparingTo("400.00");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("READY_FOR_PICKUP");
        }

        @Test
        @DisplayName("R1: a rate limit stops the capture run: one call, not one per waiting payment; one warning an interval")
        void captureRateLimitStopsTheRun(CapturedOutput output) throws Exception {
            var first = readyToCapture();
            var second = submit("400", 1);
            payAndConfirm(second);
            dispatch(second);
            jdbc.update("update payment set status = 'FAILED' where status = 'CAPTURE_PENDING' and id not in (?, ?)",
                    first.paymentId(), second.paymentId());
            capturesGoTo(razorpayAnswering(429, RAZORPAY_429));

            paymentJobs.capturePending();
            paymentJobs.capturePending();

            verify(mockProvider, times(2)).capture(anyString(), any(), anyString());
            assertThat(occurrences(output.getOut(), "Capture run stopped after payment")).isEqualTo(1);
            assertThat(pstatus(first.paymentId())).isEqualTo("CAPTURE_PENDING");
            assertThat(pstatus(second.paymentId())).isEqualTo("CAPTURE_PENDING");
            reset(mockProvider);
            paymentJobs.capturePending();
            assertThat(pstatus(first.paymentId())).isEqualTo("CAPTURED");
            assertThat(pstatus(second.paymentId())).isEqualTo("CAPTURED");
        }

        @ParameterizedTest(name = "{0} on the capture")
        @ValueSource(ints = {401, 403})
        @DisplayName("R1: refused keys on the capture keep the payment CAPTURE_PENDING and are one error an interval")
        void captureWithRefusedKeysWaits(int status, CapturedOutput output) throws Exception {
            var submitted = readyToCapture();
            capturesGoTo(razorpayAnswering(status,
                    "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}"));

            for (int run = 0; run < 4; run++) {
                paymentJobs.capturePending();
            }

            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURE_PENDING");
            String line = "refused our credentials capturing payment " + submitted.paymentId();
            assertThat(occurrences(output.getOut(), line)).describedAs("once, not once per run").isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
            // Every run still asked: the throttle silences the line, not the work.
            verify(mockProvider, times(4)).capture(anyString(), any(), anyString());
            reset(mockProvider);
            paymentJobs.capturePending();
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("R1: a capture that keeps failing on a payment near its hold limit is an error, once an hour")
        void captureNearTheHoldLimitIsAnError(CapturedOutput output) throws Exception {
            var submitted = readyToCapture();
            // Almost the whole hold gone, the goods with the buyer, and the capture refused.
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), "
                    + "interval (coalesce(hold_minutes, 4320) - 1) minute) where id = ?", submitted.paymentId());
            doThrow(new PaymentProviderException("Razorpay rejected the request: 403", false, "403"))
                    .when(mockProvider).capture(anyString(), any(), anyString());
            String line = "Payment " + submitted.paymentId() + " is still CAPTURE_PENDING and its hold";

            for (int run = 0; run < 3; run++) {
                paymentJobs.capturePending();
            }

            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        @Test
        @DisplayName("R1: the same alert fires when the capture fails for an ordinary reason, and not for a young payment")
        void captureAlertNeedsAnOldHoldAndAFailure(CapturedOutput output) throws Exception {
            var young = readyToCapture();
            doThrow(new PaymentProviderException("gateway down", true, "503"))
                    .when(mockProvider).capture(anyString(), any(), anyString());
            paymentJobs.capturePending();
            String line = "Payment " + young.paymentId() + " is still CAPTURE_PENDING and its hold";
            assertThat(output.getOut()).describedAs("hold barely used").doesNotContain(line);

            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), "
                    + "interval (coalesce(hold_minutes, 4320) - 1) minute) where id = ?", young.paymentId());
            paymentJobs.capturePending();

            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
            assertThat(pstatus(young.paymentId())).isEqualTo("CAPTURE_PENDING");
        }

        @Test
        @DisplayName("Fourth review 1: a capture Razorpay refuses for good FAILS the payment, and says so as an error a person must act on")
        void captureRefusedForGoodIsAnErrorForAPerson(CapturedOutput output) throws Exception {
            var submitted = readyToCapture();
            doThrow(new PaymentProviderException("Razorpay rejected the request: 400", false, "400"))
                    .when(mockProvider).capture(anyString(), any(), anyString());

            paymentJobs.capturePending();
            paymentJobs.capturePending();

            // State as it has always been: the payment is FAILED, the order goes on, and nothing
            // reads the payment again. Only the line is new.
            assertThat(pstatus(submitted.paymentId())).isEqualTo("FAILED");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("READY_FOR_PICKUP");
            String line = "Payment " + submitted.paymentId() + " for order " + submitted.orderId()
                    + ": capture refused, supplier not paid, a person must act (provider 400:";
            assertThat(occurrences(output.getOut(), line)).describedAs("once: a FAILED payment is not tried again").isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + java.util.regex.Pattern.quote(line));
        }

        @Test
        @DisplayName("Fourth review 3: a capture failing for more than an hour is an error, once an hour, before the hold runs low")
        void captureFailingForAnHourIsAnError(CapturedOutput output) throws Exception {
            var submitted = readyToCapture();
            doThrow(new PaymentProviderException("gateway down", true, "503"))
                    .when(mockProvider).capture(anyString(), any(), anyString());
            String line = "Payment " + submitted.paymentId() + " has been CAPTURE_PENDING and failing to capture since";

            paymentJobs.capturePending();
            assertThat(output.getOut()).describedAs("failing for seconds, not an hour").doesNotContain(line);

            // The first failed attempt was two hours ago; the hold is barely used.
            jdbc.update("update payment_transaction set created_at = date_sub(utc_timestamp(6), interval 2 hour) "
                    + "where payment_id = ? and transaction_type = 'CAPTURE' and status = 'FAILED'", submitted.paymentId());
            for (int run = 0; run < 3; run++) {
                paymentJobs.capturePending();
            }

            assertThat(occurrences(output.getOut(), line)).describedAs("once an hour, not once per run").isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
            assertThat(output.getOut()).doesNotContain("Payment " + submitted.paymentId() + " is still CAPTURE_PENDING and its hold");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURE_PENDING");
        }

        @Test
        @DisplayName("R2: replaying the create request after a draft was cancelled hands out no checkout")
        void replayedCreateAfterDraftCancelHandsOutNoCheckout() throws Exception {
            var submitted = submit("400", 1);
            assertThat(cancelAs(submitted, false)).isEqualTo(200);
            long intentId = jdbc.queryForObject("select intent_id from intent_order_link where supplier_order_id = ?",
                    Long.class, submitted.orderId());

            var res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/intents/" + intentId + "/orders")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("deliveryMode", "PICKUP"))))
                    .andReturn().getResponse();

            assertThat(res.getStatus()).isEqualTo(200);
            var body = json.readTree(res.getContentAsString());
            // A client that opens checkout from this response would take money that is then
            // captured and refunded at our cost; the order is cancelled and nothing is payable.
            assertThat(body.at("/data/payment").isMissingNode() || body.at("/data/payment").isNull())
                    .describedAs("no checkout: " + body.at("/data/payment")).isTrue();
            assertThat(res.getContentAsString()).doesNotContain("publicKey").doesNotContain(submitted.providerOrderId());
        }

        @Test
        @DisplayName("R2: replaying the create request for a live draft still returns its checkout")
        void replayedCreateForALiveDraftStillReturnsTheCheckout() throws Exception {
            var submitted = submit("400", 1);
            long intentId = jdbc.queryForObject("select intent_id from intent_order_link where supplier_order_id = ?",
                    Long.class, submitted.orderId());

            var res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/intents/" + intentId + "/orders")
                            .header("Authorization", "Bearer " + submitted.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("deliveryMode", "PICKUP"))))
                    .andReturn().getResponse();

            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(json.readTree(res.getContentAsString()).at("/data/payment/providerOrderId").asText())
                    .isEqualTo(submitted.providerOrderId());
        }

        /** Two cancelled UPI orders whose refunds are raised, oldest first. */
        private List<Submitted> twoRefundsRaised() throws Exception {
            var first = paidByUpi();
            var second = paidByUpi();
            cancelAs(first, true);
            cancelAs(second, true);
            paymentJobs.settleCancellations();
            assertThat(refundRows(first)).hasSize(1);
            assertThat(refundRows(second)).hasSize(1);
            return List.of(first, second);
        }

        @Test
        @DisplayName("R3: a refund Razorpay refuses on its own does not starve the others: the run rotates")
        void oneRefusedRefundDoesNotStarveTheOthers() throws Exception {
            var both = twoRefundsRaised();
            var refused = both.get(0);
            var other = both.get(1);
            String refusedPid = providerPaymentId(refused);
            doAnswer(call -> {
                if (refusedPid.equals(call.getArgument(0))) {
                    throw new PaymentProviderException("Razorpay rejected the request: 403", false, "403");
                }
                return call.callRealMethod();
            }).when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 4; run++) {
                paymentJobs.processRefunds();
            }

            // Without an order the refused one is first on every run, and stops it.
            assertThat(refundRows(other).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(pstatus(other.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(refundRows(refused).get(0).get("status")).isEqualTo("FAILED");
            assertThat(refundRows(refused).get(0).get("attempts")).describedAs("refusals do not count").isEqualTo(0);
            assertBooksAgree(other);
        }

        @Test
        @DisplayName("R3: a refund still REQUESTED after an hour is an error too, once an hour, before any send")
        void oldRequestedRefundIsAnError(CapturedOutput output) throws Exception {
            var both = twoRefundsRaised();
            jdbc.update("update refund set created_at = date_sub(utc_timestamp(6), interval 2 hour) "
                    + "where payment_id in (?, ?)", both.get(0).paymentId(), both.get(1).paymentId());
            doThrow(new PaymentProviderException("Razorpay rejected the request: 403", false, "403"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            long secondRefund = ((Number) refundRows(both.get(1)).get(0).get("id")).longValue();
            String line = "Refund " + secondRefund + " of payment " + both.get(1).paymentId() + " has been REQUESTED";

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            // Not sent (the run stopped at the other refund), and still said out loud, once.
            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        @Test
        @DisplayName("Fourth review 2: a stuck PROCESSING refund rotates with the others, and is sent though one refund is refused")
        void stuckProcessingRefundIsNotStarvedByARefusedOne() throws Exception {
            var both = twoRefundsRaised();
            var refused = both.get(0);
            var stuck = both.get(1);
            String refusedPid = providerPaymentId(refused);
            long stuckId = ((Number) refundRows(stuck).get(0).get("id")).longValue();
            // The process died mid-send on the second refund: PROCESSING, no provider id, 10 min old.
            jdbc.update("update refund set status = 'PROCESSING', attempts = 1, "
                    + "updated_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?", stuckId);
            doAnswer(call -> {
                if (refusedPid.equals(call.getArgument(0))) {
                    throw new PaymentProviderException("Razorpay rejected the request: 403", false, "403");
                }
                return call.callRealMethod();
            }).when(mockProvider).refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 6; run++) {
                paymentJobs.processRefunds();
            }

            // It used to be appended after the refused one, which stops every run before it.
            assertThat(refundRows(stuck).get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(refundRows(refused).get(0).get("status")).isEqualTo("FAILED");
            assertBooksAgree(stuck);
        }

        @Test
        @DisplayName("Fourth review 2: a refund PROCESSING for over an hour with no provider id is an error too, once an hour")
        void stuckProcessingRefundIsAnError(CapturedOutput output) throws Exception {
            var both = twoRefundsRaised();
            var stuck = both.get(1);
            long stuckId = ((Number) refundRows(stuck).get(0).get("id")).longValue();
            jdbc.update("update refund set status = 'PROCESSING', attempts = 1, "
                    + "updated_at = date_sub(utc_timestamp(6), interval 10 minute), "
                    + "created_at = date_sub(utc_timestamp(6), interval 2 hour) where id = ?", stuckId);
            doThrow(new PaymentProviderException("Razorpay rejected the request: 403", false, "403"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            String line = "Refund " + stuckId + " of payment " + stuck.paymentId() + " has been PROCESSING";

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        /** Answers the lookup of every payment with Razorpay's "the id does not exist". */
        private void everyPaymentIsUnknown() throws Exception {
            lookupsGoTo(razorpayAnswering(400, UNKNOWN_ID_BODY));
        }

        @Test
        @DisplayName("R4: two unknown payments in a row are a configuration fault: the run stops and nothing goes to review")
        void twoUnknownPaymentsInARowIsAConfigurationFault(CapturedOutput output) throws Exception {
            var payments3 = List.of(paidByUpi(), paidByUpi(), paidByUpi());
            for (var submitted : payments3) {
                cancelAs(submitted, true);
            }
            everyPaymentIsUnknown();

            paymentJobs.settleCancellations();
            paymentJobs.settleCancellations();

            for (var submitted : payments3) {
                assertThat(paymentRow(submitted.paymentId()).get("review_required_at"))
                        .describedAs("nobody was sent to review").isNull();
                assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            }
            // Stopped after the second, on each of the two runs.
            verify(mockProvider, times(4)).inspect(anyString());
            String line = "provider does not know 2 payments in a row: check API keys/mode/base URL";
            assertThat(occurrences(output.getOut(), line)).describedAs("one error an interval").isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
            assertThat(output.getOut()).doesNotContain("needs a person");

            // Keys fixed: the same payments are returned by the next run, without a person.
            reset(mockProvider);
            paymentJobs.settleCancellations();
            for (var submitted : payments3) {
                assertThat(pstatus(submitted.paymentId())).isEqualTo("CAPTURED");
            }
        }

        @Test
        @DisplayName("R4: one unknown payment among known ones still goes to a person, whichever it is in the run")
        void aSingleUnknownPaymentStillGoesToReview(CapturedOutput output) throws Exception {
            var unknown = paidByUpi();
            var known = paidByUpi();
            cancelAs(unknown, true);
            cancelAs(known, true);
            String unknownPid = providerPaymentId(unknown);
            doAnswer(call -> {
                if (unknownPid.equals(call.getArgument(0))) {
                    throw new PaymentProviderException("Razorpay refused the lookup: 404", false, "404");
                }
                return call.callRealMethod();
            }).when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            assertThat(paymentRow(unknown.paymentId()).get("review_required_at")).isNotNull();
            assertThat(output.getOut()).contains("Cancellation of payment " + unknown.paymentId() + " needs a person");
            assertThat(pstatus(known.paymentId())).isEqualTo("CAPTURED");
            assertThat(output.getOut()).doesNotContain("payments in a row");
        }

        @Test
        @DisplayName("Fourth review 4: an unknown payment stays held when the next lookup gets no answer, so a third unknown is still a fault")
        void heldPaymentIsNotSentToReviewByALookupThatGotNoAnswer(CapturedOutput output) throws Exception {
            var three = List.of(paidByUpi(), paidByUpi(), paidByUpi());
            for (var submitted : three) {
                cancelAs(submitted, true);
            }
            String timedOutPid = providerPaymentId(three.get(1));
            var razorpay = razorpayAnswering(400, UNKNOWN_ID_BODY);
            doAnswer(call -> {
                if (timedOutPid.equals(call.getArgument(0))) {
                    throw new PaymentProviderException("timed out", true, "timeout");
                }
                return razorpay.inspect(call.getArgument(0));
            }).when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            // Unknown, no answer, unknown: nothing was learnt that clears the keys. The first one
            // used to go to review on the timeout, and the third alone was then not a fault.
            for (var submitted : three) {
                assertThat(paymentRow(submitted.paymentId()).get("review_required_at"))
                        .describedAs("nobody was sent to review").isNull();
                assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            }
            assertThat(output.getOut()).contains("provider does not know 2 payments in a row");
        }

        @Test
        @DisplayName("Fourth review 4: an unknown payment followed only by a lookup with no answer still reaches review at the end of the run, once")
        void heldPaymentStillReachesReviewWhenNothingFollows(CapturedOutput output) throws Exception {
            var unknown = paidByUpi();
            var timedOut = paidByUpi();
            cancelAs(unknown, true);
            cancelAs(timedOut, true);
            String timedOutPid = providerPaymentId(timedOut);
            var razorpay = razorpayAnswering(400, UNKNOWN_ID_BODY);
            doAnswer(call -> {
                if (timedOutPid.equals(call.getArgument(0))) {
                    throw new PaymentProviderException("timed out", true, "timeout");
                }
                return razorpay.inspect(call.getArgument(0));
            }).when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            assertThat(paymentRow(unknown.paymentId()).get("review_required_at")).isNotNull();
            assertThat(occurrences(output.getOut(), "Cancellation of payment " + unknown.paymentId() + " needs a person"))
                    .isEqualTo(1);
            assertThat(paymentRow(timedOut.paymentId()).get("review_required_at")).isNull();
        }

        // F3: the hold guard uses the expiry the payment was created with

        @Test
        @DisplayName("F3: the hold limit is the payment's own: raising the setting afterwards does not lengthen it")
        void guardUsesTheExpiryThePaymentWasCreatedWith() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            assertThat(paymentRow(submitted.paymentId()).get("hold_minutes"))
                    .describedAs("stored when the Razorpay order was made").isEqualTo(4320);
            jdbc.update("update payment set authorized_at = date_sub(utc_timestamp(6), interval 70 hour) "
                    + "where id = ?", submitted.paymentId());
            var before = holdPolicy.holdLimit();
            org.springframework.test.util.ReflectionTestUtils.setField(holdPolicy, "holdLimit",
                    java.time.Duration.ofMinutes(7200));
            try {
                // The order carries 4320 at Razorpay, whatever the setting says now.
                assertThat(funding.canTakeFunds(submitted.orderId())).isFalse();
                supplierStep(submitted, "preparing");
                assertThat(supplierStep(submitted, "ready")).isEqualTo(409);
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(holdPolicy, "holdLimit", before);
            }
        }

        @Test
        @DisplayName("F3: lowering the setting afterwards does not shorten a payment created with a longer hold")
        void guardKeepsALongerHoldThePaymentWasCreatedWith() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            jdbc.update("update payment set hold_minutes = 7200, "
                    + "authorized_at = date_sub(utc_timestamp(6), interval 100 hour) where id = ?",
                    submitted.paymentId());

            assertThat(funding.canTakeFunds(submitted.orderId())).isTrue();
        }

        @Test
        @DisplayName("F3: a payment with no stored expiry falls back to the current setting")
        void paymentWithoutAStoredExpiryUsesTheSetting() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            jdbc.update("update payment set hold_minutes = null, "
                    + "authorized_at = date_sub(utc_timestamp(6), interval 70 hour) where id = ?",
                    submitted.paymentId());
            assertThat(funding.canTakeFunds(submitted.orderId())).describedAs("default 72 hours").isFalse();
            var before = holdPolicy.holdLimit();
            org.springframework.test.util.ReflectionTestUtils.setField(holdPolicy, "holdLimit",
                    java.time.Duration.ofMinutes(7200));
            try {
                assertThat(funding.canTakeFunds(submitted.orderId())).describedAs("setting raised").isTrue();
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(holdPolicy, "holdLimit", before);
            }
        }

        @Test
        @DisplayName("F3: the warning and the past-the-limit alert measure against the payment's own hold too")
        void alertsUseThePaymentsOwnHold(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set hold_minutes = 1440, "
                    + "authorized_at = date_sub(utc_timestamp(6), interval 30 hour) where id = ?",
                    submitted.paymentId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            paymentJobs.settleCancellations();

            assertThat(output.getOut()).contains("Payment " + submitted.paymentId()
                    + " is still CANCEL_PENDING past the provider's hold limit of 24 hours");
        }

        // F6: only the attempt that holds the money says how it was paid

        @Test
        @DisplayName("F6: a late declined card attempt does not overwrite 'upi' on a payment being returned")
        void lateDeclinedCardAttemptDoesNotRewriteTheMethod() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            assertThat(paymentRow(submitted.paymentId()).get("provider_method")).isEqualTo("upi");

            var declined = mockProvider.declineAttempt(submitted.providerOrderId());
            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.failed",
                    declined.providerPaymentId(), submitted.providerOrderId()));

            var row = paymentRow(submitted.paymentId());
            assertThat(row.get("provider_method")).isEqualTo("upi");
            assertThat(row.get("provider_method_detail")).isNull();
            assertThat(row.get("provider_payment_id")).isEqualTo(providerPaymentId(submitted));
            assertThat(api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId())
                    .at("/data/paymentInstrument").asText()).isEqualTo("upi");
        }

        @Test
        @DisplayName("F6: the attempt that holds the money still records its own method while the payment is returned")
        void theHoldingAttemptStillRecordsItsMethod() throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set provider_method = null where id = ?", submitted.paymentId());

            postWebhook(webhookBody("evt_" + UUID.randomUUID(), "payment.authorized",
                    providerPaymentId(submitted), submitted.providerOrderId()));

            assertThat(paymentRow(submitted.paymentId()).get("provider_method")).isEqualTo("upi");
        }

        // F7: alert noise and wording

        @Test
        @DisplayName("F7: the fifteen-minute alert is one line per payment per interval, not one every run")
        void stuckAlertIsThrottled(CapturedOutput output) throws Exception {
            var submitted = paidByUpi();
            cancelAs(submitted, true);
            jdbc.update("update payment set cancel_requested_at = date_sub(utc_timestamp(6), interval 20 minute), "
                    + "authorized_at = date_sub(utc_timestamp(6), interval 30 minute) where id = ?",
                    submitted.paymentId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            for (int run = 0; run < 4; run++) {
                paymentJobs.settleCancellations();
            }

            assertThat(occurrences(output.getOut(), "Payment " + submitted.paymentId()
                    + " still CANCEL_PENDING after")).isEqualTo(1);
        }

        @Test
        @DisplayName("F7: money that reaches a draft cancelled long ago is timed from when it arrived, not from the cancel")
        void stuckAlertIsTimedFromWhenTheMoneyArrived(CapturedOutput output) throws Exception {
            var submitted = submit("400", 10);
            cancelAs(submitted, false);
            jdbc.update("update payment set cancel_requested_at = date_sub(utc_timestamp(6), interval 5 day) "
                    + "where id = ?", submitted.paymentId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());
            payAndConfirm(submitted, "upi");
            assertThat(pstatus(submitted.paymentId())).isEqualTo("CANCEL_PENDING");

            paymentJobs.settleCancellations();

            assertThat(output.getOut()).doesNotContain("Payment " + submitted.paymentId()
                    + " still CANCEL_PENDING after");
        }

        // F8: a run cannot outlive its lock

        @Test
        @DisplayName("F8: a run stops when its time budget is spent, and the next run carries on")
        void cancellationRunStopsAtItsBudget() throws Exception {
            var one = paidByUpi();
            var two = paidByUpi();
            var three = paidByUpi();
            for (var submitted : List.of(one, two, three)) {
                cancelAs(submitted, true);
            }
            Object jobs = org.springframework.test.util.AopTestUtils.getTargetObject(paymentJobs);
            var before = org.springframework.test.util.ReflectionTestUtils.getField(jobs, "cancelBudget");
            org.springframework.test.util.ReflectionTestUtils.setField(jobs, "cancelBudget", java.time.Duration.ZERO);
            try {
                paymentJobs.settleCancellations();
                int settled = count("select count(*) from payment where status = 'CAPTURED' and id in (?, ?, ?)",
                        one.paymentId(), two.paymentId(), three.paymentId());
                assertThat(settled).describedAs("progress every run, but only one payment").isEqualTo(1);
                paymentJobs.settleCancellations();
                assertThat(count("select count(*) from payment where status = 'CAPTURED' and id in (?, ?, ?)",
                        one.paymentId(), two.paymentId(), three.paymentId())).isEqualTo(2);
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(jobs, "cancelBudget", before);
            }
            paymentJobs.settleCancellations();
            assertThat(count("select count(*) from payment where status = 'CAPTURED' and id in (?, ?, ?)",
                    one.paymentId(), two.paymentId(), three.paymentId())).isEqualTo(3);
        }

        // F9: tests that can fail

        @Test
        @DisplayName("F9: two genuinely waiting payments in one run: one the provider fails on does not hold up the other")
        void oneFailingPaymentDoesNotBlockAnotherInTheSameRun() throws Exception {
            var failing = paidByUpi();
            var healthy = paidByUpi();
            String failingId = providerPaymentId(failing);
            cancelAs(failing, true);
            cancelAs(healthy, true);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(failingId);

            paymentJobs.settleCancellations();

            assertThat(pstatus(healthy.paymentId())).isEqualTo("CAPTURED");
            assertThat(pstatus(failing.paymentId())).isEqualTo("CANCEL_PENDING");
            assertThat(paymentRow(failing.paymentId()).get("review_required_at")).isNull();

            reset(mockProvider);
            paymentJobs.settleCancellations();
            assertThat(pstatus(failing.paymentId())).isEqualTo("CAPTURED");
            paymentJobs.processRefunds();
            assertBooksAgree(failing);
            assertBooksAgree(healthy);
        }

        // Notifications (F5)

        @Test
        @DisplayName("F5: a dispute refund to the wallet is announced once, by the dispute's own notification")
        void disputeRefundIsNotAnnouncedTwice() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);

            var refund = creditWallet(submitted, "100.00");
            relayRefundEvents(refund.getId());

            assertThat(count("select count(*) from notification where target_id = ? "
                    + "and event_type = 'RefundCompleted'", submitted.orderId())).isZero();
        }

        @Test
        @DisplayName("F5: each part of a wallet withdrawal does not push a 'refunded' linked to an unrelated old order")
        void withdrawalPartsAreNotAnnouncedPerOrder() throws Exception {
            var card = submit("400", 1);
            payAndConfirm(card);
            dispatch(card);
            paymentJobs.capturePending();
            long withdrawalRefund = refundToCard(card, "10.00");
            paymentJobs.processRefunds();
            assertThat(count("select count(*) from refund where id = ? and status = 'COMPLETED'",
                    withdrawalRefund)).isEqualTo(1);

            relayRefundEvents(withdrawalRefund);

            assertThat(count("select count(*) from notification where target_id = ? "
                    + "and event_type = 'RefundCompleted'", card.orderId())).isZero();
        }

        @Test
        @DisplayName("F5: an older cancellation refund to the wallet is still announced, as added to the wallet")
        void legacyCancellationWalletRefundIsStillAnnounced() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            var refund = refundService.refundCancelled(paymentRepositoryRow(submitted), "test");

            relayRefundEvents(refund.getId());

            assertThat(jdbc.queryForList("select body from notification where target_id = ? "
                    + "and event_type = 'RefundCompleted'", submitted.orderId()))
                    .isNotEmpty().allSatisfy(row -> assertThat((String) row.get("body"))
                            .contains("has been added to your wallet"));
        }

        private com.costonomy.mp.payment.domain.Payment paymentRepositoryRow(Submitted submitted) {
            return payments.findById(submitted.paymentId()).orElseThrow();
        }

        @Test
        @DisplayName("F5: with instant refund on, the refund-started message does not promise 5-7 working days")
        void refundStartedMessageMatchesTheSpeed() throws Exception {
            Object speed = org.springframework.test.util.AopTestUtils.getTargetObject(cancelRefundSpeed);
            org.springframework.test.util.ReflectionTestUtils.setField(speed, "speed", "optimum");
            try {
                var submitted = paidByUpi();
                cancelAs(submitted, true);
                paymentJobs.settleCancellations();
                long refundId = ((Number) refundRows(submitted).get(0).get("id")).longValue();

                relayRefundEvents(refundId);

                var started = jdbc.queryForList("select body from notification where target_id = ? "
                        + "and event_type = 'RefundRequested'", submitted.orderId());
                assertThat(started).isNotEmpty();
                assertThat((String) started.get(0).get("body")).contains("Refund started:")
                        .contains("is on its way back to the account you paid from")
                        .doesNotContain("working days");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(speed, "speed", "normal");
            }
        }

        // What the mobile app reads (refundAmount, refundedAt)

        @Test
        @DisplayName("the order says how much of a cancelled order's money is coming back, and when it landed")
        void orderCarriesTheCancelRefund() throws Exception {
            var submitted = paidByUpi();
            var untouched = order(submitted);
            assertThat(untouched.has("refundAmount")).describedAs("present, null, on an order not cancelled").isTrue();
            assertThat(untouched.get("refundAmount").isNull()).isTrue();
            assertThat(untouched.get("refundedAt").isNull()).isTrue();

            cancelAs(submitted, true);
            assertThat(order(submitted).get("refundAmount").isNull()).describedAs("no refund raised yet").isTrue();
            paymentJobs.settleCancellations();
            var started = order(submitted);
            assertThat(started.get("refundAmount").decimalValue()).isEqualByComparingTo("4000.00");
            assertThat(started.get("refundedAt").isNull()).describedAs("not landed yet").isTrue();
            assertThat(started.get("paymentStatus").asText()).isEqualTo("RETURNING");

            paymentJobs.processRefunds();
            var landed = order(submitted);
            assertThat(landed.get("refundAmount").decimalValue()).isEqualByComparingTo("4000.00");
            assertThat(landed.get("refundedAt").asText()).isNotBlank();
            assertThat(java.time.Instant.parse(landed.get("refundedAt").asText())).isBeforeOrEqualTo(Instant.now());
            assertThat(landed.get("paymentStatus").asText()).isEqualTo("FULLY_REFUNDED");
        }

        @Test
        @DisplayName("a card hold that was dropped has no refund: both fields are null")
        void cardCancelHasNoRefundFields() throws Exception {
            var submitted = submit("400", 10);
            payAndConfirm(submitted, "card");
            cancelAs(submitted, true);

            var body = order(submitted);
            assertThat(body.get("refundAmount").isNull()).isTrue();
            assertThat(body.get("refundedAt").isNull()).isTrue();
        }

        @Autowired private TaskScheduler taskScheduler;

        private String lineWith(String log, String text) {
            return log.lines().filter(line -> line.contains(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("no log line containing: " + text));
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

    private String orderPaymentStatus(Submitted submitted) throws Exception {
        return api.get(submitted.buyer().token(), "/api/v1/supplier-orders/" + submitted.orderId())
                .at("/data/paymentStatus").asText();
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
