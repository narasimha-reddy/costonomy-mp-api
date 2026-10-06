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
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.service.WithdrawalReversalService;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
    @Autowired private com.costonomy.mp.payment.service.WithdrawalReversalService reversals;
    @Autowired private com.costonomy.mp.payment.service.RefundOperations refundOperations;
    @Autowired private com.costonomy.mp.wallet.service.WalletService walletService;
    @Autowired private com.costonomy.mp.wallet.service.WalletWithdrawalService withdrawalService;
    @Autowired private com.costonomy.mp.payment.repository.RefundRepository refundRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired private com.costonomy.mp.wallet.service.WalletTopUpService walletTopUps;
    @Autowired private com.costonomy.mp.quickscan.service.QuickScanService quickScans;
    @Autowired private com.costonomy.mp.procurement.repository.SupplierOrderRepository supplierOrders;

    private ApiClient api;
    private TestOrder orders;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
        forgetWhyRefundsFailed();
    }

    /**
     * A refund refused for a reason on our side (a balance, credentials) pauses withdrawals for half an
     * hour, in every test that shares this database (D-110). What one test left must not pause the next.
     */
    @org.junit.jupiter.api.AfterEach
    void forgetWhyRefundsFailed() {
        jdbc.update("update refund set failure_kind = null where failure_kind in ('INSUFFICIENT_BALANCE', 'CONFIG')");
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

            // 422 with what can go back (D-110), not a bare 400: the app offers "withdraw ₹100 instead".
            assertThat(withdrawStatus(submitted.buyer(), "200.00", UUID.randomUUID().toString())).isEqualTo(422);
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
        @DisplayName("F3/D-110: a withdrawal draws from the OLDEST credits: sources of 10, 20 and 500 (oldest first) and a withdrawal of 30 take the 10 and the 20, never the 500, and the response says two of three were checked")
        void aSmallWithdrawalDrawsFromTheOldestSourcesNotTheLargest() throws Exception {
            var buyer = newBuyer();
            var oldest = submit(buyer, "400", 1);
            payAndConfirm(oldest);
            acceptAndCapture(oldest);
            var middle = submit(buyer, "400", 1);
            payAndConfirm(middle);
            acceptAndCapture(middle);
            var newest = submit(buyer, "1000", 1);
            payAndConfirm(newest);
            acceptAndCapture(newest);
            creditWallet(oldest, "10.00");
            creditWallet(middle, "20.00");
            creditWallet(newest, "500.00");

            var data = withdraw(buyer, "30.00", UUID.randomUUID().toString()).at("/data");

            assertThat(data.get("parts")).hasSize(2);
            assertThat(data.at("/parts/0/paymentId").asLong()).isEqualTo(oldest.paymentId());
            assertThat(data.at("/parts/0/amount").decimalValue()).isEqualByComparingTo("10.00");
            assertThat(data.at("/parts/1/paymentId").asLong()).isEqualTo(middle.paymentId());
            assertThat(data.at("/parts/1/amount").decimalValue()).isEqualByComparingTo("20.00");
            assertThat(balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(data.get("checkedSources").asInt()).isEqualTo(2);
            assertThat(data.get("uncheckedSources").asInt()).isEqualTo(1);
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
        @DisplayName("an order's checkout is opened with no transaction open, and the payment has its provider order (D-136)")
        void orderCreationOpensTheCheckoutOutsideTheTransaction() throws Exception {
            doAnswer(call -> { recordTransactionState("createAuthorization"); return call.callRealMethod(); })
                    .when(mockProvider).createAuthorization(any());

            var submitted = submit("400", 1);

            // The call holds no connection (it used to run inside the order's transaction, across a round trip to the
            // provider), and it happened once, and the payment has what it returned.
            assertThat(openDuring).isEmpty();
            verify(mockProvider, times(1)).createAuthorization(any());
            assertThat(jdbc.queryForObject("select provider_order_id from payment where id = ?",
                    String.class, submitted.paymentId())).isEqualTo(submitted.providerOrderId()).isNotBlank();
        }

        @Test
        @DisplayName("a provider failure while opening the checkout leaves an unpaid draft the supplier cannot see; a retry opens it (D-136)")
        void providerFailureLeavesADraftAndARetryOpensTheCheckout() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId, "name", "Paneer",
                            "packSize", 1, "packUnit", "KG", "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            long intentId = orders.answeredIntent(buyer.token(), buyer.outletId(), seller.token(), skuId, 1);
            doThrow(new com.costonomy.mp.payment.provider.PaymentProviderException("down", true, "GATEWAY_TIMEOUT"))
                    .doCallRealMethod().when(mockProvider).createAuthorization(any());

            var failed = orders.createOrderRaw(buyer.token(), intentId, UUID.randomUUID().toString(),
                    Map.of("deliveryMode", "PICKUP"));

            assertThat(failed.getStatus()).isEqualTo(422);
            assertThat(json.readTree(failed.getContentAsString()).at("/error/code").asText()).isEqualTo("PAYMENT_FAILED");
            assertThat(json.readTree(failed.getContentAsString()).at("/error/message").asText()).contains("Nothing was charged");
            // The order exists and is unpaid, hidden from the supplier; the payment is waiting for its checkout.
            var order = jdbc.queryForMap("""
                    select o.id, o.status from supplier_order o join intent_order_link l on l.supplier_order_id = o.id
                     where l.intent_id = ?""", intentId);
            long orderId = ((Number) order.get("id")).longValue();
            assertThat(order.get("status")).isEqualTo("DRAFT");
            var payment = jdbc.queryForMap("select status, provider_order_id from payment where supplier_order_id = ?", orderId);
            assertThat(payment.get("status")).isEqualTo("CREATED");
            assertThat(payment.get("provider_order_id")).isNull();
            assertThat(jdbc.queryForObject("""
                    select count(*) from outbox_event where aggregate_type = 'SUPPLIER_ORDER' and aggregate_id = ?
                       and event_type in ('SupplierOrderConfirmed', 'SupplierOrderReleased')""",
                    Integer.class, orderId)).isZero();

            // The app drops its key after a 4xx and tries again; the order exists, so the retry opens the checkout.
            var retried = orders.createOrderRaw(buyer.token(), intentId, UUID.randomUUID().toString(),
                    Map.of("deliveryMode", "PICKUP"));

            assertThat(retried.getStatus()).isEqualTo(200);
            var body = json.readTree(retried.getContentAsString()).at("/data");
            assertThat(body.at("/supplierOrderId").asLong()).isEqualTo(orderId);
            assertThat(body.at("/payment/providerOrderId").asText()).isNotBlank();
            assertThat(jdbc.queryForObject("select provider_order_id from payment where supplier_order_id = ?",
                    String.class, orderId)).isEqualTo(body.at("/payment/providerOrderId").asText());
            assertThat(jdbc.queryForObject("""
                    select count(*) from supplier_order o join intent_order_link l on l.supplier_order_id = o.id
                     where l.intent_id = ?""", Integer.class, intentId)).isEqualTo(1);
        }

        @Test
        @DisplayName("a payment whose checkout was never opened is ended by the sweep, and its unseen order abandoned (D-136)")
        void sweepEndsAPaymentWhoseCheckoutWasNeverOpened() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId, "name", "Paneer",
                            "packSize", 1, "packUnit", "KG", "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            long intentId = orders.answeredIntent(buyer.token(), buyer.outletId(), seller.token(), skuId, 1);
            doThrow(new com.costonomy.mp.payment.provider.PaymentProviderException("down", true, "GATEWAY_TIMEOUT"))
                    .when(mockProvider).createAuthorization(any());
            orders.createOrderRaw(buyer.token(), intentId, UUID.randomUUID().toString(), Map.of("deliveryMode", "PICKUP"));
            long orderId = jdbc.queryForObject("select supplier_order_id from intent_order_link where intent_id = ?",
                    Long.class, intentId);
            long paymentId = jdbc.queryForObject("select id from payment where supplier_order_id = ?", Long.class, orderId);

            // Fresh, it is left alone: the app is about to retry.
            paymentJobs.reconcileStale();
            assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
                    .isEqualTo("CREATED");

            // Aged past the grace, with nothing at the provider to ask about.
            jdbc.update("update payment set created_at = date_sub(utc_timestamp(6), interval 2 hour), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 2 hour) where id = ?", paymentId);
            paymentJobs.reconcileStale();

            assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
                    .isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId))
                    .isEqualTo("CANCELLED");
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
        @DisplayName("a withdrawal part the provider declines is sent once, then, with none of ours at the provider, put back in the wallet (D-110)")
        void declinedRefundNeedsReview() throws Exception {
            // .19 makes the mock decline the refund.
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted);
            dispatch(submitted);
            paymentJobs.capturePending();
            long refundId = refundToCard(submitted, "10.00");

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            // It was retried every thirty seconds for ever before (D-101); the provider's "no" is an
            // answer, not a transient error. Sent once. It went out of the wallet and not to the card, and
            // the provider holds nothing of ours, so it came back: REVERSED, not left for a person (D-110).
            assertThat(jdbc.queryForObject("select status from refund where id = ?",
                    String.class, refundId)).isEqualTo("REVERSED");
            verify(mockProvider, times(1)).refund(anyString(), any(), eq("mandi-refund-" + refundId), any());
            assertThat(withdrawalsOf(submitted)).isEqualTo(1);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("10.00");
            assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where refund_id = ? "
                    + "and kind = 'WITHDRAWAL_REVERSAL'", Integer.class, refundId)).isEqualTo(1);
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
            var errors = captureErrors();
            try {
                cancelAndReadyRounds();
            } finally {
                stopCapturing(errors);
            }
            // The loser is handled and reported as the winning outcome: nothing about that
            // is an error, so nothing may be logged at ERROR (an alert would page someone).
            assertThat(errors.list.stream().map(e -> e.getLoggerName() + ": " + e.getFormattedMessage()).toList())
                    .describedAs("ERROR events logged by a lost cancel-or-ready race").isEmpty();
        }

        @Test
        @DisplayName("a lost optimistic lock is not logged at ERROR by Hibernate, and any other batch error still is")
        void lostVersionCheckIsQuietButOtherBatchErrorsAreNot() throws Exception {
            var submitted = paidByUpi();
            assertThat(supplierStep(submitted, "preparing")).isEqualTo(200);
            long orderId = submitted.orderId();
            var errors = captureErrors();
            var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
            try {
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    var order = supplierOrders.findById(orderId).orElseThrow();
                    // Someone else commits first (its own connection), moving the version on.
                    new org.springframework.transaction.support.TransactionTemplate(txManager,
                            new org.springframework.transaction.support.DefaultTransactionDefinition(
                                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW))
                            .executeWithoutResult(other -> jdbc.update(
                                    "update supplier_order set version = version + 1 where id = ?", orderId));
                    order.setStatus(com.costonomy.mp.procurement.domain.SupplierOrderStatus.CANCELLED);
                    supplierOrders.saveAndFlush(order);
                })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
                // The order is exactly as the winner left it.
                assertThat(orderStatus(orderId)).isEqualTo("PREPARING");
                assertThat(errors.list).describedAs("HHH100501 for a stale version").isEmpty();

                // The filter is narrow: the same logger reporting anything else is still an error.
                org.slf4j.LoggerFactory.getLogger("org.hibernate.orm.jdbc.batch")
                        .error("HHH100501: Exception executing batch [java.sql.BatchUpdateException: Duplicate entry], SQL: insert");
                assertThat(errors.list).hasSize(1);
            } finally {
                stopCapturing(errors);
            }
        }

        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureErrors() {
            var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            appender.addFilter(new ch.qos.logback.core.filter.Filter<>() {
                @Override
                public ch.qos.logback.core.spi.FilterReply decide(ch.qos.logback.classic.spi.ILoggingEvent event) {
                    return event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)
                            && (event.getLoggerName().startsWith("org.hibernate")
                            || event.getLoggerName().startsWith("com.costonomy.mp.procurement")
                            || event.getLoggerName().startsWith("com.costonomy.mp.common"))
                            ? ch.qos.logback.core.spi.FilterReply.NEUTRAL : ch.qos.logback.core.spi.FilterReply.DENY;
                }
            });
            appender.start();
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .addAppender(appender);
            return appender;
        }

        private void stopCapturing(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .detachAppender(appender);
            appender.stop();
        }

        private void cancelAndReadyRounds() throws Exception {
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

    // ── A withdrawal part the provider will not send (D-110) ─────────────

    @Nested
    @DisplayName("a withdrawal part the provider will not send (D-110)")
    @ExtendWith(OutputCaptureExtension.class)
    class WithdrawalFailures {

        /**
         * The database is shared by every test in the class, and the refund job sends, verifies and
         * reverses every refund it finds. What earlier tests left is put aside for a person, which is
         * what the job skips, and the breaker's memory of refunds refused for a reason on our side is
         * cleared so one test cannot pause the next one's withdrawal.
         */
        @BeforeEach
        void putAsideWhatEarlierTestsLeft() {
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = null "
                    + "where status in ('REQUESTED', 'FAILED', 'REJECTED') "
                    + "or (status = 'PROCESSING' and provider_refund_id is null)");
            jdbc.update("update refund set failure_kind = null where failure_kind in ('INSUFFICIENT_BALANCE', 'CONFIG')");
            // Not due for the hourly and daily reads of the provider unless a test makes them so.
            jdbc.update("update refund set verified_at = utc_timestamp(6) where status in ('REVERSED', 'NEEDS_REVIEW')");
            // Nor may the cancellation job settle another test's cancelled order under this one's stubs.
            jdbc.update("update payment set review_required_at = utc_timestamp(6), "
                    + "review_reason = 'left by an earlier test' "
                    + "where status = 'CANCEL_PENDING' and review_required_at is null");
            alertThrottle.clear();
        }

        // ── helpers ──────────────────────────────────────────────────────

        private record Ops(String token, long userId) {
        }

        private Ops ops(String roleCode) throws Exception {
            String phone = ApiClient.freshPhone();
            String token = api.login(phone);
            Long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
            jdbc.update("""
                    insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                           granted_at, created_at, updated_at, version)
                    select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                      from role r where r.code = ?
                    """, userId, roleCode);
            return new Ops(token, userId);
        }

        /** A paid order the supplier has made ready, so its money is captured. */
        private Submitted captured(Buyer buyer, String unitPrice, int quantity) throws Exception {
            var submitted = submit(buyer, unitPrice, quantity);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            return submitted;
        }

        private String pid(Submitted submitted) {
            return jdbc.queryForObject("select provider_payment_id from payment where id = ?",
                    String.class, submitted.paymentId());
        }

        private record Withdrawn(Buyer buyer, Submitted source, long refundId, String providerPaymentId) {
        }

        /** A fresh restaurant with {@code amount} of refund money in its wallet from one captured payment, all of it withdrawn. */
        private Withdrawn withdrawn(String unitPrice, int quantity, String amount) throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, unitPrice, quantity);
            creditWallet(source, amount);
            long refundId = withdraw(buyer, amount, UUID.randomUUID().toString())
                    .at("/data/parts/0/refundId").asLong();
            return new Withdrawn(buyer, source, refundId, pid(source));
        }

        private Map<String, Object> refundRow(long refundId) {
            return jdbc.queryForMap("select * from refund where id = ?", refundId);
        }

        private String rstatus(long refundId) {
            return String.valueOf(refundRow(refundId).get("status"));
        }

        private Map<String, Object> payRow(long paymentId) {
            return jdbc.queryForMap("select * from payment where id = ?", paymentId);
        }

        private long reversalsOf(long refundId) {
            return jdbc.queryForObject("select count(*) from wallet_transaction where refund_id = ? "
                    + "and kind = 'WITHDRAWAL_REVERSAL'", Long.class, refundId);
        }

        private long count(String sql, Object... args) {
            return jdbc.queryForObject(sql, Long.class, args);
        }

        private void refuseRefundsWith(ProviderFailureKind kind, String code, String description) {
            doThrow(new PaymentProviderException("refused", false, code, kind, description))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
        }

        private void refundsAreUnreachable() {
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException("timeout")))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
        }

        /**
         * What must be true of a wallet after every scenario: the ledger adds up to the balance, the
         * balance is not negative, every reversed withdrawal part was put back exactly once and nothing
         * else was, and every debit belongs to a refund.
         */
        private void assertLedger(Buyer buyer) {
            BigDecimal net = jdbc.queryForObject("""
                    select coalesce(sum(case wt.direction when 'CREDIT' then wt.amount else -wt.amount end), 0)
                      from wallet_transaction wt join wallet w on w.id = wt.wallet_id where w.outlet_id = ?
                    """, BigDecimal.class, buyer.outletId());
            assertThat(net).describedAs("sum of the ledger").isEqualByComparingTo(balance(buyer));
            assertThat(balance(buyer)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(count("""
                    select count(*) from wallet_transaction wt join wallet w on w.id = wt.wallet_id
                     where w.outlet_id = ? and wt.kind = 'WITHDRAWAL_REVERSAL'
                    """, buyer.outletId())).describedAs("reversal rows")
                    .isEqualTo(count("""
                            select count(*) from refund r join payment p on p.id = r.payment_id
                             where p.outlet_id = ? and r.reason = 'WALLET_WITHDRAWAL' and r.status = 'REVERSED'
                            """, buyer.outletId()));
            assertThat(count("""
                    select count(*) from wallet_transaction wt join wallet w on w.id = wt.wallet_id
                     where w.outlet_id = ? and wt.kind = 'WITHDRAWAL'
                    """, buyer.outletId())).describedAs("withdrawal debits")
                    .isEqualTo(count("""
                            select count(*) from refund r join payment p on p.id = r.payment_id
                             where p.outlet_id = ? and r.reason = 'WALLET_WITHDRAWAL'
                            """, buyer.outletId()));
            // The last row says what the balance was after it: nothing moved the balance without a row.
            BigDecimal last = jdbc.queryForObject("""
                    select coalesce((select wt.balance_after from wallet_transaction wt
                                       join wallet w on w.id = wt.wallet_id where w.outlet_id = ?
                                      order by wt.id desc limit 1), 0)
                    """, BigDecimal.class, buyer.outletId());
            assertThat(last).describedAs("balance after the last ledger row").isEqualByComparingTo(balance(buyer));
        }

        private JsonNode error(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
            return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("error");
        }

        // ── the pre-check ────────────────────────────────────────────────

        @Test
        @DisplayName("the pre-check skips a payment the provider does not know: 422 with the amount that can go, nothing debited, the source blocked; that amount then goes through")
        void precheckSkipsPaymentUnknownToProvider() throws Exception {
            var buyer = newBuyer();
            var s1 = captured(buyer, "400", 1);
            var s2 = captured(buyer, "400", 1);
            var s3 = captured(buyer, "400", 1);
            creditWallet(s1, "400.00");
            creditWallet(s2, "400.00");
            creditWallet(s3, "400.00");
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(pid(s1));

            var refused = withdrawCall(buyer, "1200.00", UUID.randomUUID().toString());

            assertThat(refused.getStatus()).isEqualTo(422);
            var error = error(refused);
            assertThat(error.get("code").asText()).isEqualTo("WITHDRAWAL_EXCEEDS_REFUNDABLE");
            assertThat(error.at("/details/withdrawableNow").decimalValue()).isEqualByComparingTo("800.00");
            assertThat(error.at("/details/blocked").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(error.at("/details/unavailable").decimalValue()).isEqualByComparingTo("0");
            assertThat(error.at("/details/requested").decimalValue()).isEqualByComparingTo("1200.00");
            assertThat(error.at("/details/reason").asText()).isEqualTo("SOURCE_BLOCKED");
            assertThat(error.get("message").asText())
                    .contains("₹800.00 can go back to your card or bank now").contains("₹400.00 can't");
            // Nothing moved.
            assertThat(balance(buyer)).isEqualByComparingTo("1200.00");
            assertThat(withdrawalsOf(s1) + withdrawalsOf(s2) + withdrawalsOf(s3)).isZero();
            assertThat(payRow(s1.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("PAYMENT_UNKNOWN");
            assertThat(payRow(s1.paymentId()).get("provider_refund_blocked_at")).isNotNull();
            assertThat(count("select count(*) from audit_log where entity_type = 'PAYMENT' and entity_id = ? "
                    + "and action = 'PAYMENT_REFUND_BLOCKED'", s1.paymentId())).isEqualTo(1);

            // The amount offered goes through, from the two payments that can take it and not the third.
            var accepted = withdraw(buyer, "800.00", UUID.randomUUID().toString());
            assertThat(accepted.at("/data/parts")).hasSize(2);
            assertThat(accepted.at("/data/parts/0/paymentId").asLong()).isEqualTo(s2.paymentId());
            assertThat(accepted.at("/data/parts/1/paymentId").asLong()).isEqualTo(s3.paymentId());
            paymentJobs.processRefunds();
            verify(mockProvider, never()).refund(eq(pid(s1)), any(), anyString(), any());
            assertThat(mockProvider.refundedOf(pid(s2))).isEqualByComparingTo("400.00");
            assertThat(mockProvider.refundedOf(pid(s3))).isEqualByComparingTo("400.00");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("a blocked source is skipped the next time, without asking the provider about it again, and its money stays spendable")
        void blockedSourceIsSkippedNextTime() throws Exception {
            var buyer = newBuyer();
            var s1 = captured(buyer, "400", 1);
            var s2 = captured(buyer, "400", 1);
            creditWallet(s1, "400.00");
            creditWallet(s2, "400.00");
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(pid(s1));

            assertThat(withdrawStatus(buyer, "800.00", UUID.randomUUID().toString())).isEqualTo(422);
            assertThat(withdrawStatus(buyer, "800.00", UUID.randomUUID().toString())).isEqualTo(422);

            // Asked once; the second attempt did not ask about the payment it already knows is dead.
            verify(mockProvider, times(1)).inspect(pid(s1));
            var second = withdraw(buyer, "400.00", UUID.randomUUID().toString());
            assertThat(second.at("/data/parts")).hasSize(1);
            assertThat(second.at("/data/parts/0/paymentId").asLong()).isEqualTo(s2.paymentId());
            // The blocked 400 is still in the wallet, spendable.
            assertThat(balance(buyer)).isEqualByComparingTo("400.00");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("the pre-check caps a source at what the provider says is left of the payment")
        void precheckCapsAtProviderRemaining() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            // Someone refunded 300 of it in the provider's dashboard.
            mockProvider.refundedExternally(pid(source), new BigDecimal("300.00"));

            var refused = withdrawCall(buyer, "1000.00", UUID.randomUUID().toString());

            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(error(refused).at("/details/withdrawableNow").decimalValue()).isEqualByComparingTo("700.00");
            assertThat(error(refused).at("/details/reason").asText()).isEqualTo("NO_REFUND_MONEY");
            assertThat(error(refused).get("message").asText())
                    .contains("₹700.00 can go back to your card or bank. The rest of your balance can be spent on orders.");
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");

            withdraw(buyer, "700.00", UUID.randomUUID().toString());
            paymentJobs.processRefunds();
            assertThat(mockProvider.refundedOf(pid(source))).isEqualByComparingTo("1000.00");
            assertThat(balance(buyer)).isEqualByComparingTo("300.00");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("a source the provider cannot be asked about is skipped, not blocked, and used on a later attempt")
        void precheckUnreachableIsNotBlocked() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).inspect(anyString());

            var refused = withdrawCall(buyer, "400.00", UUID.randomUUID().toString());

            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(error(refused).at("/details/withdrawableNow").decimalValue()).isEqualByComparingTo("0");
            assertThat(error(refused).at("/details/unavailable").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(error(refused).at("/details/reason").asText()).isEqualTo("PROVIDER_UNREACHABLE");
            assertThat(error(refused).get("message").asText()).contains("Nothing can go back").contains("Try again in a few minutes");
            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_at")).isNull();
            assertThat(balance(buyer)).isEqualByComparingTo("400.00");

            reset(mockProvider);
            assertThat(withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts")).hasSize(1);
            assertLedger(buyer);
        }

        @Test
        @DisplayName("a payment the provider says was never captured is blocked, and the disagreement is an error line")
        void precheckNotCapturedBlocksAndAlerts(CapturedOutput output) throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            doReturn(new PaymentProvider.ProviderPaymentFacts(pid(source),
                    jdbc.queryForObject("select provider_order_id from payment where id = ?", String.class, source.paymentId()),
                    PaymentProvider.ProviderPaymentStatus.AUTHORIZED, false, new BigDecimal("400.00"), BigDecimal.ZERO,
                    "upi", null, null, Instant.now()))
                    .when(mockProvider).inspect(pid(source));

            assertThat(withdrawStatus(buyer, "400.00", UUID.randomUUID().toString())).isEqualTo(422);

            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("NOT_CAPTURED");
            assertThat(output.getOut()).containsPattern("ERROR.*Payment " + source.paymentId()
                    + " is CAPTURED in our books but the provider reports AUTHORIZED");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("a payment refunded in full elsewhere is blocked REFUNDED_ELSEWHERE; one past the refund window is skipped and not blocked")
        void precheckRefundedElsewhereAndWindow() throws Exception {
            var buyer = newBuyer();
            var refundedElsewhere = captured(buyer, "400", 1);
            var old = captured(buyer, "400", 1);
            var fine = captured(buyer, "400", 1);
            creditWallet(refundedElsewhere, "400.00");
            creditWallet(old, "400.00");
            creditWallet(fine, "400.00");
            mockProvider.refundedExternally(pid(refundedElsewhere), new BigDecimal("400.00"));
            var real = mockProvider.inspect(pid(old));
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(),
                    real.captured(), real.amount(), real.amountRefunded(), real.method(), real.methodDetail(), real.fee(),
                    Instant.now().minus(java.time.Duration.ofDays(400)))).when(mockProvider).inspect(pid(old));

            var refused = withdrawCall(buyer, "1200.00", UUID.randomUUID().toString());

            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(error(refused).at("/details/withdrawableNow").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(error(refused).at("/details/blocked").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(error(refused).at("/details/unavailable").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(payRow(refundedElsewhere.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");
            assertThat(payRow(old.paymentId()).get("provider_refund_blocked_at")).describedAs("past the window is not blocked").isNull();
            assertLedger(buyer);
        }

        @Test
        @DisplayName("nothing is asked of the provider when the wallet itself cannot cover the request")
        void noProviderCallWhenTheBalanceIsShort() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "100.00");

            assertThat(withdrawStatus(buyer, "100.01", UUID.randomUUID().toString())).isEqualTo(400);

            verify(mockProvider, never()).inspect(anyString());
            assertLedger(buyer);
        }

        // ── every kind of refusal ────────────────────────────────────────

        @Test
        @DisplayName("a definite refusal, with none of ours at the provider, puts the money back exactly once and blocks a source that will never take it")
        void definiteRejectionReversesOnce(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            refuseRefundsWith(ProviderFailureKind.NOT_CAPTURED, "400", "The payment status should be captured");

            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("REVERSED");
            assertThat(row.get("failure_kind")).isEqualTo("NOT_CAPTURED");
            assertThat(row.get("reversed_at")).isNotNull();
            assertThat(row.get("reversed_by")).isNull();
            assertThat(row.get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(jdbc.queryForMap("select kind, direction, reference, amount, refund_id from wallet_transaction "
                    + "where refund_id = ? and kind = 'WITHDRAWAL_REVERSAL'", w.refundId()))
                    .containsEntry("direction", "CREDIT").containsEntry("reference", "withdrawal-reversal-" + w.refundId());
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("NOT_CAPTURED");
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                    + "and action = 'REFUND_REVERSED'", w.refundId())).isEqualTo(1);
            assertThat(count("select count(*) from outbox_event where event_type = 'WithdrawalReversed' "
                    + "and aggregate_id = ?", w.refundId())).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*Refund " + w.refundId() + " REVERSED: 400.00 back in the wallet");

            // More runs change nothing: one send, one reversal.
            paymentJobs.processRefunds();
            paymentJobs.processRefunds();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            verify(mockProvider, times(1)).refund(eq(w.providerPaymentId()), any(), eq("mandi-refund-" + w.refundId()), any());
            verify(mockProvider, atLeastOnce()).listRefunds(w.providerPaymentId());
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a refusal as an unknown payment that the provider's own answers contradict is sent again, not put back and not blocked; a window refusal is put back and blocked WINDOW_PASSED")
        void permanentRefusalsBlockTheSource() throws Exception {
            var unknown = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.PAYMENT_UNKNOWN, "404", null);
            paymentJobs.processRefunds();
            // Its list and its payment read both answered about the payment in the same minute: the refusal was wrong.
            assertThat(rstatus(unknown.refundId())).isEqualTo("REQUESTED");
            assertThat(reversalsOf(unknown.refundId())).isZero();
            assertThat(payRow(unknown.source().paymentId()).get("provider_refund_blocked_reason")).isNull();
            reset(mockProvider);

            var late = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.WINDOW_PASSED, "400", "Refund is not allowed after the refund window");
            paymentJobs.processRefunds();
            assertThat(rstatus(late.refundId())).isEqualTo("REVERSED");
            assertThat(payRow(late.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("WINDOW_PASSED");
            assertLedger(unknown.buyer());
            assertLedger(late.buyer());
        }

        @Test
        @DisplayName("an insufficient provider balance puts the money back without blocking the source, is withdrawable again, and pauses new withdrawals for a while")
        void insufficientBalanceReversesWithoutBlocking() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.INSUFFICIENT_BALANCE, "400",
                    "Your account does not have enough balance to carry out the refund operation");

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(refundRow(w.refundId()).get("failure_kind")).isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            // Withdrawable again from the same source...
            assertThat(refundService.withdrawable(w.buyer().outletId())).singleElement().satisfies(source -> {
                assertThat(source.paymentId()).isEqualTo(w.source().paymentId());
                assertThat(source.available()).isEqualByComparingTo("400.00");
                assertThat(source.blocked()).isFalse();
            });
            // ...but not for the next half hour: a debit now would only be put back.
            reset(mockProvider);
            var paused = withdrawCall(w.buyer(), "400.00", UUID.randomUUID().toString());
            assertThat(paused.getStatus()).isEqualTo(503);
            assertThat(error(paused).get("code").asText()).isEqualTo("WITHDRAWALS_PAUSED");
            assertThat(error(paused).get("message").asText()).contains("Your money is safe in your wallet");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            verify(mockProvider, never()).inspect(anyString());

            // The window ends.
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 31 minute) where id = ?",
                    w.refundId());
            assertThat(withdraw(w.buyer(), "400.00", UUID.randomUUID().toString()).at("/data/parts")).hasSize(1);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("refused credentials (401) are retried without limit, never reversed, and pause new withdrawals")
        void credentialsRefusedPausesAndNeverReverses() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            doThrow(new PaymentProviderException("refused", false, "401")).when(mockProvider)
                    .refund(anyString(), any(), anyString(), any());

            for (int run = 0; run < 7; run++) {
                paymentJobs.processRefunds();
            }

            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            assertThat(refundRow(w.refundId()).get("failure_kind")).isEqualTo("CONFIG");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(withdrawStatus(w.buyer(), "1.00", UUID.randomUUID().toString())).isEqualTo(503);

            // The keys are fixed: it is sent, and the breaker's memory of it goes with the failure.
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            assertThat(refundRow(w.refundId()).get("failure_kind")).isNull();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("an ambiguous failure is retried, looked up before each resend, and after five goes to review: never put back automatically")
        void ambiguousFailureNeverReverses() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refundsAreUnreachable();

            for (int run = 0; run < 6; run++) {
                paymentJobs.processRefunds();
            }

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("failure_kind")).isEqualTo("AMBIGUOUS");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            // Five sends, and each resend was preceded by a look at the provider.
            verify(mockProvider, times(5)).refund(eq(w.providerPaymentId()), any(), anyString(), any());
            verify(mockProvider, times(4)).listRefunds(w.providerPaymentId());
            assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a 5xx after the provider made the refund is adopted on the next run: one refund at the provider, never sent twice")
        void ambiguousButCreatedAtProviderIsAdopted() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            doAnswer(call -> {
                call.callRealMethod();
                throw PaymentProviderException.unreachable("the answer was lost", new RuntimeException());
            }).when(mockProvider).refund(anyString(), any(), anyString(), any());

            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("400.00");

            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("provider_refund_id")).isNotNull();
            assertThat(row.get("verified_result")).isEqualTo("OURS");
            // Sent once; found, not resent. Provider holds one refund.
            verify(mockProvider, times(1)).refund(eq(w.providerPaymentId()), any(), anyString(), any());
            assertThat(mockProvider.listRefunds(w.providerPaymentId())).hasSize(1);
            assertThat(reversalsOf(w.refundId())).isZero();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a resend whose lookup cannot be read is not sent: it waits, and the attempt counts")
        void unreadableLookupBeforeResendDoesNotSend() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refundsAreUnreachable();
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            clearInvocations(mockProvider);
            reset(mockProvider);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();

            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat((Integer) row.get("attempts")).isEqualTo(2);

            // A rate-limited lookup is not the refund's fault: waits, and does not count.
            reset(mockProvider);
            doThrow(new PaymentProviderException("slow down", false, "429")).when(mockProvider).listRefunds(anyString());
            paymentJobs.processRefunds();
            assertThat((Integer) refundRow(w.refundId()).get("attempts")).isEqualTo(2);
            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("'already refunded' with none of ours at the provider goes to review, is NOT put back, and blocks the source")
        void alreadyRefundedElsewhereIsNotRecredited(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            // After the pre-check and before the send, someone refunds the payment in full in the dashboard.
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));

            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("failure_kind")).isEqualTo("ALREADY_REFUNDED");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");
            assertThat(output.getOut()).containsPattern("ERROR.*Refund " + w.refundId() + " → NEEDS_REVIEW");
            // It stays there: more runs do not touch it.
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("an over-refund refusal where the refund of ours is already there is adopted, not reversed")
        void overRefundButOursIsAdopted() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            // Ours went through at the provider; the answer was lost and the row was left REJECTED by a later refusal.
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "mandi-refund-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));
            jdbc.update("update refund set status = 'REJECTED', failure_kind = 'OVER_REFUND', attempts = 2, "
                    + "sent_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?", w.refundId());

            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("provider_refund_id")).isNotNull();
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a refund the provider made and then reports failed is asked about again after an hour, then reversed, not blocked")
        void providerFailedRefundIsReversedAfterRecheck() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            doReturn(new PaymentProvider.ProviderRefund("rfnd_failed", PaymentProvider.ProviderRefundStatus.FAILED,
                    new BigDecimal("400.00"), "PROVIDER_FAILED", "Razorpay reported the refund as failed"))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            doReturn(new PaymentProvider.ProviderRefund("rfnd_failed", PaymentProvider.ProviderRefundStatus.FAILED,
                    new BigDecimal("400.00"), "PROVIDER_FAILED", "Razorpay reported the refund as failed"))
                    .when(mockProvider).fetchRefund("rfnd_failed");

            paymentJobs.processRefunds();

            // Waiting, not believed yet.
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("PROCESSING");
            assertThat(row.get("failed_at")).isNotNull();
            assertThat(row.get("failure_kind")).isEqualTo("PROVIDER_FAILED");
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?",
                    w.refundId());
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("PROCESSING");
            assertThat(reversalsOf(w.refundId())).isZero();

            // An hour later it is still failed: believed, checked, put back.
            jdbc.update("update refund set failed_at = date_sub(utc_timestamp(6), interval 2 hour), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?", w.refundId());
            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(refundRow(w.refundId()).get("failure_kind")).isEqualTo("PROVIDER_FAILED");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a refund that fails without the provider making one (no id) is a definite refusal: checked, then reversed")
        void failedWithoutAnIdIsCheckedAndReversed() throws Exception {
            // The mock declines a refund of a payment captured for an amount ending .19 with no refund id.
            var buyer = newBuyer();
            var source = captured(buyer, "1000.19", 1);
            creditWallet(source, "1000.19");
            long refundId = withdraw(buyer, "1000.19", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();

            paymentJobs.processRefunds();

            assertThat(rstatus(refundId)).isEqualTo("REVERSED");
            assertThat(reversalsOf(refundId)).isEqualTo(1);
            assertThat(balance(buyer)).isEqualByComparingTo("1000.19");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("the same unexplained refusal twice for one payment blocks it, with an error line")
        void secondUnexplainedRefusalBlocksTheSource(CapturedOutput output) throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "800", 1);
            creditWallet(source, "800.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            for (int i = 0; i < 2; i++) {
                withdraw(buyer, "400.00", UUID.randomUUID().toString());
                paymentJobs.processRefunds();
                if (i == 0) {
                    assertThat(payRow(source.paymentId()).get("provider_refund_blocked_at")).isNull();
                }
            }
            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("OPS");
            assertThat(output.getOut()).contains("refused two withdrawal parts for a reason we do not recognise");
            assertThat(balance(buyer)).isEqualByComparingTo("800.00");
            assertLedger(buyer);
        }

        // ── one reversal per part, however it is reached ─────────────────

        /** A withdrawal part left REJECTED, as a job that died between the refusal and the credit leaves it. */
        private Withdrawn rejected(String failureKind) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            jdbc.update("update refund set status = 'REJECTED', failure_kind = ?, attempts = 1, "
                    + "sent_at = date_sub(utc_timestamp(6), interval 10 minute) where id = ?", failureKind, w.refundId());
            return w;
        }

        @SafeVarargs
        private <T> List<T> inParallel(java.util.concurrent.Callable<T>... calls) throws Exception {
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(calls.length);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<T>>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            var results = new java.util.ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();
            return results;
        }

        @Test
        @DisplayName("two callers reversing the same refund put the money back once: five races, each with a counting stub on the provider")
        void reversalIsIdempotentUnderConcurrency() throws Exception {
            for (int round = 0; round < 5; round++) {
                var w = rejected("REJECTED_OTHER");
                // Slow enough that both callers have read the provider before either has written.
                doAnswer(call -> {
                    Thread.sleep(150);
                    return call.callRealMethod();
                }).when(mockProvider).listRefunds(anyString());

                var results = inParallel(() -> reversals.verifyAndReverse(w.refundId()),
                        () -> reversals.verifyAndReverse(w.refundId()));

                assertThat(results).containsExactlyInAnyOrder(WithdrawalReversalService.Result.REVERSED,
                        WithdrawalReversalService.Result.NOT_APPLICABLE);
                assertThat(reversalsOf(w.refundId())).isEqualTo(1);
                assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
                assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
                assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                        + "and action = 'REFUND_REVERSED'", w.refundId())).isEqualTo(1);
                verify(mockProvider, times(2)).listRefunds(w.providerPaymentId());
                assertLedger(w.buyer());
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("two runs of the refund job over the same rejected refund put the money back once")
        void twoJobRunsReverseOnce() throws Exception {
            var w = rejected("REJECTED_OTHER");
            var results = inParallel(() -> {
                paymentJobs.processRefunds();
                return 1;
            }, () -> {
                paymentJobs.processRefunds();
                return 2;
            });
            assertThat(results).hasSize(2);
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a person putting a withdrawal back races their own retry: exactly one wins, five races")
        void reversalRacesOpsRetry() throws Exception {
            var finance = ops("OPS_FINANCE");
            for (int round = 0; round < 5; round++) {
                var w = withdrawn("400", 1, "400.00");
                jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = 'AMBIGUOUS', attempts = 5, "
                        + "sent_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                refundOperations.verify(finance.userId(), w.refundId(), "read the dashboard");

                var outcomes = inParallel(() -> {
                    try {
                        return refundOperations.recredit(finance.userId(), w.refundId(), "nothing was sent", true,
                                "Razorpay support ticket 1").status();
                    } catch (BusinessException ex) {
                        return "refused " + ex.code();
                    }
                }, () -> {
                    try {
                        return refundOperations.retry(finance.userId(), w.refundId(), "try again").status();
                    } catch (BusinessException ex) {
                        return "refused " + ex.code();
                    }
                });

                String status = rstatus(w.refundId());
                assertThat(status).isIn("REVERSED", "REQUESTED");
                // Exactly one of them acted, and the other was told it was too late.
                assertThat(outcomes.stream().filter(o -> o.startsWith("refused")).count()).isEqualTo(1);
                assertThat(reversalsOf(w.refundId())).isEqualTo("REVERSED".equals(status) ? 1 : 0);
                assertThat(balance(w.buyer())).isEqualByComparingTo("REVERSED".equals(status) ? "400.00" : "0");
                assertLedger(w.buyer());
            }
        }

        @Test
        @DisplayName("a job that died between the refusal and the reversal is finished by the next run, once")
        void crashBetweenRejectAndReverseRecovers() throws Exception {
            var w = rejected("REJECTED_OTHER");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a job that died after claiming a refund and before calling the provider sends it once on restart")
        void crashBetweenDebitAndProviderCall() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            // Claimed (PROCESSING, attempt 1) and then the process died: nothing was sent.
            jdbc.update("update refund set status = 'PROCESSING', attempts = 1, sent_at = date_sub(utc_timestamp(6), interval 20 minute), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 20 minute) where id = ?", w.refundId());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            verify(mockProvider, times(1)).refund(eq(w.providerPaymentId()), any(), eq("mandi-refund-" + w.refundId()), any());
            assertThat(mockProvider.listRefunds(w.providerPaymentId())).hasSize(1);
            assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a job that died after the provider made the refund and before it wrote the outcome finds it and does not send again")
        void crashAfterTheProviderMadeTheRefund() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "mandi-refund-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));
            clearInvocations(mockProvider);
            jdbc.update("update refund set status = 'PROCESSING', attempts = 1, sent_at = date_sub(utc_timestamp(6), interval 20 minute), "
                    + "updated_at = date_sub(utc_timestamp(6), interval 20 minute) where id = ?", w.refundId());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("400.00");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("the provider's list is trusted only two minutes after the first send: a rejected refund waits, then is reversed")
        void reversalWaitsForTheListToCatchUp() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            org.springframework.test.util.ReflectionTestUtils.setField(reversals, "minAge", java.time.Duration.ofMinutes(2));
            try {
                paymentJobs.processRefunds();
                // Rejected just now: not yet believed.
                assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
                assertThat(reversalsOf(w.refundId())).isZero();
                verify(mockProvider, never()).listRefunds(anyString());

                jdbc.update("update refund set sent_at = date_sub(utc_timestamp(6), interval 3 minute) where id = ?", w.refundId());
                paymentJobs.processRefunds();
                assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
                assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(reversals, "minAge", java.time.Duration.ZERO);
            }
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("when the provider cannot be read, a rejected refund is left rejected and nothing is put back; it is reversed once it can be")
        void unreadableProviderLeavesItRejected() throws Exception {
            var w = rejected("REJECTED_OTHER");
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a rejected refund older than half an hour is an error line, once an hour")
        void rejectedForLongIsAnError(CapturedOutput output) throws Exception {
            var w = rejected("REJECTED_OTHER");
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 45 minute) where id = ?", w.refundId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            String line = "Refund " + w.refundId() + " REJECTED for";
            assertThat(occurrences(output.getOut(), line)).isEqualTo(1);
            assertThat(output.getOut()).containsPattern("ERROR.*" + line);
        }

        private int occurrences(String text, String needle) {
            int found = 0;
            for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
                found++;
            }
            return found;
        }

        // ── the ledger, under concurrency ────────────────────────────────

        @Test
        @DisplayName("two simultaneous withdrawals against a provider that can take only one of them: the second sees the first's refund, five races")
        void simultaneousWithdrawalsRespectWhatTheProviderCanTake() throws Exception {
            for (int round = 0; round < 5; round++) {
                var buyer = newBuyer();
                var source = captured(buyer, "1000", 1);
                creditWallet(source, "1000.00");
                // The provider can take only 500 more of this payment.
                mockProvider.refundedExternally(pid(source), new BigDecimal("500.00"));

                var statuses = inParallel(
                        () -> withdrawStatus(buyer, "300.00", UUID.randomUUID().toString()),
                        () -> withdrawStatus(buyer, "300.00", UUID.randomUUID().toString()));

                assertThat(statuses).containsExactlyInAnyOrder(200, 422);
                assertThat(withdrawalsOf(source)).isEqualTo(1);
                assertThat(balance(buyer)).isEqualByComparingTo("700.00");
                assertLedger(buyer);
            }
        }

        @Test
        @DisplayName("a withdrawal racing a wallet order payment: the balance never goes negative and the order is funded or refused cleanly, five races")
        void withdrawalRacesWalletOrderPayment() throws Exception {
            for (int round = 0; round < 5; round++) {
                var buyer = newBuyer();
                var source = captured(buyer, "400", 1);
                creditWallet(source, "400.00");
                var order = submit(buyer, "400", 1);

                var outcomes = inParallel(
                        () -> {
                            try {
                                withdrawalService.withdraw(null, buyer.outletId(), new BigDecimal("400.00"), UUID.randomUUID().toString());
                                return "withdrawn";
                            } catch (BusinessException ex) {
                                return "refused";
                            }
                        },
                        () -> walletService.debitFor(buyer.outletId(), order.orderId(), new BigDecimal("400.00")) ? "paid" : "short");

                // The wallet had 400, and each wanted all of it: exactly one got it.
                assertThat(outcomes).containsAnyOf("withdrawn", "paid");
                assertThat(outcomes.stream().filter(o -> o.equals("withdrawn") || o.equals("paid")).count()).isEqualTo(1);
                assertThat(balance(buyer)).isEqualByComparingTo("0");
                assertLedger(buyer);
            }
        }

        // ── the late success watch ───────────────────────────────────────

        @Test
        @DisplayName("a refund put back that then turns up at the provider is a CRITICAL error, marks the payment for a person, and claws nothing back")
        void lateSuccessIsCritical(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            reset(mockProvider);

            // Nothing there yet: the watch is quiet, and notes that it looked.
            // (An hour past the two-day point, so a clock a little different in the database container from this
            // process's does not make the look just made seem older than the point it was made for.)
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 49 hour), "
                    + "verified_at = date_sub(utc_timestamp(6), interval 49 hour) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();
            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            verify(mockProvider, times(1)).listRefunds(w.providerPaymentId());
            // Looked at today: not again today.
            paymentJobs.auditReversedRefunds();
            verify(mockProvider, times(1)).listRefunds(w.providerPaymentId());

            // The money went out after all.
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "late-key",
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));
            jdbc.update("update refund set verified_at = date_sub(utc_timestamp(6), interval 2 day) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).containsPattern("ERROR.*CRITICAL refund " + w.refundId()
                    + " was REVERSED but provider refund .* exists: restaurant credited twice");
            assertThat(payRow(w.source().paymentId()).get("review_required_at")).isNotNull();
            assertThat(String.valueOf(payRow(w.source().paymentId()).get("review_reason"))).contains("reversed but exists");
            assertThat(count("select count(*) from outbox_event where event_type = 'WithdrawalDoubleCredit' "
                    + "and aggregate_id = ?", w.refundId())).isEqualTo(1);
            assertThat(count("select count(*) from audit_log where action = 'REFUND_REVERSED_BUT_SENT' and entity_id = ?",
                    w.refundId())).isEqualTo(1);
            // Nothing clawed back: the wallet keeps the credit and the refund stays REVERSED.
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("the late success watch stops after fourteen days")
        void lateSuccessWatchEndsAfterFourteenDays() throws Exception {
            var w = rejected("REJECTED_OTHER");
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            reset(mockProvider);
            jdbc.update("update refund set verified_at = date_sub(utc_timestamp(6), interval 2 day), "
                    + "reversed_at = date_sub(utc_timestamp(6), interval 16 day) where id = ?", w.refundId());

            paymentJobs.auditReversedRefunds();

            verify(mockProvider, never()).listRefunds(w.providerPaymentId());
        }

        @Test
        @DisplayName("a refund in review after an ambiguous send is read again by the hourly job, and adopted if the provider made it")
        void ambiguousInReviewIsReconciled() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refundsAreUnreachable();
            for (int run = 0; run < 5; run++) {
                paymentJobs.processRefunds();
            }
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            reset(mockProvider);
            // It turns out the provider made it (a send whose answer was lost).
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "mandi-refund-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));

            // Read an hour ago: due.
            jdbc.update("update refund set verified_at = date_sub(utc_timestamp(6), interval 2 hour) where id = ?", w.refundId());
            paymentJobs.reconcileAmbiguousRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("OURS");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("the hourly read of a refund in review is once an hour and for a week only, and does not move its own clock")
        void ambiguousReconcileIsHourlyForAWeek() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = 'AMBIGUOUS', attempts = 5, "
                    + "sent_at = date_sub(utc_timestamp(6), interval 1 day), verified_at = null where id = ?", w.refundId());
            var before = refundRow(w.refundId()).get("updated_at");

            paymentJobs.reconcileAmbiguousRefunds();
            paymentJobs.reconcileAmbiguousRefunds();

            verify(mockProvider, times(1)).listRefunds(w.providerPaymentId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("updated_at")).describedAs("status clock").isEqualTo(before);
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");

            // A week on it is no longer watched.
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 8 day), "
                    + "verified_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
            clearInvocations(mockProvider);
            paymentJobs.reconcileAmbiguousRefunds();
            verify(mockProvider, never()).listRefunds(w.providerPaymentId());
        }

        // ── what the restaurant sees ─────────────────────────────────────

        @Test
        @DisplayName("the wallet statement shows the withdrawal as REVERSED and the entry that put it back")
        void statementShowsReversal() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.NOT_CAPTURED, "400", "The payment status should be captured");
            paymentJobs.processRefunds();

            var recent = api.get(w.buyer().token(), "/api/v1/outlets/" + w.buyer().outletId() + "/wallet").at("/data/recent");

            assertThat(recent.get(0).get("kind").asText()).isEqualTo("WITHDRAWAL_REVERSAL");
            assertThat(recent.get(0).get("direction").asText()).isEqualTo("CREDIT");
            assertThat(recent.get(0).get("amount").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(recent.get(0).get("reason").asText()).isEqualTo("Withdrawal returned to your wallet");
            assertThat(recent.get(1).get("kind").asText()).isEqualTo("WITHDRAWAL");
            assertThat(recent.get(1).get("refundStatus").asText()).isEqualTo("REVERSED");
            assertThat(recent.get(2).get("kind").asText()).isEqualTo("REFUND");
        }

        @Test
        @DisplayName("a cancellation refund the provider refuses is never credited to a wallet by the system")
        void cancellationRefusalIsNeverAutoCredited() throws Exception {
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();

            var refund = jdbc.queryForMap("select * from refund where payment_id = ? and reason = 'CANCELLATION'", submitted.paymentId());
            assertThat(refund.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(refund.get("failure_kind")).isEqualTo("PROVIDER_FAILED");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            assertThat(count("select count(*) from wallet_transaction wt join wallet w on w.id = wt.wallet_id "
                    + "where w.outlet_id = ?", submitted.buyer().outletId())).isZero();
        }

        private int cancelAs(Submitted submitted) throws Exception {
            return mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + submitted.orderId() + "/supplier-cancel")
                            .header("Authorization", "Bearer " + submitted.seller().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "OUT_OF_STOCK"))))
                    .andReturn().getResponse().getStatus();
        }

        // ── operations ───────────────────────────────────────────────────

        private static final String ADMIN = "/api/v1/admin";

        /** A withdrawal part a person has to decide about. */
        private Withdrawn inReview(String unitPrice, int quantity, String amount, String kind) throws Exception {
            var w = withdrawn(unitPrice, quantity, amount);
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = ?, attempts = 5, "
                    + "failure_code = '500', failure_reason = 'the provider did not answer', "
                    + "sent_at = date_sub(utc_timestamp(6), interval 3 hour), "
                    + "last_sent_at = date_sub(utc_timestamp(6), interval 3 hour), verified_at = null, verified_result = null "
                    + "where id = ?", kind, w.refundId());
            return w;
        }

        private JsonNode postOk(Ops who, String path, Object body) throws Exception {
            var response = mvc.perform(MockMvcRequestBuilders.post(path)
                            .header("Authorization", "Bearer " + who.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
            return json.readTree(response.getContentAsString()).get("data");
        }

        private JsonNode postRefused(Ops who, String path, Object body, int status, String code) throws Exception {
            var response = mvc.perform(MockMvcRequestBuilders.post(path)
                            .header("Authorization", "Bearer " + who.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(status);
            var error = json.readTree(response.getContentAsString()).get("error");
            if (code != null) {
                assertThat(error.get("code").asText()).isEqualTo(code);
            }
            return error;
        }

        private Map<String, Object> recreditBody(String note, boolean confirm, String evidence) {
            var body = new java.util.HashMap<String, Object>();
            body.put("note", note);
            body.put("confirmNoProviderRefund", confirm);
            if (evidence != null) {
                body.put("evidence", evidence);
            }
            return body;
        }

        @Test
        @DisplayName("every operations action needs REFUND_OPERATE: a restaurant, a support user and a stranger are refused, and the read needs PAYMENT_INSPECT")
        void operationsNeedThePermission() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var support = ops("OPS_SUPPORT");
            var finance = ops("OPS_FINANCE");
            var restaurant = new Ops(w.buyer().token(), 0L);
            String refund = ADMIN + "/refunds/" + w.refundId();

            for (var who : List.of(restaurant, support)) {
                postRefused(who, refund + "/verify", Map.of("note", "x"), 403, "FORBIDDEN");
                postRefused(who, refund + "/retry", Map.of("note", "x"), 403, "FORBIDDEN");
                postRefused(who, refund + "/recredit", recreditBody("x", true, "e"), 403, "FORBIDDEN");
                postRefused(who, refund + "/to-wallet", Map.of("note", "x", "confirmNoProviderRefund", true), 403, "FORBIDDEN");
                postRefused(who, refund + "/mark-completed", Map.of("providerRefundId", "rfnd_x", "note", "x"), 403, "FORBIDDEN");
                postRefused(who, ADMIN + "/payments/" + w.source().paymentId() + "/refund-block", Map.of("reason", "x"), 403, "FORBIDDEN");
                postRefused(who, ADMIN + "/payments/" + w.source().paymentId() + "/refund-unblock", Map.of("reason", "x"), 403, "FORBIDDEN");
                postRefused(who, ADMIN + "/payments/" + w.source().paymentId() + "/returned-outside",
                        Map.of("providerRefundId", "rfnd_x", "note", "x"), 403, "FORBIDDEN");
            }
            // The read: a restaurant is refused; support may look.
            assertThat(api.getStatus(restaurant.token(), ADMIN + "/refunds")).isEqualTo(403);
            assertThat(api.getStatus(restaurant.token(), refund)).isEqualTo(403);
            assertThat(api.getStatus(support.token(), ADMIN + "/refunds")).isEqualTo(200);
            assertThat(api.getStatus(support.token(), refund)).isEqualTo(200);
            // Nothing moved.
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                    + "and action like 'REFUND_OPS%'", w.refundId())).isZero();
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            // A note is required.
            postRefused(finance, refund + "/verify", Map.of(), 400, "VALIDATION_ERROR");
            postRefused(finance, refund + "/verify", Map.of("note", " "), 400, "VALIDATION_ERROR");
            // An unknown refund is not found.
            postRefused(finance, ADMIN + "/refunds/999999999/verify", Map.of("note", "x"), 404, null);
        }

        @Test
        @DisplayName("a person puts a withdrawal back only after a fresh verification that showed none of ours, and it is audited with the actor")
        void opsRecreditNeedsFreshVerification() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            // Without a verification: refused, and nothing moves.
            postRefused(finance, refund + "/recredit", recreditBody("checked", true, "ticket 42"), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            var verified = postOk(finance, refund + "/verify", Map.of("note", "read the dashboard"));
            assertThat(verified.get("result").asText()).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");

            // The operator must say nothing was sent, and for an unknown outcome what proves it.
            postRefused(finance, refund + "/recredit", recreditBody("checked", false, "ticket 42"), 400, "VALIDATION_ERROR");
            postRefused(finance, refund + "/recredit", recreditBody("checked", true, null), 400, "VALIDATION_ERROR");
            postRefused(finance, refund + "/recredit", Map.of("confirmNoProviderRefund", true, "evidence", "e"), 400, "VALIDATION_ERROR");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            // A verification more than ten minutes old is not fresh.
            jdbc.update("update refund set verified_at = date_sub(utc_timestamp(6), interval 11 minute) where id = ?", w.refundId());
            postRefused(finance, refund + "/recredit", recreditBody("checked", true, "ticket 42"), 409, "REFUND_VERIFICATION_REQUIRED");
            postOk(finance, refund + "/verify", Map.of("note", "again"));

            var done = postOk(finance, refund + "/recredit", recreditBody("Razorpay support confirms nothing was sent", true, "ticket 42"));

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(done.get("status").asText()).isEqualTo("REVERSED");
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("REVERSED");
            assertThat(row.get("reversed_by")).isEqualTo(finance.userId());
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            var audit = jdbc.queryForList("select action, actor_id, reason from audit_log where entity_type = 'REFUND' "
                    + "and entity_id = ? and action <> 'REFUND_REQUESTED' order by id", w.refundId());
            assertThat(audit).extracting(a -> a.get("action")).contains("REFUND_OPS_VERIFY", "REFUND_REVERSED", "REFUND_OPS_RECREDIT");
            assertThat(audit).allSatisfy(a -> assertThat(a.get("actor_id")).isEqualTo(finance.userId()));
            assertThat(audit.stream().filter(a -> a.get("action").equals("REFUND_OPS_RECREDIT")).findFirst().orElseThrow().get("reason").toString())
                    .contains("Razorpay support confirms").contains("ticket 42");

            // Nothing left to verify.
            postRefused(finance, refund + "/verify", Map.of("note", "x"), 409, "INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("putting a withdrawal back twice, however it is asked, credits once")
        void opsRecreditTwiceCreditsOnce() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(finance, refund + "/verify", Map.of("note", "read"));
            postOk(finance, refund + "/recredit", recreditBody("checked", true, "ticket"));

            postRefused(finance, refund + "/recredit", recreditBody("checked", true, "ticket"), 409, null);
            paymentJobs.processRefunds();
            paymentJobs.reconcileAmbiguousRefunds();

            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a verification that finds our refund adopts it: the refund completes and nothing is put back")
        void opsVerifyAdoptsOurs() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "mandi-refund-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));

            var result = postOk(finance, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "read the dashboard"));

            assertThat(result.get("result").asText()).isEqualTo("ADOPTED");
            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            // And a re-credit of it is refused: it is done.
            postRefused(finance, ADMIN + "/refunds/" + w.refundId() + "/recredit", recreditBody("x", true, "e"), 409, null);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("above ₹10,000 a second person must make the same call; the same person twice is refused")
        void opsSecondApproverAboveTenThousand() throws Exception {
            var w = inReview("1200", 10, "12000.00", "AMBIGUOUS");
            var first = ops("OPS_FINANCE");
            var second = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(first, refund + "/verify", Map.of("note", "read"));

            var waiting = postOk(first, refund + "/recredit", recreditBody("checked", true, "ticket 7"));

            assertThat(waiting.get("done").asBoolean()).isFalse();
            assertThat(waiting.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(refundRow(w.refundId()).get("ops_action")).isEqualTo("RECREDIT");
            assertThat(refundRow(w.refundId()).get("ops_action_by")).isEqualTo(first.userId());
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'REFUND_OPS_APPROVAL_REQUESTED'",
                    w.refundId())).isEqualTo(1);

            // The same person cannot approve their own request, and the money still has not moved.
            postRefused(first, refund + "/recredit", recreditBody("checked", true, "ticket 7"), 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            // A request nobody answered for a day is no approval.
            jdbc.update("update refund set ops_action_at = date_sub(utc_timestamp(6), interval 25 hour) where id = ?", w.refundId());
            var stale = postOk(second, refund + "/verify", Map.of("note", "read"));
            assertThat(stale.get("result").asText()).isEqualTo("NONE_OF_OURS");
            var reasked = postOk(second, refund + "/recredit", recreditBody("checked", true, "ticket 7"));
            assertThat(reasked.get("done").asBoolean()).isFalse();
            assertThat(refundRow(w.refundId()).get("ops_action_by")).isEqualTo(second.userId());

            // A different person from the one who asked carries it out, on the verification that is still fresh:
            // a new verification would end the request (see opsStaleApprovalCannotBeCompleted).
            var done = postOk(first, refund + "/recredit", recreditBody("checked", true, "ticket 7"));

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(refundRow(w.refundId()).get("reversed_by")).isEqualTo(first.userId());
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(balance(w.buyer())).isEqualByComparingTo("12000.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("a retry reads the provider first: ours found is adopted, otherwise the same refund is sent again with the same key")
        void opsRetryVerifiesFirst() throws Exception {
            var finance = ops("OPS_FINANCE");

            var found = inReview("400", 1, "400.00", "AMBIGUOUS");
            mockProvider.refund(found.providerPaymentId(), new BigDecimal("400.00"), "mandi-refund-" + found.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + found.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(found.refundId())), "normal"));
            clearInvocations(mockProvider);
            var adopted = postOk(finance, ADMIN + "/refunds/" + found.refundId() + "/retry", Map.of("note", "try again"));
            assertThat(adopted.get("result").asText()).isEqualTo("ADOPTED");
            assertThat(rstatus(found.refundId())).isEqualTo("COMPLETED");
            paymentJobs.processRefunds();
            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());

            var absent = inReview("400", 1, "400.00", "AMBIGUOUS");
            var queued = postOk(finance, ADMIN + "/refunds/" + absent.refundId() + "/retry", Map.of("note", "try again"));
            assertThat(queued.get("status").asText()).isEqualTo("REQUESTED");
            assertThat((Integer) refundRow(absent.refundId()).get("attempts")).isZero();
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'REFUND_OPS_RETRY' "
                    + "and actor_id = ?", absent.refundId(), finance.userId())).isEqualTo(1);

            paymentJobs.processRefunds();

            assertThat(rstatus(absent.refundId())).isEqualTo("COMPLETED");
            // Sent once, with the key it always had, and preceded by a look at the provider.
            verify(mockProvider, times(1)).refund(eq(absent.providerPaymentId()), any(), eq("mandi-refund-" + absent.refundId()), any());
            assertThat(mockProvider.refundedOf(absent.providerPaymentId())).isEqualByComparingTo("400.00");
            // Only a refund in review can be retried.
            postRefused(finance, ADMIN + "/refunds/" + absent.refundId() + "/retry", Map.of("note", "again"), 409, null);
            assertLedger(found.buyer());
            assertLedger(absent.buyer());
        }

        @Test
        @DisplayName("mark completed is checked against the provider: the wrong amount, an unknown refund, another payment's refund and one already used are all refused")
        void opsMarkCompletedChecksTheProvider() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var other = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
            var wrongAmount = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("100.00"));
            var onAnotherPayment = mockProvider.refundedExternally(other.providerPaymentId(), new BigDecimal("400.00"));
            var right = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("300.00"));

            postRefused(finance, path, Map.of("providerRefundId", wrongAmount.providerRefundId(), "note", "hand refund"), 422, "REFUND_VERIFICATION_FAILED");
            postRefused(finance, path, Map.of("providerRefundId", "rfnd_does_not_exist", "note", "hand refund"), 422, "REFUND_VERIFICATION_FAILED");
            postRefused(finance, path, Map.of("providerRefundId", onAnotherPayment.providerRefundId(), "note", "hand refund"), 422, "REFUND_VERIFICATION_FAILED");
            // 300 is not 400 either.
            postRefused(finance, path, Map.of("providerRefundId", right.providerRefundId(), "note", "hand refund"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");

            var exact = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));
            // Note: refundedExternally beyond capacity is fine for the mock's list; the provider's own record is what is read.
            var done = postOk(finance, path, Map.of("providerRefundId", exact.providerRefundId(), "note", "refunded by hand on the dashboard"));

            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("provider_refund_id")).isEqualTo(exact.providerRefundId());
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'REFUND_OPS_MARK_COMPLETED' "
                    + "and actor_id = ?", w.refundId(), finance.userId())).isEqualTo(1);
            assertThat(reversalsOf(w.refundId())).isZero();
            // The same provider refund cannot complete a second refund of ours.
            var twin = inReview("400", 1, "400.00", "AMBIGUOUS");
            jdbc.update("update refund set payment_id = ? where id = ?", w.source().paymentId(), twin.refundId());
            postRefused(finance, ADMIN + "/refunds/" + twin.refundId() + "/mark-completed",
                    Map.of("providerRefundId", exact.providerRefundId(), "note", "same one"), 422, "REFUND_VERIFICATION_FAILED");
            // Already completed: not again.
            postRefused(finance, path, Map.of("providerRefundId", exact.providerRefundId(), "note", "again"), 409, null);
        }

        @Test
        @DisplayName("a cancellation refund the provider refused can be sent to the wallet instead: verified, reversed, one wallet refund, refunded amount moved once")
        void opsToWalletForACancellationRefund() throws Exception {
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            long refundId = jdbc.queryForObject("select id from refund where payment_id = ? and reason = 'CANCELLATION'",
                    Long.class, submitted.paymentId());
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            var finance = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            var body = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true);

            postRefused(finance, path + "/to-wallet", body, 409, "REFUND_VERIFICATION_REQUIRED");
            postOk(finance, path + "/verify", Map.of("note", "read the dashboard"));
            postRefused(finance, path + "/to-wallet", Map.of("note", "x", "confirmNoProviderRefund", false), 400, "VALIDATION_ERROR");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");

            var done = postOk(finance, path + "/to-wallet", body);

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(rstatus(refundId)).isEqualTo("REVERSED");
            var walletRefund = jdbc.queryForMap("select * from refund where idempotency_key = ?", "cancel-wallet-" + refundId);
            assertThat(walletRefund).containsEntry("destination", "WALLET").containsEntry("status", "COMPLETED")
                    .containsEntry("reason", "CANCELLATION");
            assertThat((BigDecimal) walletRefund.get("amount")).isEqualByComparingTo("400.19");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
            assertThat(count("select count(*) from wallet_transaction where refund_id = ? and kind = 'REFUND'",
                    walletRefund.get("id"))).isEqualTo(1);
            assertThat(decimal(submitted.paymentId(), "refunded_amount")).isEqualByComparingTo("400.19");
            assertThat(pstatusOf(submitted.paymentId())).isEqualTo("FULLY_REFUNDED");
            assertThat(mockProvider.refundedOf(pid(submitted))).isEqualByComparingTo("0");
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'REFUND_OPS_TO_WALLET' and actor_id = ?",
                    refundId, finance.userId())).isEqualTo(1);
            // Once.
            postRefused(finance, path + "/to-wallet", body, 409, null);
            paymentJobs.processRefunds();
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
            assertLedger(submitted.buyer());
        }

        private String pstatusOf(long paymentId) {
            return jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId);
        }

        @Test
        @DisplayName("a withdrawal part cannot be sent to the wallet as a cancellation refund, nor a cancellation refund re-credited as a withdrawal")
        void opsActionsAreForTheRightKindOfRefund() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            postOk(finance, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "read"));
            postRefused(finance, ADMIN + "/refunds/" + w.refundId() + "/to-wallet",
                    Map.of("note", "x", "confirmNoProviderRefund", true), 409, "INVALID_STATE_TRANSITION");

            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            cancelAs(submitted);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            long cancelRefund = jdbc.queryForObject("select id from refund where payment_id = ? and reason = 'CANCELLATION'",
                    Long.class, submitted.paymentId());
            postOk(finance, ADMIN + "/refunds/" + cancelRefund + "/verify", Map.of("note", "read"));
            postRefused(finance, ADMIN + "/refunds/" + cancelRefund + "/recredit", recreditBody("x", true, "e"), 409, "INVALID_STATE_TRANSITION");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("blocking a payment as a refund source stops withdrawals drawing from it; unblocking restores it; both audited, and repeats change nothing")
        void opsBlockAndUnblock() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            var finance = ops("OPS_FINANCE");
            String base = ADMIN + "/payments/" + source.paymentId();

            postRefused(finance, base + "/refund-block", Map.of(), 400, "VALIDATION_ERROR");
            assertThat(postOk(finance, base + "/refund-block", Map.of("reason", "the customer's bank returned refunds")).get("changed").asBoolean()).isTrue();
            assertThat(postOk(finance, base + "/refund-block", Map.of("reason", "again")).get("changed").asBoolean()).isFalse();
            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("OPS");
            assertThat(count("select count(*) from audit_log where entity_type = 'PAYMENT' and entity_id = ? "
                    + "and action = 'PAYMENT_REFUND_BLOCKED' and actor_id = ?", source.paymentId(), finance.userId())).isEqualTo(1);

            var refused = withdrawCall(buyer, "400.00", UUID.randomUUID().toString());
            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(error(refused).at("/details/reason").asText()).isEqualTo("SOURCE_BLOCKED");
            assertThat(balance(buyer)).isEqualByComparingTo("400.00");

            assertThat(postOk(finance, base + "/refund-unblock", Map.of("reason", "it was wrong")).get("changed").asBoolean()).isTrue();
            assertThat(postOk(finance, base + "/refund-unblock", Map.of("reason", "again")).get("changed").asBoolean()).isFalse();
            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_at")).isNull();
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'PAYMENT_REFUND_UNBLOCKED'",
                    source.paymentId())).isEqualTo(1);
            assertThat(withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts")).hasSize(1);
            assertLedger(buyer);
        }

        @Test
        @DisplayName("the queue lists refunds waiting for a person, by status and kind, and the detail shows the provider's own list with ours marked")
        void opsListAndDetail() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("50.00"));

            var queue = api.get(finance.token(), ADMIN + "/refunds?kind=AMBIGUOUS").at("/data");
            assertThat(queue).anySatisfy(item -> {
                assertThat(item.get("id").asLong()).isEqualTo(w.refundId());
                assertThat(item.get("status").asText()).isEqualTo("NEEDS_REVIEW");
                assertThat(item.get("failureKind").asText()).isEqualTo("AMBIGUOUS");
                assertThat(item.get("outletId").asLong()).isEqualTo(w.buyer().outletId());
                assertThat(item.get("amount").decimalValue()).isEqualByComparingTo("400.00");
            });
            // Filtered by status: a refund waiting for a person is not among the reversed.
            assertThat(api.get(finance.token(), ADMIN + "/refunds?status=REVERSED").at("/data"))
                    .noneSatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(w.refundId()));

            var detail = api.get(finance.token(), ADMIN + "/refunds/" + w.refundId()).at("/data");
            assertThat(detail.at("/refund/id").asLong()).isEqualTo(w.refundId());
            assertThat(detail.get("providerRefunds")).hasSize(1);
            assertThat(detail.at("/providerRefunds/0/ours").asBoolean()).isFalse();
            assertThat(detail.get("walletBalance").decimalValue()).isEqualByComparingTo("0");
            assertThat(detail.get("secondApproverAbove").decimalValue()).isEqualByComparingTo("10000.00");

            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).listRefunds(anyString());
            var unreadable = api.get(finance.token(), ADMIN + "/refunds/" + w.refundId()).at("/data");
            assertThat(unreadable.get("providerError").asText()).contains("Could not read the provider");
            assertThat(unreadable.at("/refund/id").asLong()).isEqualTo(w.refundId());
        }

        @Test
        @DisplayName("the wallet credit for a reversal is idempotent on the refund: a second call moves nothing, and each entry adds up")
        void reversalCreditIsIdempotentOnTheRefund() throws Exception {
            var w = withdrawn("400", 1, "400.00");

            walletService.lock(w.buyer().outletId());
            walletService.creditWithdrawalReversal(w.buyer().outletId(), w.refundId(), new BigDecimal("400.00"));
            walletService.creditWithdrawalReversal(w.buyer().outletId(), w.refundId(), new BigDecimal("400.00"));

            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(jdbc.queryForObject("select balance_after from wallet_transaction where refund_id = ? "
                    + "and kind = 'WITHDRAWAL_REVERSAL'", BigDecimal.class, w.refundId())).isEqualByComparingTo("400.00");
        }

        @Test
        @DisplayName("the queue refuses a status or kind it does not know with 400, not a server error")
        void opsListRefusesUnknownFilters() throws Exception {
            var finance = ops("OPS_FINANCE");

            assertThat(api.getStatus(finance.token(), ADMIN + "/refunds?status=BOGUS")).isEqualTo(400);
            assertThat(api.getStatus(finance.token(), ADMIN + "/refunds?kind=BOGUS")).isEqualTo(400);
            assertThat(api.getStatus(finance.token(), ADMIN + "/refunds?status=NEEDS_REVIEW,REJECTED&kind=ambiguous")).isEqualTo(200);
        }

        @Test
        @DisplayName("an operator cannot act on a refund while the provider cannot be read: refused, nothing changes")
        void opsActionsRefuseWhenTheProviderIsUnreadable() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).listRefunds(anyString());

            postRefused(finance, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "x"), 503, "PROVIDER_UNAVAILABLE");
            postRefused(finance, ADMIN + "/refunds/" + w.refundId() + "/retry", Map.of("note", "x"), 503, "PROVIDER_UNAVAILABLE");

            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a cancelled order's payment refunded by hand in the provider's dashboard is recorded as returned outside Mandi, after checking the provider; the job does not raise a second refund")
        void opsReturnedOutsideMandi() throws Exception {
            var submitted = submit("400", 1);
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            assertThat(pstatusOf(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            // Ops captured and refunded it in the dashboard; the job saw a payment it cannot act on.
            String pid = pid(submitted);
            mockProvider.capture(pid, new BigDecimal("400.00"), "by-hand");
            var refunded = mockProvider.refundedExternally(pid, new BigDecimal("400.00"));
            jdbc.update("update payment set review_required_at = utc_timestamp(6), "
                    + "review_reason = 'refunded at the provider outside Mandi' where id = ?", submitted.paymentId());
            var finance = ops("OPS_FINANCE");
            String path = ADMIN + "/payments/" + submitted.paymentId() + "/returned-outside";

            // The provider is read, not believed.
            postRefused(finance, path, Map.of("providerRefundId", "rfnd_nope", "note", "refunded by hand"), 422, "REFUND_VERIFICATION_FAILED");
            var partial = mockProvider.refundedExternally(pid, new BigDecimal("10.00"));
            postRefused(finance, path, Map.of("providerRefundId", partial.providerRefundId(), "note", "refunded by hand"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(pstatusOf(submitted.paymentId())).isEqualTo("CANCEL_PENDING");
            // Not a payment waiting for a person.
            var fine = submit("400", 1);
            payAndConfirm(fine, "upi");
            cancelAs(fine);
            postRefused(finance, ADMIN + "/payments/" + fine.paymentId() + "/returned-outside",
                    Map.of("providerRefundId", "rfnd_x", "note", "x"), 409, "INVALID_STATE_TRANSITION");
            clearInvocations(mockProvider);

            var done = postOk(finance, path, Map.of("providerRefundId", refunded.providerRefundId(), "note", "refunded by hand on 30 Sept"));

            assertThat(done.get("paymentStatus").asText()).isEqualTo("FULLY_REFUNDED");
            var payment = payRow(submitted.paymentId());
            assertThat(payment.get("status")).isEqualTo("FULLY_REFUNDED");
            assertThat((BigDecimal) payment.get("captured_amount")).isEqualByComparingTo("400.00");
            assertThat((BigDecimal) payment.get("refunded_amount")).isEqualByComparingTo("400.00");
            assertThat(payment.get("review_required_at")).isNull();
            var refund = jdbc.queryForMap("select * from refund where payment_id = ?", submitted.paymentId());
            assertThat(refund).containsEntry("status", "COMPLETED").containsEntry("reason", "CANCELLATION")
                    .containsEntry("destination", "ORIGINAL").containsEntry("provider_refund_id", refunded.providerRefundId())
                    .containsEntry("idempotency_key", "cancel-order-" + submitted.orderId());
            assertThat(count("select count(*) from audit_log where entity_type = 'PAYMENT' and entity_id = ? "
                    + "and action = 'PAYMENT_RETURNED_OUTSIDE' and actor_id = ?", submitted.paymentId(), finance.userId())).isEqualTo(1);

            // The jobs find nothing to do, and Mandi sends nothing to the provider.
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            assertThat(count("select count(*) from refund where payment_id = ?", submitted.paymentId())).isEqualTo(1);
            verify(mockProvider, never()).refund(eq(pid), any(), anyString(), any());
            assertThat(orderPaymentStatus(submitted)).isEqualTo("FULLY_REFUNDED");
            // Once.
            postRefused(finance, path, Map.of("providerRefundId", refunded.providerRefundId(), "note", "again"), 409, null);
        }

        // ════════════════════════════════════════════════════════════════════
        // Review findings on the withdrawal failure reversal (each fails on the code before its fix)
        // ════════════════════════════════════════════════════════════════════

        // ── 1: never put back unless the payer was provably not refunded ─────

        @Test
        @DisplayName("F1: a refusal in words we do not recognise, after the payer was refunded in full another way, is NOT put back: it goes to review and the source is blocked")
        void rewordedRefusalAfterADashboardRefundIsNotPutBack() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            // Someone refunds the payment in full in the dashboard; Razorpay's refusal is worded in a way the adapter
            // does not know, so it is filed as REJECTED_OTHER and the wording says nothing about money.
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "This payment has been refunded in full");

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("failure_kind")).isEqualTo("REJECTED_OTHER");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1: any refund on the payment that is not ours sends the part to review, whatever the refusal said and however small it is; the source is not blocked for a partial one")
        void aForeignPartialRefundSendsTheRefusedPartToReview() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            long refundId = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            mockProvider.refundedExternally(pid(source), new BigDecimal("100.00"));
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");

            paymentJobs.processRefunds();

            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(refundId)).isZero();
            assertThat(payRow(source.paymentId()).get("provider_refund_blocked_at")).isNull();
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("F1: the payment's own amount_refunded is checked too: more gone back than our refunds explain, even with an empty list, is review")
        void amountRefundedBeyondOurRefundsSendsTheRefusedPartToReview() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            // The list lags (or the money went back some way it does not list), but the payment says 150 has gone back.
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(),
                    real.captured(), real.amount(), new BigDecimal("150.00"), real.method(), real.methodDetail(), real.fee(),
                    real.createdAt())).when(mockProvider).inspect(w.providerPaymentId());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1: the list is checked on its own too: a refund that is not ours, that the payment's amount_refunded does not count yet (pending), still sends the part to review")
        void aForeignRefundThatAmountRefundedDoesNotCountYetSendsThePartToReview() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(),
                    PaymentProvider.ProviderPaymentStatus.CAPTURED, true, real.amount(), BigDecimal.ZERO, real.method(),
                    real.methodDetail(), real.fee(), real.createdAt())).when(mockProvider).inspect(w.providerPaymentId());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1: a payment that cannot be read is not decided about: the refusal stays REJECTED and nothing is put back, until it can be read")
        void unreadablePaymentLeavesTheRefusalRejected() throws Exception {
            var w = rejected("REJECTED_OTHER");
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).inspect(anyString());

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
            assertThat(reversalsOf(w.refundId())).isZero();
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1: a genuine refusal with nothing but refunds of ours on the payment is still put back, once: our own earlier part is not 'someone else's'")
        void definiteRefusalWithOnlyOurOwnRefundsStillReverses() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            long first = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            paymentJobs.processRefunds();
            assertThat(rstatus(first)).isEqualTo("COMPLETED");
            assertThat(mockProvider.refundedOf(pid(source))).isEqualByComparingTo("400.00");
            // Long enough ago for the provider's amount_refunded to be trusted to include it.
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 1 hour), "
                    + "sent_at = date_sub(utc_timestamp(6), interval 1 hour) where id = ?", first);
            long second = withdraw(buyer, "300.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");

            paymentJobs.processRefunds();

            assertThat(rstatus(second)).isEqualTo("REVERSED");
            assertThat(reversalsOf(second)).isEqualTo(1);
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
            assertLedger(buyer);
        }

        // ── 5: an over-estimate must end in the wallet, not in review ────────

        @Test
        @DisplayName("F5: an over-refund refusal with no refund but ours on the payment is put back (the allowance over-estimated); with a foreign refund it goes to review")
        void overRefundIsPutBackUnlessSomeoneElseRefunded() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.OVER_REFUND, "400", "The refund amount is greater than the refundable amount");

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            reset(mockProvider);

            var other = withdrawn("400", 1, "400.00");
            mockProvider.refundedExternally(other.providerPaymentId(), new BigDecimal("300.00"));
            refuseRefundsWith(ProviderFailureKind.OVER_REFUND, "400", "The refund amount is greater than the refundable amount");
            paymentJobs.processRefunds();
            assertThat(rstatus(other.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(other.refundId())).isZero();
            assertLedger(w.buyer());
            assertLedger(other.buyer());
        }

        @Test
        @DisplayName("F5: what may not be counted by the provider yet is every refund of ours still in flight, and any that changed since the read")
        void unconfirmedCountsEveryRefundOfOursInFlight() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            long inFlightWithId = withdraw(buyer, "100.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long resent = withdraw(buyer, "10.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long doneLongAgo = withdraw(buyer, "1.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long doneJustNow = withdraw(buyer, "2.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long reversed = withdraw(buyer, "3.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            String longAgo = "date_sub(utc_timestamp(6), interval 1 hour)";
            // Pending at the provider with its id since long ago: the provider's amount_refunded may not include it.
            jdbc.update("update refund set status = 'PROCESSING', provider_refund_id = 'rfnd_pending_a', sent_at = " + longAgo
                    + ", updated_at = " + longAgo + " where id = ?", inFlightWithId);
            // First sent long ago, sent again just now: the FIRST send time is old, the last one is not.
            jdbc.update("update refund set status = 'FAILED', attempts = 2, sent_at = " + longAgo
                    + ", updated_at = utc_timestamp(6) where id = ?", resent);
            jdbc.update("update refund set status = 'COMPLETED', provider_refund_id = 'rfnd_done_a', sent_at = " + longAgo
                    + ", updated_at = " + longAgo + " where id = ?", doneLongAgo);
            jdbc.update("update refund set status = 'COMPLETED', provider_refund_id = 'rfnd_done_b', sent_at = " + longAgo
                    + ", updated_at = utc_timestamp(6) where id = ?", doneJustNow);
            jdbc.update("update refund set status = 'REVERSED', updated_at = utc_timestamp(6) where id = ?", reversed);

            var counted = refundRepository.unconfirmedOn(source.paymentId(), Instant.now().minusSeconds(5));

            // 100 (in flight, with an id), 10 (sent again), 2 (finished just now); not 1 (finished long ago), not 3 (reversed).
            assertThat(counted).isEqualByComparingTo("112.00");
        }

        // ── 2: one provider refund completes one refund of ours ─────────────

        @Test
        @DisplayName("F2: two legacy refunds of the same amount cannot both adopt one provider refund: one is completed, the other goes to review; the index refuses a duplicate")
        void twoLegacyRefundsCannotAdoptOneProviderRefund() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            long r1 = withdraw(buyer, "200.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long r2 = withdraw(buyer, "200.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            // Both sent before receipts existed (sent_at null, one attempt), both failed at deploy; the provider made one.
            jdbc.update("update refund set status = 'FAILED', attempts = 1, sent_at = null where id in (?, ?)", r1, r2);
            var made = mockProvider.refundedExternally(pid(source), new BigDecimal("200.00"));

            paymentJobs.processRefunds();

            assertThat(count("select count(*) from refund where provider_refund_id = ?", made.providerRefundId())).isEqualTo(1);
            var statuses = List.of(rstatus(r1), rstatus(r2));
            assertThat(statuses).containsExactlyInAnyOrder("COMPLETED", "NEEDS_REVIEW");
            long loser = "COMPLETED".equals(rstatus(r1)) ? r2 : r1;
            assertThat(refundRow(loser).get("provider_refund_id")).isNull();
            assertThat(reversalsOf(loser)).isZero();
            // Not sent to the provider a second time on a hunch: the one that is left is for a person.
            verify(mockProvider, never()).refund(eq(pid(source)), any(), anyString(), any());
            assertThat(mockProvider.refundedOf(pid(source))).isEqualByComparingTo("200.00");
            // And the database itself refuses a duplicate, for the races the check cannot see.
            assertThatThrownBy(() -> jdbc.update("update refund set provider_refund_id = ? where id = ?",
                    made.providerRefundId(), loser)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertLedger(buyer);
        }

        @Test
        @DisplayName("F2: two operators marking two refunds completed against one provider refund at the same moment: exactly one succeeds")
        void markCompletedAgainstOneProviderRefundSucceedsOnce() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            long r1 = withdraw(buyer, "200.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long r2 = withdraw(buyer, "200.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = 'AMBIGUOUS', attempts = 5 where id in (?, ?)", r1, r2);
            var made = mockProvider.refundedExternally(pid(source), new BigDecimal("200.00"));
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");

            var outcomes = inParallel(
                    () -> markCompletedOrError(a, r1, made.providerRefundId()),
                    () -> markCompletedOrError(b, r2, made.providerRefundId()));

            assertThat(outcomes.stream().filter("OK"::equals).count()).describedAs(outcomes.toString()).isEqualTo(1);
            assertThat(count("select count(*) from refund where provider_refund_id = ?", made.providerRefundId())).isEqualTo(1);
            assertThat(List.of(rstatus(r1), rstatus(r2))).containsExactlyInAnyOrder("COMPLETED", "NEEDS_REVIEW");
            assertLedger(buyer);
        }

        private String markCompletedOrError(Ops who, long refundId, String providerRefundId) {
            try {
                refundOperations.markCompleted(who.userId(), refundId, providerRefundId, "checked in the dashboard");
                return "OK";
            } catch (RuntimeException ex) {
                return ex.getClass().getSimpleName();
            }
        }

        // ── 3: the late success watch is front-loaded, and a hit pauses withdrawals ──

        @Test
        @DisplayName("F3: a reversed refund is looked for after ten minutes; a hit is CRITICAL, is found once, pauses that outlet's withdrawals (spending stays allowed) until ops resolves it")
        void lateSuccessWithinMinutesIsFoundAndPausesWithdrawals(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            reset(mockProvider);
            // The refund of ours turns up at the provider a few minutes after the reversal.
            // (A key of its own: the mock answers a key it has seen with the refund it made then.)
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "late-key-minutes-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(),
                            Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));

            // Five minutes in: not yet time.
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 5 minute), "
                    + "verified_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?", w.refundId());
            clearInvocations(mockProvider);
            paymentJobs.auditReversedRefunds();
            verify(mockProvider, never()).listRefunds(w.providerPaymentId());
            assertThat(withdrawStatus(w.buyer(), "1.00", UUID.randomUUID().toString())).isNotEqualTo(503);

            // Eleven minutes in: found, and the outlet's withdrawals stop.
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), "
                    + "verified_at = date_sub(utc_timestamp(6), interval 11 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).containsPattern("ERROR.*CRITICAL refund " + w.refundId() + " was REVERSED but provider refund");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNotNull();
            assertThat(refundRow(w.refundId()).get("late_success_resolved_at")).isNull();
            var paused = withdrawCall(w.buyer(), "100.00", UUID.randomUUID().toString());
            assertThat(paused.getStatus()).isEqualTo(503);
            assertThat(error(paused).get("code").asText()).isEqualTo("WITHDRAWALS_PAUSED");
            // Spending is allowed: the money is the restaurant's until a person says otherwise.
            var order = submit(w.buyer(), "100", 1);
            assertThat(walletService.debitFor(w.buyer().outletId(), order.orderId(), new BigDecimal("100.00"))).isTrue();
            // Another restaurant is not affected.
            var other = newBuyer();
            var otherSource = captured(other, "400", 1);
            creditWallet(otherSource, "400.00");
            assertThat(withdraw(other, "100.00", UUID.randomUUID().toString()).at("/data/parts")).hasSize(1);
            // Found once: not again, and no second event.
            paymentJobs.auditReversedRefunds();
            assertThat(count("select count(*) from outbox_event where event_type = 'WithdrawalDoubleCredit' and aggregate_id = ?",
                    w.refundId())).isEqualTo(1);

            // Ops resolves it, with a note; that ends the pause and moves no money.
            var finance = ops("OPS_FINANCE");
            BigDecimal before = balance(w.buyer());
            postRefused(ops("OPS_SUPPORT"), ADMIN + "/refunds/" + w.refundId() + "/resolve-late-success", Map.of("note", "x"), 403, "FORBIDDEN");
            assertThat(withdrawStatus(w.buyer(), "1.00", UUID.randomUUID().toString())).isEqualTo(503);
            postRefused(finance, ADMIN + "/refunds/" + w.refundId() + "/resolve-late-success", Map.of(), 400, "VALIDATION_ERROR");
            var resolved = postOk(finance, ADMIN + "/refunds/" + w.refundId() + "/resolve-late-success",
                    Map.of("note", "wallet adjusted by finance, ticket 12"));
            assertThat(resolved.get("result").asText()).isEqualTo("RESOLVED");
            assertThat(balance(w.buyer())).isEqualByComparingTo(before);
            assertThat(withdrawStatus(w.buyer(), "1.00", UUID.randomUUID().toString())).isNotEqualTo(503);
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                    + "and action = 'REFUND_LATE_SUCCESS_RESOLVED' and actor_id = ?", w.refundId(), finance.userId())).isEqualTo(1);
            // A refund that was never found is not something to resolve.
            var never = rejected("REJECTED_OTHER");
            paymentJobs.processRefunds();
            postRefused(finance, ADMIN + "/refunds/" + never.refundId() + "/resolve-late-success", Map.of("note", "x"), 409, "INVALID_STATE_TRANSITION");
        }

        // ── 4: what a refusal offers is what asking for it gets ─────────────

        @Test
        @DisplayName("F4: the amount offered by the refusal is accepted when asked for: ask 900, offered 600, ask 600 goes through")
        void theOfferedAmountIsAccepted() throws Exception {
            var buyer = newBuyer();
            var s1 = captured(buyer, "1000", 1);
            creditWallet(s1, "1000.00");
            // A ₹400 part of the first source is stuck with a person, and ₹300 of it was refunded in the dashboard.
            long stuck = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = 'AMBIGUOUS', attempts = 5 where id = ?", stuck);
            mockProvider.refundedExternally(pid(s1), new BigDecimal("300.00"));
            var s2 = captured(buyer, "300", 1);
            creditWallet(s2, "300.00");

            var first = withdrawCall(buyer, "900.00", UUID.randomUUID().toString());
            assertThat(first.getStatus()).isEqualTo(422);
            var offered = error(first).at("/details/withdrawableNow").decimalValue();
            assertThat(offered).isEqualByComparingTo("600.00");

            var second = withdrawCall(buyer, offered.toPlainString(), UUID.randomUUID().toString());

            assertThat(second.getStatus()).describedAs(second.getContentAsString()).isEqualTo(200);
            var parts = json.readTree(second.getContentAsString()).at("/data/parts");
            BigDecimal total = BigDecimal.ZERO;
            for (var part : parts) {
                total = total.add(part.get("amount").decimalValue());
            }
            assertThat(total).isEqualByComparingTo("600.00");
            assertLedger(buyer);
        }

        // ── 6: a pause must not poison the client's idempotency key ────────

        @Test
        @DisplayName("F6: a withdrawal refused because withdrawals are paused leaves its idempotency key usable: the same key works after the pause, and a completed one still replays")
        void pausedWithdrawalDoesNotPoisonTheKey() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "400", 1);
            var credit = creditWallet(source, "400.00");
            jdbc.update("update refund set failure_kind = 'INSUFFICIENT_BALANCE', updated_at = utc_timestamp(6) where id = ?", credit.getId());
            String key = UUID.randomUUID().toString();

            var refused = withdrawCall(buyer, "100.00", key);
            assertThat(refused.getStatus()).isEqualTo(503);
            assertThat(error(refused).get("code").asText()).isEqualTo("WITHDRAWALS_PAUSED");
            assertThat(count("select count(*) from idempotency_record where idempotency_key = ?", key)).isZero();
            // Still refused for as long as the pause lasts, with the same answer and not "the previous attempt failed".
            var again = withdrawCall(buyer, "100.00", key);
            assertThat(again.getStatus()).isEqualTo(503);
            assertThat(error(again).get("code").asText()).isEqualTo("WITHDRAWALS_PAUSED");

            // The pause ends: the same request with the same key is a first request.
            jdbc.update("update refund set failure_kind = null where id = ?", credit.getId());
            var accepted = withdrawCall(buyer, "100.00", key);
            assertThat(accepted.getStatus()).describedAs(accepted.getContentAsString()).isEqualTo(200);
            var replay = withdrawCall(buyer, "100.00", key);
            assertThat(replay.getStatus()).isEqualTo(200);
            assertThat(json.readTree(replay.getContentAsString()).get("data")).isEqualTo(json.readTree(accepted.getContentAsString()).get("data"));
            assertThat(count("select count(*) from refund where reason = 'WALLET_WITHDRAWAL' and idempotency_key like ?",
                    "withdraw-" + buyer.outletId() + "-" + key + "-%")).isEqualTo(1);
            assertLedger(buyer);
        }

        // ── 7: wrong keys or a wrong account must not block, reverse or send ──

        @Test
        @DisplayName("F7: two payments in a row the provider has never heard of, before a withdrawal, is a configuration fault: nothing is blocked, nothing is debited, one error line")
        void twoUnknownPaymentsBeforeAWithdrawalIsAConfigurationFault(CapturedOutput output) throws Exception {
            var buyer = newBuyer();
            var s1 = captured(buyer, "400", 1);
            var s2 = captured(buyer, "400", 1);
            var s3 = captured(buyer, "400", 1);
            for (var s : List.of(s1, s2, s3)) {
                creditWallet(s, "400.00");
            }
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(anyString());

            assertThat(withdrawStatus(buyer, "1200.00", UUID.randomUUID().toString())).isEqualTo(422);
            assertThat(withdrawStatus(buyer, "1200.00", UUID.randomUUID().toString())).isEqualTo(422);

            for (var s : List.of(s1, s2, s3)) {
                assertThat(payRow(s.paymentId()).get("provider_refund_blocked_at")).describedAs("blocked " + s.paymentId()).isNull();
            }
            assertThat(balance(buyer)).isEqualByComparingTo("1200.00");
            assertThat(withdrawalsOf(s1) + withdrawalsOf(s2) + withdrawalsOf(s3)).isZero();
            // All three are asked (an answer from a later one would say the keys work), and it is written once for both attempts.
            verify(mockProvider, times(6)).inspect(anyString());
            assertThat(occurrences(output.getOut(), "Configuration fault: the provider does not know 3 payments")).isEqualTo(1);
            // The keys are put right: it goes through.
            reset(mockProvider);
            assertThat(withdraw(buyer, "1200.00", UUID.randomUUID().toString()).at("/data/parts")).hasSize(3);
            assertLedger(buyer);
        }

        @Test
        @DisplayName("F7: two rejected refunds whose payments the provider does not know when their refunds are read: nothing is put back, blocked or sent; they are decided once the provider knows them")
        void twoUnknownPaymentsWhenVerifyingIsAConfigurationFault(CapturedOutput output) throws Exception {
            var a = rejected("PAYMENT_UNKNOWN");
            var b = rejected("PAYMENT_UNKNOWN");
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            for (var w : List.of(a, b)) {
                assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
                assertThat(reversalsOf(w.refundId())).isZero();
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            }
            assertThat(occurrences(output.getOut(), "Configuration fault: the provider does not know payments")).isEqualTo(1);
            // The right keys are back: the provider's list is read, and it knows both payments. It said it did not, so
            // the refusals were wrong: both are sent again (with a look at its list first), and neither is put back.
            reset(mockProvider);
            paymentJobs.processRefunds();
            assertThat(rstatus(a.refundId())).isEqualTo("REQUESTED");
            assertThat(rstatus(b.refundId())).isEqualTo("REQUESTED");
            paymentJobs.processRefunds();
            for (var w : List.of(a, b)) {
                assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
                assertThat(reversalsOf(w.refundId())).isZero();
                assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("400.00");
                assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
                assertLedger(w.buyer());
            }
        }

        @Test
        @DisplayName("F7: a 404 on the list is not proof that nothing was sent: a single rejected refund is sent to review, never put back")
        void aNotFoundListIsNotProofOfNothingSent() throws Exception {
            var w = rejected("PAYMENT_UNKNOWN");
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F7: a resend whose lookup gets a 404 is not sent: the earlier send may be at the real account")
        void aNotFoundLookupBeforeAResendDoesNotSend() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refundsAreUnreachable();
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            reset(mockProvider);
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(anyString());

            paymentJobs.processRefunds();

            verify(mockProvider, never()).refund(anyString(), any(), anyString(), any());
            assertThat(rstatus(w.refundId())).isEqualTo("FAILED");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("F7: two different payments the provider refuses as unknown in a row stop the run: the refunds after them are not sent")
        void twoUnknownPaymentsWhenSendingStopTheRun(CapturedOutput output) throws Exception {
            var a = withdrawn("400", 1, "400.00");
            var b = withdrawn("400", 1, "400.00");
            var c = withdrawn("400", 1, "400.00");
            doThrow(new PaymentProviderException("gone", false, "404", ProviderFailureKind.PAYMENT_UNKNOWN, null))
                    .when(mockProvider).refund(eq(a.providerPaymentId()), any(), anyString(), any());
            doThrow(new PaymentProviderException("gone", false, "404", ProviderFailureKind.PAYMENT_UNKNOWN, null))
                    .when(mockProvider).refund(eq(b.providerPaymentId()), any(), anyString(), any());
            // And the list is refused for those two too, as another account's keys would.
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(a.providerPaymentId());
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(b.providerPaymentId());

            paymentJobs.processRefunds();

            verify(mockProvider, never()).refund(eq(c.providerPaymentId()), any(), anyString(), any());
            assertThat(rstatus(c.refundId())).isEqualTo("REQUESTED");
            for (var w : List.of(a, b)) {
                assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
                assertThat(reversalsOf(w.refundId())).isZero();
                assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            }
            assertThat(output.getOut()).contains("Configuration fault: the provider does not know payments");
        }

        // ── 8: a throttled refund must not stop the reversal step ─────────────

        @Test
        @DisplayName("F8: a rate-limited refund stops the sends of the run, not the settling of rejected refunds: the reversal still happens, and the rejected-for-long alert still fires")
        void aThrottledRefundDoesNotStopTheReversalStep(CapturedOutput output) throws Exception {
            var rejectedOne = rejected("REJECTED_OTHER");
            var other = withdrawn("400", 1, "400.00");
            reset(mockProvider);
            doThrow(new PaymentProviderException("slow down", false, "429")).when(mockProvider)
                    .refund(eq(other.providerPaymentId()), any(), anyString(), any());

            paymentJobs.processRefunds();

            assertThat(rstatus(other.refundId())).isEqualTo("FAILED");
            assertThat(rstatus(rejectedOne.refundId())).isEqualTo("REVERSED");
            assertThat(reversalsOf(rejectedOne.refundId())).isEqualTo(1);
            assertLedger(rejectedOne.buyer());

            // And when it cannot be verified, the run that was throttled still says it has been waiting.
            var stuck = rejected("REJECTED_OTHER");
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 45 minute) where id = ?", stuck.refundId());
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).listRefunds(stuck.providerPaymentId());
            paymentJobs.processRefunds();
            assertThat(output.getOut()).containsPattern("ERROR.*Refund " + stuck.refundId() + " REJECTED for");
        }

        // ── 10: an ambiguous refund is not put back seconds after its last send ──

        @Test
        @DisplayName("F10: a person cannot put back a refund whose outcome was never known within thirty minutes of its last send; after that, with a fresh verification, they can")
        void opsRecreditOfAnAmbiguousRefundNeedsAMinimumAge() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                jdbc.update("update refund set last_sent_at = utc_timestamp(6) where id = ?", w.refundId());
                postOk(finance, refund + "/verify", Map.of("note", "read"));
                // Last sent just now: the list may lag a refund the provider has only just made.
                var early = postRefused(finance, refund + "/recredit", recreditBody("checked", true, "ticket 7"), 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(early.get("message").asText()).contains("30 minutes");
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");

                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 31 minute) where id = ?", w.refundId());
                postOk(finance, refund + "/verify", Map.of("note", "read again"));
                var done = postOk(finance, refund + "/recredit", recreditBody("checked", true, "ticket 7"));
                assertThat(done.get("done").asBoolean()).isTrue();
                assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
            assertLedger(w.buyer());
        }

        // ── 11: a first approver's request does not survive a retry or a read ───

        @Test
        @DisplayName("F11: a first approval is void once the refund is retried and fails again, or once it is read again: the second person cannot complete it")
        void opsStaleApprovalCannotBeCompleted() throws Exception {
            var w = inReview("1200", 10, "12000.00", "AMBIGUOUS");
            var first = ops("OPS_FINANCE");
            var second = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(first, refund + "/verify", Map.of("note", "read"));
            assertThat(postOk(first, refund + "/recredit", recreditBody("checked", true, "ticket 7")).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("ops_action")).isEqualTo("RECREDIT");

            // Someone retries; it fails again into review within the day.
            postOk(second, refund + "/retry", Map.of("note", "trying again"));
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            refundsAreUnreachable();
            for (int run = 0; run < 6; run++) {
                paymentJobs.processRefunds();
            }
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            reset(mockProvider);
            // A fresh read that shows none of ours; the second person now calls it. It is a first request, not a completion.
            jdbc.update("update refund set verified_at = utc_timestamp(6), verified_result = 'NONE_OF_OURS', "
                    + "updated_at = date_sub(utc_timestamp(6), interval 1 hour) where id = ?", w.refundId());
            var stale = postOk(second, refund + "/recredit", recreditBody("completing A's request", true, "ticket 7"));
            assertThat(stale.get("done").asBoolean()).isFalse();
            assertThat(stale.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(refundRow(w.refundId()).get("ops_action_by")).isEqualTo(second.userId());

            // A read of the provider in between also ends a request: its picture has changed.
            postOk(first, refund + "/verify", Map.of("note", "read"));
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            var afterRead = postOk(first, refund + "/recredit", recreditBody("checked", true, "ticket 7"));
            assertThat(afterRead.get("done").asBoolean()).isFalse();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        // ── 13: dispute refunds decide under the wallet lock ──────────────────

        @Test
        @DisplayName("F13: two dispute refunds on one wallet-paid order at the same moment cannot both pass the limit: the second decides after the first has committed")
        void disputeRefundsOnOneOrderCannotBothPassTheLimit() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            var order = submit(buyer, "100", 1);
            assertThat(walletService.debitFor(buyer.outletId(), order.orderId(), new BigDecimal("1000.00"))).isTrue();
            var firstHoldsTheLock = new CountDownLatch(1);
            var secondHasStarted = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                    walletService.creditDisputeRefund(order.orderId(), new BigDecimal("700.00"), "dispute-race-a-" + order.orderId());
                    firstHoldsTheLock.countDown();
                    try {
                        // Holds the wallet until the second has read what it reads before the lock (if it does) and is waiting.
                        secondHasStarted.await(10, TimeUnit.SECONDS);
                        Thread.sleep(1500);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return "committed";
                }));
                var second = pool.submit(() -> {
                    firstHoldsTheLock.await(10, TimeUnit.SECONDS);
                    secondHasStarted.countDown();
                    try {
                        walletService.creditDisputeRefund(order.orderId(), new BigDecimal("700.00"), "dispute-race-b-" + order.orderId());
                        return "credited";
                    } catch (BusinessException ex) {
                        return ex.code().name();
                    }
                });
                assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("committed");
                assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("VALIDATION_ERROR");
            } finally {
                pool.shutdownNow();
            }
            BigDecimal refunded = jdbc.queryForObject("select coalesce(sum(amount), 0) from wallet_transaction "
                    + "where supplier_order_id = ? and kind = 'DISPUTE_REFUND'", BigDecimal.class, order.orderId());
            assertThat(refunded).describedAs("credited as dispute refunds of a ₹1000 order").isEqualByComparingTo("700.00");
        }

        // ── second review of the reversal (D-110) ────────────────────────────

        private String rawPost(Ops who, String path, Object body) throws Exception {
            var r = mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + who.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
            String c = r.getContentAsString();
            return r.getStatus() + " " + (c.length() > 200 ? c.substring(0, 200) : c);
        }

        /** The provider answers "unknown" for this payment on both reads, as it does for a payment that is really gone. */
        private void unknownToProvider(String providerPaymentId) {
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(providerPaymentId);
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(providerPaymentId);
        }

        /** Set a field of the real bean behind a scheduler-lock proxy. */
        private void setJobField(String name, Object value) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(paymentJobs);
            org.springframework.test.util.ReflectionTestUtils.setField(target, name, value);
        }

        private void agedRejected(Withdrawn w, int minutesAgo) {
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval ? minute) where id = ?",
                    minutesAgo, w.refundId());
        }

        // 1: the head of the rejected queue

        @Test
        @DisplayName("R2-1: two payments the provider cannot place at the head of the rejected queue do not starve the refunds behind them: once another is answered they go to a person, once, and the rest is decided")
        void twoDeadPaymentsAtTheHeadDoNotStarveTheQueue(CapturedOutput output) throws Exception {
            var a = rejected("PAYMENT_UNKNOWN");
            var b = rejected("PAYMENT_UNKNOWN");
            var c = rejected("REJECTED_OTHER");
            agedRejected(a, 50);
            agedRejected(b, 50);
            agedRejected(c, 40);
            unknownToProvider(a.providerPaymentId());
            unknownToProvider(b.providerPaymentId());

            for (int i = 0; i < 3; i++) {
                paymentJobs.processRefunds();
            }

            assertThat(rstatus(c.refundId())).isEqualTo("REVERSED");
            assertThat(reversalsOf(c.refundId())).isEqualTo(1);
            assertThat(balance(c.buyer())).isEqualByComparingTo("400.00");
            for (var dead : List.of(a, b)) {
                var row = refundRow(dead.refundId());
                assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
                assertThat(row.get("review_cause")).isEqualTo("PAYMENT_GONE");
                assertThat(reversalsOf(dead.refundId())).isZero();
                assertThat(balance(dead.buyer())).isEqualByComparingTo("0");
                // Not blocked: the queue cannot tell a dead payment from a wrong key set by itself.
                assertThat(payRow(dead.source().paymentId()).get("provider_refund_blocked_at")).isNull();
                // Sent to a person once, not on every run.
                assertThat(occurrences(output.getOut(), "Refund " + dead.refundId() + " → NEEDS_REVIEW after verification")).isEqualTo(1);
            }
            assertThat(occurrences(output.getOut(), "Configuration fault: the provider does not know payments")).isZero();
            assertLedger(c.buyer());
        }

        @Test
        @DisplayName("R2-1: the thirty-minute alert fires for every rejected refund, whatever its place in the queue or what the run made of it")
        void theRejectedAlertFiresForEveryRefundWherever(CapturedOutput output) throws Exception {
            var a = rejected("PAYMENT_UNKNOWN");
            var b = rejected("PAYMENT_UNKNOWN");
            var c = rejected("REJECTED_OTHER");
            agedRejected(a, 50);
            agedRejected(b, 50);
            agedRejected(c, 40);
            unknownToProvider(a.providerPaymentId());
            unknownToProvider(b.providerPaymentId());
            // Nothing is answered on this page: the two are a configuration fault and the third could not be read.
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException("timeout")))
                    .when(mockProvider).listRefunds(c.providerPaymentId());

            paymentJobs.processRefunds();

            for (var w : List.of(a, b, c)) {
                assertThat(rstatus(w.refundId())).isEqualTo("REJECTED");
                assertThat(output.getOut()).describedAs("alert for " + w.refundId())
                        .containsPattern("ERROR.*Refund " + w.refundId() + " REJECTED for");
            }
            assertThat(occurrences(output.getOut(), "Configuration fault: the provider does not know payments")).isEqualTo(1);
        }

        @Test
        @DisplayName("R2-1: refunds read and left undecided move behind the others in the queue, so a few dead payments cannot hold its head for ever")
        void heldRefundsMoveBehindTheOthers() throws Exception {
            var a = rejected("PAYMENT_UNKNOWN");
            var b = rejected("PAYMENT_UNKNOWN");
            var x = rejected("PAYMENT_UNKNOWN");
            var c = rejected("REJECTED_OTHER");
            agedRejected(a, 50);
            agedRejected(b, 50);
            agedRejected(x, 45);
            agedRejected(c, 40);
            for (var dead : List.of(a, b, x)) {
                unknownToProvider(dead.providerPaymentId());
            }
            setJobField("settlePageSize", 2);
            try {
                var updatedBefore = refundRow(a.refundId()).get("updated_at");
                // The run reads two: both dead, nothing answered: a configuration fault, and they are held back.
                paymentJobs.processRefunds();
                assertThat(rstatus(a.refundId())).isEqualTo("REJECTED");
                assertThat(rstatus(b.refundId())).isEqualTo("REJECTED");
                assertThat(refundRow(a.refundId()).get("settle_held_at")).isNotNull();
                assertThat(refundRow(a.refundId()).get("updated_at")).describedAs("when it was rejected is not touched").isEqualTo(updatedBefore);
                assertThat(rstatus(c.refundId())).isEqualTo("REJECTED");

                // The next run starts with the others: the third dead one and the healthy one. It is answered, so the
                // dead one goes to a person; the two held stay where they are.
                paymentJobs.processRefunds();
                assertThat(rstatus(c.refundId())).isEqualTo("REVERSED");
                assertThat(rstatus(x.refundId())).isEqualTo("NEEDS_REVIEW");
                assertThat(rstatus(a.refundId())).isEqualTo("REJECTED");
                assertThat(rstatus(b.refundId())).isEqualTo("REJECTED");
            } finally {
                setJobField("settlePageSize", 100);
            }
            assertLedger(c.buyer());
        }

        @Test
        @DisplayName("R2-1: a dead payment sent for a refund does not stop the sends behind it once the provider has answered about another: the count of unknown payments starts again")
        void unknownCounterStartsAgainAfterAnAnswer() throws Exception {
            var a = withdrawn("400", 1, "400.00");
            var b = withdrawn("400", 1, "400.00");
            var c = withdrawn("400", 1, "400.00");
            var d = withdrawn("400", 1, "400.00");
            for (var dead : List.of(a, c)) {
                doThrow(new PaymentProviderException("gone", false, "404", ProviderFailureKind.PAYMENT_UNKNOWN, null))
                        .when(mockProvider).refund(eq(dead.providerPaymentId()), any(), anyString(), any());
            }

            paymentJobs.processRefunds();

            // a: refused (the first unknown); b: sent and answered, which ends the count; c: refused; d: still sent.
            assertThat(rstatus(b.refundId())).isEqualTo("COMPLETED");
            assertThat(rstatus(d.refundId())).isEqualTo("COMPLETED");
        }

        // 2: the second approver with the production age

        @Test
        @DisplayName("R2-2: with the production minimum age, the second person completes a re-credit above the threshold: an approval, a verification or a note does not make the refund look recently sent")
        void secondApproverCanCompleteWithTheProductionAge() throws Exception {
            var w = inReview("1200", 10, "12000.00", "AMBIGUOUS");
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                postOk(a, refund + "/verify", Map.of("note", "read"));
                var asked = postOk(a, refund + "/recredit", recreditBody("checked", true, "ticket 7"));
                assertThat(asked.get("awaitingSecondApprover").asBoolean()).isTrue();
                // The row changed just now (updated_at), the last send was three hours ago.
                var sentBefore = refundRow(w.refundId()).get("last_sent_at");

                var done = postOk(b, refund + "/recredit", recreditBody("second", true, "ticket 7"));

                assertThat(done.get("done").asBoolean()).isTrue();
                assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("12000.00");
                assertThat(refundRow(w.refundId()).get("last_sent_at")).isEqualTo(sentBefore);
                assertLedger(w.buyer());
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("R2-2: the last send is recorded at every send, and only by a send")
        void lastSentIsSetBySendsOnly() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            assertThat(refundRow(w.refundId()).get("last_sent_at")).isNull();
            refundsAreUnreachable();
            paymentJobs.processRefunds();
            var first = (java.sql.Timestamp) refundRow(w.refundId()).get("last_sent_at");
            assertThat(first).isNotNull();
            jdbc.update("update refund set last_sent_at = date_sub(last_sent_at, interval 1 hour) where id = ?", w.refundId());
            paymentJobs.processRefunds();
            var second = (java.sql.Timestamp) refundRow(w.refundId()).get("last_sent_at");
            assertThat(second).describedAs("moves at each send").isAfter(java.sql.Timestamp.from(first.toInstant().minusSeconds(60)));
            assertThat(refundRow(w.refundId()).get("sent_at")).describedAs("the first send stays").isNotEqualTo(second);
        }

        // 3: the payer was refunded another way

        @Test
        @DisplayName("R2-3: a part the system held back because the payer was refunded another way records why; verify says so; a re-credit is refused, and marking it done against that refund is the way out")
        void aPartHeldBackForAForeignRefundIsNeverPutBackByAPerson() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            var foreign = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "This payment has been refunded in full");
            paymentJobs.processRefunds();
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            assertThat(row.get("review_ref")).isEqualTo(foreign.providerRefundId());
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");
            reset(mockProvider);
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            // The queue says why.
            var listed = mvc.perform(MockMvcRequestBuilders.get(ADMIN + "/refunds").header("Authorization", "Bearer " + finance.token()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(listed).contains("\"reviewCause\":\"REFUNDED_ANOTHER_WAY\"").contains(foreign.providerRefundId());

            // A verification reads what the system read: not "none of ours".
            assertThat(postOk(finance, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("FOREIGN_REFUND");
            postRefused(finance, refund + "/recredit", recreditBody("checked the list", true, null), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // The way out: that refund is this one, checked against the provider.
            var done = postOk(finance, refund + "/mark-completed",
                    Map.of("providerRefundId", foreign.providerRefundId(), "note", "the dashboard refund of this withdrawal"));
            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("R2-3: the recorded cause alone refuses a re-credit, even when a verification shows nothing; a retry clears it")
        void theRecordedCauseRefusesARecredit() throws Exception {
            var w = inReview("400", 1, "400.00", "REJECTED_OTHER");
            jdbc.update("update refund set review_cause = 'REFUNDED_ANOTHER_WAY', review_ref = 'rfnd_x' where id = ?", w.refundId());
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(finance, refund + "/verify", Map.of("note", "read"));
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");

            var refused = postRefused(finance, refund + "/recredit", recreditBody("checked", true, null), 422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("rfnd_x");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            postOk(finance, refund + "/retry", Map.of("note", "ask the provider again"));
            assertThat(refundRow(w.refundId()).get("review_cause")).isNull();
            assertThat(refundRow(w.refundId()).get("review_ref")).isNull();
        }

        @Test
        @DisplayName("R2-3: a verification and a re-credit read the payer's refunds too: another refund on the payment, or more refunded than ours explain, is never 'none of ours'")
        void verificationAndRecreditReadForeignRefunds() throws Exception {
            var finance = ops("OPS_FINANCE");
            // On the list.
            var listedCase = inReview("400", 1, "400.00", "AMBIGUOUS");
            mockProvider.refundedExternally(listedCase.providerPaymentId(), new BigDecimal("100.00"));
            assertThat(postOk(finance, ADMIN + "/refunds/" + listedCase.refundId() + "/verify", Map.of("note", "read"))
                    .get("result").asText()).isEqualTo("FOREIGN_REFUND");

            // In the payment's own figure only.
            var figureCase = inReview("400", 1, "400.00", "AMBIGUOUS");
            var real = mockProvider.inspect(figureCase.providerPaymentId());
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(),
                    real.captured(), real.amount(), new BigDecimal("400.00"), real.method(), real.methodDetail(), real.fee(),
                    real.createdAt())).when(mockProvider).inspect(figureCase.providerPaymentId());
            assertThat(postOk(finance, ADMIN + "/refunds/" + figureCase.refundId() + "/verify", Map.of("note", "read"))
                    .get("result").asText()).isEqualTo("FOREIGN_REFUND");
            reset(mockProvider);

            // Nothing there, verified; then the payer is refunded another way; the re-credit's own read sees it.
            var late = inReview("400", 1, "400.00", "AMBIGUOUS");
            String refund = ADMIN + "/refunds/" + late.refundId();
            assertThat(postOk(finance, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            mockProvider.refundedExternally(late.providerPaymentId(), new BigDecimal("400.00"));
            postRefused(finance, refund + "/recredit", recreditBody("checked", true, "ticket 7"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(rstatus(late.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(late.refundId()).get("verified_result")).isEqualTo("FOREIGN_REFUND");
            assertThat(balance(late.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(late.refundId())).isZero();
            assertLedger(late.buyer());
        }

        @Test
        @DisplayName("R2-3: a cancellation refund is not sent to the wallet while the payer was refunded another way: the read made for the call sees it")
        void toWalletRefusesAForeignRefund() throws Exception {
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            long refundId = jdbc.queryForObject("select id from refund where payment_id = ? and reason = 'CANCELLATION'",
                    Long.class, submitted.paymentId());
            var finance = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            var body = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true);
            postOk(finance, path + "/verify", Map.of("note", "read the dashboard"));
            mockProvider.refundedExternally(pid(submitted), new BigDecimal("400.19"));

            postRefused(finance, path + "/to-wallet", body, 422, "REFUND_VERIFICATION_FAILED");

            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            // And the cause on record refuses it whatever the reads say.
            reset(mockProvider);
            jdbc.update("update refund set review_cause = 'REFUNDED_ANOTHER_WAY' where id = ?", refundId);
            postOk(finance, path + "/verify", Map.of("note", "read again"));
            postRefused(finance, path + "/to-wallet", body, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
        }

        // 4: a fault in the keys

        @Test
        @DisplayName("R2-4: refunds refused as 'unknown payment' during a key fault are sent again when the keys are right, not put back, and their sources are not blocked")
        void keyFaultThenFixSendsAgainAndBlocksNothing() throws Exception {
            var a = withdrawn("400", 1, "400.00");
            var b = withdrawn("400", 1, "400.00");
            var c = withdrawn("400", 1, "400.00");
            doThrow(new PaymentProviderException("gone", false, "404", ProviderFailureKind.PAYMENT_UNKNOWN, null))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).listRefunds(anyString());
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(anyString());
            paymentJobs.processRefunds();
            paymentJobs.processRefunds();
            assertThat(List.of(rstatus(a.refundId()), rstatus(b.refundId()), rstatus(c.refundId()))).contains("REJECTED");
            reset(mockProvider);

            paymentJobs.processRefunds();
            paymentJobs.processRefunds();
            paymentJobs.processRefunds();

            for (var w : List.of(a, b, c)) {
                assertThat(rstatus(w.refundId())).describedAs("refund " + w.refundId()).isEqualTo("COMPLETED");
                assertThat(reversalsOf(w.refundId())).isZero();
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
                assertThat(mockProvider.refundedOf(w.providerPaymentId())).isEqualByComparingTo("400.00");
                assertLedger(w.buyer());
            }
            assertThat(count("select count(*) from audit_log where action = 'REFUND_REQUEUED' and entity_id in (?, ?, ?)",
                    a.refundId(), b.refundId(), c.refundId())).isGreaterThanOrEqualTo(1);
            // The restaurant can withdraw again.
            var again = withdrawn("400", 1, "400.00");
            assertThat(rstatus(again.refundId())).isNotEqualTo("REVERSED");
        }

        @Test
        @DisplayName("R2-4: a refusal as 'unknown payment' that keeps contradicting the provider's answers is sent again only so many times, then goes to a person; nothing is put back or blocked")
        void aContradictedUnknownRefusalIsBounded() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            doThrow(new PaymentProviderException("gone", false, "404", ProviderFailureKind.PAYMENT_UNKNOWN, null))
                    .when(mockProvider).refund(anyString(), any(), anyString(), any());
            for (int i = 0; i < 8; i++) {
                paymentJobs.processRefunds();
            }
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("review_cause")).isEqualTo("CONTRADICTED_REFUSAL");
            assertThat((Integer) row.get("attempts")).isEqualTo(5);
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
        }

        // 5: sources

        @Test
        @DisplayName("R2-5: an outlet whose two oldest sources are dead payments can still withdraw from the sources behind them; the dead ones are blocked")
        void twoDeadSourcesDoNotStrandTheOutlet() throws Exception {
            var buyer = newBuyer();
            var s1 = captured(buyer, "400", 1);
            var s2 = captured(buyer, "400", 1);
            var s3 = captured(buyer, "400", 1);
            creditWallet(s1, "400.00");
            creditWallet(s2, "400.00");
            creditWallet(s3, "400.00");
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(pid(s1));
            doThrow(new PaymentProviderException("gone", false, "NOT_FOUND")).when(mockProvider).inspect(pid(s2));

            var done = withdraw(buyer, "400.00", UUID.randomUUID().toString());

            assertThat(done.at("/data/parts")).hasSize(1);
            assertThat(payRow(s1.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("PAYMENT_UNKNOWN");
            assertThat(payRow(s2.paymentId()).get("provider_refund_blocked_reason")).isEqualTo("PAYMENT_UNKNOWN");
            assertThat(payRow(s3.paymentId()).get("provider_refund_blocked_at")).isNull();
            assertThat(balance(buyer)).isEqualByComparingTo("800.00");
            assertLedger(buyer);
        }

        // 6: 'already refunded' that nothing corroborates

        @Test
        @DisplayName("R2-6: 'already refunded' that the provider's own reads do not show goes to a person, is not put back and blocks nothing")
        void anUncorroboratedAlreadyRefundedGoesToReview() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.ALREADY_REFUNDED, "400", "The payment has been fully refunded already");

            paymentJobs.processRefunds();

            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("review_cause")).isEqualTo("CONTRADICTED_REFUSAL");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("R2-6: 'already refunded' that our own other refunds explain in full is corroborated: put back")
        void anAlreadyRefundedOurOwnRefundsExplainIsPutBack() throws Exception {
            var w = withdrawn("400", 1, "400.00");
            // Another refund of ours (its own receipt) took the whole payment.
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "other-key-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-999999",
                            Map.of("mandi_refund_id", "999999"), "normal"));
            refuseRefundsWith(ProviderFailureKind.ALREADY_REFUNDED, "400", "The payment has been fully refunded already");

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("R2-6: a payer refunded another way after a reversal is a CRITICAL error and pauses the outlet's withdrawals, as a late refund of ours does")
        void aForeignRefundAfterAReversalRaisesTheAlarm(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            reset(mockProvider);
            var foreign = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00"));
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?",
                    w.refundId());

            // The first look only notes it: one read that disagrees is not enough to pause a restaurant.
            paymentJobs.auditReversedRefunds();
            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("SUSPECT");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
            // Six minutes later it is still there: a refund, not a lag.
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).containsPattern("ERROR.*CRITICAL refund " + w.refundId() + " was REVERSED but the payer was refunded another way");
            var row = refundRow(w.refundId());
            assertThat(row.get("late_success_at")).isNotNull();
            assertThat(row.get("verified_result")).isEqualTo("FOREIGN_REFUND");
            assertThat(payRow(w.source().paymentId()).get("review_required_at")).isNotNull();
            assertThat(count("select count(*) from outbox_event where event_type = 'WithdrawalDoubleCredit' and aggregate_id = ?",
                    w.refundId())).isEqualTo(1);
            var paused = withdrawCall(w.buyer(), "100.00", UUID.randomUUID().toString());
            assertThat(paused.getStatus()).isEqualTo(503);
            assertThat(error(paused).get("code").asText()).isEqualTo("WITHDRAWALS_PAUSED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(foreign.providerRefundId()).isNotNull();
        }

        @Test
        @DisplayName("R3-3: a mismatch seen once is not an alarm: it is noted, and gone at the second look a few minutes later, which is the lag it was")
        void aMismatchThatIsGoneAtTheSecondLookRaisesNoAlarm(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            reset(mockProvider);
            var real = mockProvider.inspect(w.providerPaymentId());
            var inflated = new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(),
                    real.captured(), real.amount(), new BigDecimal("400.00"), real.method(), real.methodDetail(), real.fee(),
                    real.createdAt());
            // The payment's figure shows a refund the list does not yet, at the first look only.
            doReturn(inflated).doCallRealMethod().when(mockProvider).inspect(w.providerPaymentId());
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?",
                    w.refundId());

            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("SUSPECT");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
            // Not read again before the minutes have passed: the second look is not the same moment's second read.
            clearInvocations(mockProvider);
            paymentJobs.auditReversedRefunds();
            verify(mockProvider, never()).inspect(w.providerPaymentId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("SUSPECT");

            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
            assertThat(withdrawStatus(w.buyer(), "100.00", UUID.randomUUID().toString())).describedAs("not paused").isNotEqualTo(503);
        }

        @Test
        @DisplayName("R3-3: our own refund that carries a provider refund id, counted in the payment's figure and not yet on the list, is explained: no suspicion at all")
        void anOwnRefundNotYetOnTheListIsExplained(CapturedOutput output) throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            // Part R, refused for a reason we do not recognise: put back.
            long r = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            assertThat(rstatus(r)).isEqualTo("REVERSED");
            reset(mockProvider);
            // A second part of the same payment, sent a moment before the audit: the provider made it.
            long r2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            paymentJobs.processRefunds();
            assertThat(rstatus(r2)).isEqualTo("COMPLETED");
            assertThat(refundRow(r2).get("provider_refund_id")).isNotNull();
            // ... which the provider's payment shows (400 refunded) and its list does not show yet.
            String pid = pid(source);
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var all = (List<PaymentProvider.ProviderRefundEntry>) invocation.callRealMethod();
                return all.stream().filter(e -> !"mandi-refund-".concat(String.valueOf(r2)).equals(e.receipt())).toList();
            }).when(mockProvider).listRefunds(pid);
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?", r);

            paymentJobs.auditReversedRefunds();
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", r);
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + r);
            assertThat(refundRow(r).get("verified_result")).describedAs("never even suspected").isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(r).get("late_success_at")).isNull();
        }

        @Test
        @DisplayName("R3-3: a suspicion older than a few minutes is read again whatever the schedule says, and a real foreign refund is still an alarm at that second look")
        void aSuspicionIsReadAgainAfterAFewMinutes(CapturedOutput output) throws Exception {
            var w = withdrawn("400", 1, "400.00");
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Something we have never seen");
            paymentJobs.processRefunds();
            reset(mockProvider);
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("100.00"));
            // At the last point of the schedule: nothing further would ever be read.
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 14 day), verified_at = null where id = ?", w.refundId());

            paymentJobs.auditReversedRefunds();
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("SUSPECT");
            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).containsPattern("ERROR.*CRITICAL refund " + w.refundId() + " was REVERSED but the payer was refunded another way");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNotNull();
        }

        // 7: the audit backs off

        @ParameterizedTest
        @ValueSource(strings = {"429", "401", "403"})
        @DisplayName("R2-7: the reversal audit stops for the run when the provider limits or refuses us, and asks again next run")
        void theAuditBacksOff(String code) throws Exception {
            var reversed = new java.util.ArrayList<Withdrawn>();
            for (int i = 0; i < 3; i++) {
                reversed.add(rejected("REJECTED_OTHER"));
            }
            paymentJobs.processRefunds();
            for (var w : reversed) {
                assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
                jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?",
                        w.refundId());
            }
            reset(mockProvider);
            doThrow(new PaymentProviderException("no", false, code)).when(mockProvider).listRefunds(anyString());

            paymentJobs.auditReversedRefunds();

            verify(mockProvider, times(1)).listRefunds(anyString());
            for (var w : reversed) {
                assertThat(refundRow(w.refundId()).get("verified_at")).describedAs("still due").isNull();
            }
            reset(mockProvider);
            paymentJobs.auditReversedRefunds();
            for (var w : reversed) {
                assertThat(refundRow(w.refundId()).get("verified_at")).describedAs("read now").isNotNull();
            }
        }

        @Test
        @DisplayName("R2-7: the audit reads every page, not only the first")
        void theAuditReadsEveryPage() throws Exception {
            var reversed = new java.util.ArrayList<Withdrawn>();
            for (int i = 0; i < 3; i++) {
                reversed.add(rejected("REJECTED_OTHER"));
            }
            paymentJobs.processRefunds();
            for (var w : reversed) {
                jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?",
                        w.refundId());
            }
            setJobField("auditPageSize", 1);
            try {
                paymentJobs.auditReversedRefunds();
            } finally {
                setJobField("auditPageSize", 200);
            }
            for (var w : reversed) {
                // (verified_result is NONE_OF_OURS from the reversal itself: what moves when the audit reads is verified_at.)
                assertThat(refundRow(w.refundId()).get("verified_at")).describedAs("refund " + w.refundId() + " read").isNotNull();
            }
        }

        // 8: no second connection under the wallet lock

        @Test
        @DisplayName("R2-8: a dispute refund needs one connection, not two: with every other connection of the pool taken, it still completes")
        void aDisputeRefundNeedsOneConnection() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            var order = submit(buyer, "100", 1);
            assertThat(walletService.debitFor(buyer.outletId(), order.orderId(), new BigDecimal("1000.00"))).isTrue();
            var pool = (com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource();
            var config = pool.getHikariConfigMXBean();
            long timeout = config.getConnectionTimeout();
            var held = new java.util.ArrayList<java.sql.Connection>();
            try {
                // Every connection but one is somebody else's; a second connection would wait and time out.
                for (int i = 0; i < pool.getMaximumPoolSize() - 1; i++) {
                    held.add(pool.getConnection());
                }
                config.setConnectionTimeout(500);
                new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status ->
                        walletService.creditDisputeRefund(order.orderId(), new BigDecimal("300.00"), "dispute-pool-" + order.orderId()));
            } finally {
                config.setConnectionTimeout(timeout);
                for (var connection : held) {
                    connection.close();
                }
            }
            BigDecimal refunded = jdbc.queryForObject("select coalesce(sum(amount), 0) from wallet_transaction "
                    + "where supplier_order_id = ? and kind = 'DISPUTE_REFUND'", BigDecimal.class, order.orderId());
            assertThat(refunded).isEqualByComparingTo("300.00");
        }

        // ── third review, finding 1: a foreign refund that is not this part's ─────

        /** A withdrawal part of {@code amount} from a payment of {@code paymentAmount}, sent to review because of an unrelated refund. */
        private Withdrawn heldForForeign(String unitPrice, int quantity, String amount, String foreignAmount,
                                         PaymentProvider.ProviderRefundEntry[] foreignOut) throws Exception {
            var w = withdrawn(unitPrice, quantity, amount);
            foreignOut[0] = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal(foreignAmount));
            // A refusal that does not go away (the card behind the payment cannot take refunds).
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            reset(mockProvider);
            return w;
        }

        private Map<String, Object> notThisPart(String note, String evidence, String... providerRefundIds) {
            var body = new java.util.HashMap<String, Object>();
            body.put("note", note);
            body.put("evidence", evidence);
            body.put("providerRefundIds", List.of(providerRefundIds));
            return body;
        }

        @Test
        @DisplayName("R3-1: an unrelated dashboard refund on the payment no longer strands the part: two people record that it is not this part's, a fresh verification then shows none of ours, and the part goes back once")
        void anUnrelatedRefundCanBeRecordedAndThePartPutBack(CapturedOutput output) throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            // Before: the dead end. Verified as a foreign refund, refused, and no marking it done against a refund of another amount.
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            postRefused(a, refund + "/recredit", recreditBody("the ₹100 is a goodwill refund", true, null), 422, "REFUND_VERIFICATION_FAILED");
            postRefused(a, refund + "/mark-completed", Map.of("providerRefundId", id, "note", "x"), 422, "REFUND_VERIFICATION_FAILED");

            // The first person's request records nothing; the second person's, naming the same refund, records it.
            var first = postOk(a, refund + "/foreign-refund-not-this-part",
                    notThisPart("goodwill refund on a different order", "support ticket 4411", id));
            assertThat(first.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(first.get("done").asBoolean()).isFalse();
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            postRefused(a, refund + "/foreign-refund-not-this-part",
                    notThisPart("goodwill refund on a different order", "support ticket 4411", id), 409, "SECOND_APPROVER_REQUIRED");
            var second = postOk(b, refund + "/foreign-refund-not-this-part",
                    notThisPart("goodwill refund on a different order", "support ticket 4411", id));
            assertThat(second.get("done").asBoolean()).isTrue();
            var row = refundRow(w.refundId());
            assertThat(row.get("review_cause")).isEqualTo("FOREIGN_NOT_THIS_PART");
            assertThat(row.get("review_ref")).isEqualTo(id);
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("ops_action")).isNull();
            assertThat(balance(w.buyer())).describedAs("a judgement moves no money").isEqualByComparingTo("0");
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                    + "and action = 'REFUND_OPS_FOREIGN_NOT_THIS_PART'", w.refundId())).isEqualTo(1);

            // The re-credit still needs its own fresh verification, which now shows none of ours; nothing else changed.
            postRefused(a, refund + "/recredit", recreditBody("checked", true, null), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read after the record")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            // After an exclusion the put-back needs evidence and two different people, whatever the part is worth.
            postRefused(a, refund + "/recredit", recreditBody("the only other refund is recorded as unrelated", true, null), 400, "VALIDATION_ERROR");
            var unrelated = recreditBody("the only other refund is recorded as unrelated", true, "support ticket 4411: the 100 was a goodwill refund on another order");
            assertThat(postOk(a, refund + "/recredit", unrelated).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, refund + "/recredit", unrelated, 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            var done = postOk(b, refund + "/recredit", unrelated);
            assertThat(done.get("status").asText()).isEqualTo("REVERSED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(mockProvider.refundedOf(w.providerPaymentId())).describedAs("nothing was sent to the card").isEqualByComparingTo("100.00");
            assertLedger(w.buyer());

            // And the late success watch does not read the recorded refund as a double credit.
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();
            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
        }

        @Test
        @DisplayName("R3-1: what two people recorded is exactly those refund ids and their amounts: another foreign refund, or more refunded than the recorded ones explain, is still never 'none of ours'")
        void theRecordIsExactlyThoseRefunds() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            postOk(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(b, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");

            // A second foreign refund appears, of a different id (and the same amount): the record does not cover it.
            var second = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("100.00"));
            var why = recreditBody("checked", true, "ticket 1: the first refund is a goodwill refund");
            assertThat(postOk(a, refund + "/recredit", why).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(b, refund + "/recredit", why, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read again")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // Recording the first again changes nothing about the second; the second needs its own two people.
            postOk(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(b, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read again")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            postOk(a, refund + "/foreign-refund-not-this-part", notThisPart("second goodwill", "ticket 2", second.providerRefundId()));
            postOk(b, refund + "/foreign-refund-not-this-part", notThisPart("second goodwill", "ticket 2", second.providerRefundId()));
            assertThat(refundRow(w.refundId()).get("review_ref")).isEqualTo(id + "," + second.providerRefundId());
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read again")).get("result").asText()).isEqualTo("NONE_OF_OURS");

            // More refunded than the recorded refunds explain, and not on the list (a refund the list does not show yet).
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(),
                    real.captured(), real.amount(), new BigDecimal("250.00"), real.method(), real.methodDetail(), real.fee(),
                    real.createdAt())).when(mockProvider).inspect(w.providerPaymentId());
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read the figure")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            postRefused(a, refund + "/recredit", recreditBody("checked", true, null), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("R3-1: the record is two people always, bound to the refunds named; a read, a retry or a day voids the first person's request; it needs a fresh look showing a foreign refund, evidence, and a refund the provider lists that is not ours and did not fail")
        void theRecordNeedsTwoPeopleAndItsPreconditions() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var other = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("50.00"));
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";

            // Needs a look at the provider that found a foreign refund, from the last ten minutes.
            postRefused(a, path, notThisPart("goodwill", "ticket 1", id), 409, "REFUND_VERIFICATION_REQUIRED");
            postOk(a, refund + "/verify", Map.of("note", "read"));
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 11 minute) where id = ?", w.refundId());
            postRefused(a, path, notThisPart("goodwill", "ticket 1", id), 409, "REFUND_VERIFICATION_REQUIRED");
            postOk(a, refund + "/verify", Map.of("note", "read"));
            // Evidence and the ids are required.
            var noEvidence = notThisPart("goodwill", "", id);
            postRefused(a, path, noEvidence, 400, null);
            postRefused(a, path, notThisPart("goodwill", "ticket 1"), 400, null);
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();

            // The second person must name the same refund: another is the first request of a new one.
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", id)).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("ops_action").toString()).startsWith("NTP");
            var differentIds = postOk(b, path, notThisPart("goodwill", "ticket 1", other.providerRefundId()));
            assertThat(differentIds.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            assertThat(refundRow(w.refundId()).get("ops_action_by")).isEqualTo(b.userId());

            // A read of the refund voids a request: the person who reads is the next to ask, and asks first.
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(postOk(b, path, notThisPart("goodwill", "ticket 1", id)).get("awaitingSecondApprover").asBoolean()).isTrue();
            // So does a day.
            jdbc.update("update refund set ops_action_at = date_sub(ops_action_at, interval 25 hour) where id = ?", w.refundId());
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", id)).get("awaitingSecondApprover").asBoolean()).isTrue();

            // A refund the provider does not list on this payment, and one of ours, are refused at the FIRST approval too
            // (B3): nothing is recorded, no request waits for a second person.
            postOk(a, refund + "/verify", Map.of("note", "read"));
            postRefused(a, path, notThisPart("goodwill", "ticket 1", "rfnd_nobody"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).describedAs("no request recorded for a refund that is not there").isNull();
            assertThat(count("select count(*) from audit_log where entity_id = ? and action = 'REFUND_OPS_APPROVAL_REQUESTED' "
                    + "and reason like '%rfnd_nobody%'", w.refundId())).isZero();
            var ours = mockProvider.refund(w.providerPaymentId(), new BigDecimal("10.00"), "some-other-key-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-999999", Map.of("mandi_refund_id", "999999"), "normal"));
            var oursListed = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .filter(e -> "mandi-refund-999999".equals(e.receipt())).findFirst().orElseThrow();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            postRefused(a, path, notThisPart("goodwill", "ticket 1", oursListed.providerRefundId()), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            // Listed when the first person asks, gone from the list when the second acts: the second person's own read refuses.
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", id)).get("awaitingSecondApprover").asBoolean()).isTrue();
            doReturn(List.of()).when(mockProvider).listRefunds(w.providerPaymentId());
            postRefused(b, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
            reset(mockProvider);
            assertThat(ours).isNotNull();
            assertThat(refundRow(w.refundId()).get("review_cause")).describedAs("nothing recorded").isEqualTo("REFUNDED_ANOTHER_WAY");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("R3-1: an ambiguous part is not touched within the minimum time since its last send: the provider's list may not show its refund yet")
        void theRecordWaitsForTheMinimumAgeOfAnAmbiguousPart() throws Exception {
            var w = inReview("1000", 1, "400.00", "AMBIGUOUS");
            var foreign = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("100.00"));
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?", w.refundId());
                assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
                postRefused(a, path, notThisPart("goodwill", "ticket 1", foreign.providerRefundId()), 409, "REFUND_VERIFICATION_REQUIRED");
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 45 minute) where id = ?", w.refundId());
                assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", foreign.providerRefundId()))
                        .get("awaitingSecondApprover").asBoolean()).isTrue();
                assertThat(postOk(b, path, notThisPart("goodwill", "ticket 1", foreign.providerRefundId())).get("done").asBoolean()).isTrue();
                // The recorded part still needs the evidence for an ambiguous outcome to be put back, and its own verification.
                postOk(a, refund + "/verify", Map.of("note", "read"));
                postRefused(a, refund + "/recredit", recreditBody("checked", true, null), 400, "VALIDATION_ERROR");
                var body = recreditBody("checked", true, "the provider's answer, ticket 7");
                assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
                var done = postOk(b, refund + "/recredit", body);
                assertThat(done.get("status").asText()).isEqualTo("REVERSED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("R3-1: above the threshold the re-credit after the record still needs a second person, and puts the part back once")
        void aBigPartStillNeedsASecondPersonToBePutBack() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("2000", 10, "12000.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            postOk(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(b, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(a, refund + "/verify", Map.of("note", "read"));

            var big = recreditBody("checked", true, "ticket 1: the 100 refund is unrelated goodwill");
            assertThat(postOk(a, refund + "/recredit", big).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, refund + "/recredit", big, 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(postOk(b, refund + "/recredit", big).get("status").asText()).isEqualTo("REVERSED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("12000.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("R3-1: one dashboard refund of the whole payment closes a smaller part when the operator confirms; without the confirmation, or when the payment is not refunded in full, it does not")
        void aRefundOfTheWholePaymentClosesASmallerPart() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "1000.00", foreign);
            String id = foreign[0].providerRefundId();
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");

            var refused = postRefused(finance, refund + "/mark-completed", Map.of("providerRefundId", id, "note", "the dashboard refund"),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("1000.00 at the provider").contains("400.00").contains("confirmPayerRefundedInFull");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");

            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", id);
            body.put("note", "the payer got all of it back in the dashboard, checked");
            body.put("confirmPayerRefundedInFull", true);
            var done = postOk(finance, refund + "/mark-completed", body);

            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(done.get("done").asBoolean()).isTrue();
            var row = refundRow(w.refundId());
            assertThat(row.get("provider_refund_id")).describedAs("not a one-to-one claim of the foreign refund").isNull();
            assertThat(row.get("review_cause")).isEqualTo("COMPLETED_BY_OTHER_REFUND");
            assertThat(row.get("review_ref")).isEqualTo(id);
            assertThat(row.get("completed_at")).isNotNull();
            assertThat(balance(w.buyer())).describedAs("the payer has the money; the wallet is not credited").isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? "
                    + "and action = 'REFUND_OPS_MARK_COMPLETED'", w.refundId())).isEqualTo(1);
            assertLedger(w.buyer());
            // Once: a completed part is not closed again.
            postRefused(finance, refund + "/mark-completed", body, 409, "INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("R3-1: a refund of another amount cannot close a part when the payment is not refunded in full, or when it is one of ours")
        void aForeignRefundClosesAPartOnlyWhenThePaymentIsRefundedInFullAndTheRefundIsNotOurs() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", foreign[0].providerRefundId());
            body.put("note", "the dashboard refund");
            body.put("confirmPayerRefundedInFull", true);

            var notFull = postRefused(finance, refund + "/mark-completed", body, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(notFull.get("message").asText()).contains("refunded in full");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");

            // The payment is refunded in full, by a refund of ours for another part.
            var mine = mockProvider.refund(w.providerPaymentId(), new BigDecimal("900.00"), "other-part-key-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-999999", Map.of("mandi_refund_id", "999999"), "normal"));
            var oursListed = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .filter(e -> "mandi-refund-999999".equals(e.receipt())).findFirst().orElseThrow();
            body.put("providerRefundId", oursListed.providerRefundId());
            var ours = postRefused(finance, refund + "/mark-completed", body, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(ours.get("message").asText()).contains("one of ours");
            assertThat(mine).isNotNull();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("R3-1: one dashboard refund covering two parts closes both, each once, with no unique provider refund id in the way")
        void oneRefundCoveringTwoPartsClosesBoth() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "800", 1);
            creditWallet(source, "800.00");
            long p1 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            var foreign = mockProvider.refundedExternally(pid(source), new BigDecimal("800.00"));
            paymentJobs.processRefunds();
            assertThat(List.of(rstatus(p1), rstatus(p2))).containsOnly("NEEDS_REVIEW");
            var finance = ops("OPS_FINANCE");
            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", foreign.providerRefundId());
            body.put("note", "one dashboard refund of the whole payment, for both withdrawals");
            body.put("confirmPayerRefundedInFull", true);

            assertThat(postOk(finance, ADMIN + "/refunds/" + p1 + "/mark-completed", body).get("status").asText()).isEqualTo("COMPLETED");
            assertThat(postOk(finance, ADMIN + "/refunds/" + p2 + "/mark-completed", body).get("status").asText()).isEqualTo("COMPLETED");

            assertThat(count("select count(*) from refund where review_cause = 'COMPLETED_BY_OTHER_REFUND' and review_ref = ? and status = 'COMPLETED'",
                    foreign.providerRefundId())).isEqualTo(2);
            assertThat(count("select count(*) from refund where payment_id = ? and provider_refund_id is not null", source.paymentId())).isZero();
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("R3-1: the parts closed against one foreign refund never add up to more than it")
        void partsClosedAgainstOneRefundNeverExceedIt() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "800.00");
            long p1 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            var f1 = mockProvider.refundedExternally(pid(source), new BigDecimal("500.00"));
            var f2 = mockProvider.refundedExternally(pid(source), new BigDecimal("500.00"));
            paymentJobs.processRefunds();
            assertThat(List.of(rstatus(p1), rstatus(p2))).containsOnly("NEEDS_REVIEW");
            var finance = ops("OPS_FINANCE");
            java.util.function.Function<String, Map<String, Object>> against = ref -> {
                var body = new java.util.HashMap<String, Object>();
                body.put("providerRefundId", ref);
                body.put("note", "dashboard refunds, checked");
                body.put("confirmPayerRefundedInFull", true);
                return body;
            };

            assertThat(postOk(finance, ADMIN + "/refunds/" + p1 + "/mark-completed", against.apply(f1.providerRefundId()))
                    .get("status").asText()).isEqualTo("COMPLETED");
            var refused = postRefused(finance, ADMIN + "/refunds/" + p2 + "/mark-completed", against.apply(f1.providerRefundId()),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("already closed against it");
            assertThat(rstatus(p2)).isEqualTo("NEEDS_REVIEW");
            assertThat(postOk(finance, ADMIN + "/refunds/" + p2 + "/mark-completed", against.apply(f2.providerRefundId()))
                    .get("status").asText()).isEqualTo("COMPLETED");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("R3-1: closing a large part against a refund made outside Mandi needs a second person, bound to that refund")
        void closingALargePartNeedsASecondPerson() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("2000", 10, "12000.00", "20000.00", foreign);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", foreign[0].providerRefundId());
            body.put("note", "dashboard refund of the whole payment");
            body.put("confirmPayerRefundedInFull", true);

            var first = postOk(a, path, body);
            assertThat(first.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(first.get("done").asBoolean()).isFalse();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            postRefused(a, path, body, 409, "SECOND_APPROVER_REQUIRED");
            var second = postOk(b, path, body);
            assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(second.get("done").asBoolean()).isTrue();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());
        }

        // ── third review, finding 4: two tests the reviewer's mutations survived ──

        @Test
        @DisplayName("R3-4: a person putting back a part refused as an 'unknown payment' whose payment the provider does know does not block the source")
        void aPersonPuttingBackAContradictedUnknownRefusalBlocksNothing() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            jdbc.update("update refund set review_cause = 'CONTRADICTED_REFUSAL' where id = ?", w.refundId());
            var finance = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(postOk(finance, refund + "/verify", Map.of("note", "read: the provider lists the payment")).get("result").asText())
                    .isEqualTo("NONE_OF_OURS");

            var done = postOk(finance, refund + "/recredit", recreditBody("the provider knows the payment", true, null));

            assertThat(done.get("status").asText()).isEqualTo("REVERSED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).describedAs("a healthy source stays usable").isNull();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("R3-4: two payments the provider does not know, after a refund it answered on the same page, are reviewed as gone at once: not held, and no configuration fault")
        void unknownPaymentsAfterAnAnsweredRefundAreReviewedNotHeld(CapturedOutput output) throws Exception {
            var answered = rejected("REJECTED_OTHER");
            var goneA = rejected("PAYMENT_UNKNOWN");
            var goneB = rejected("PAYMENT_UNKNOWN");
            // The queue reads the longest-waiting first: the answered one, then the two unknown ones.
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 60 minute) where id = ?", answered.refundId());
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 50 minute) where id = ?", goneA.refundId());
            jdbc.update("update refund set updated_at = date_sub(utc_timestamp(6), interval 40 minute) where id = ?", goneB.refundId());
            unknownToProvider(goneA.providerPaymentId());
            unknownToProvider(goneB.providerPaymentId());

            paymentJobs.processRefunds();

            assertThat(rstatus(answered.refundId())).isEqualTo("REVERSED");
            for (var gone : List.of(goneA, goneB)) {
                assertThat(rstatus(gone.refundId())).describedAs("refund " + gone.refundId()).isEqualTo("NEEDS_REVIEW");
                assertThat(refundRow(gone.refundId()).get("review_cause")).isEqualTo("PAYMENT_GONE");
                assertThat(reversalsOf(gone.refundId())).isZero();
                assertThat(balance(gone.buyer())).isEqualByComparingTo("0");
            }
            assertThat(output.getOut()).doesNotContain("Configuration fault: the provider does not know payments");
        }

        // ── third review: dispute refunds on two outlets at once ─────────────

        @Test
        @DisplayName("R3-2: dispute refunds on two different outlets credited at the same moment never deadlock: 40 concurrent pairs, every credit made exactly once")
        void disputeRefundsOnTwoOutletsNeverDeadlock() throws Exception {
            // The reference does not exist yet, and a locking read of a key that is not there takes a shared gap lock:
            // two transactions on different wallets (so the wallet lock does not put them in order) with neighbouring
            // references then each waited for the other's gap to insert into it. The read is now of the order's own rows.
            var buyerA = newBuyer();
            var buyerB = newBuyer();
            creditWallet(captured(buyerA, "1000", 1), "1000.00");
            creditWallet(captured(buyerB, "1000", 1), "1000.00");
            var orderA = submit(buyerA, "100", 1);
            var orderB = submit(buyerB, "100", 1);
            assertThat(walletService.debitFor(buyerA.outletId(), orderA.orderId(), new BigDecimal("1000.00"))).isTrue();
            assertThat(walletService.debitFor(buyerB.outletId(), orderB.orderId(), new BigDecimal("1000.00"))).isTrue();
            long base = 800_000_000L + (System.nanoTime() % 100_000) * 100;
            var problems = new CopyOnWriteArrayList<String>();
            var pool = Executors.newFixedThreadPool(2);
            int rounds = 40;
            try {
                for (int round = 0; round < rounds; round++) {
                    var go = new CountDownLatch(1);
                    String refA = "dispute-refund-" + (base + 2L * round);
                    String refB = "dispute-refund-" + (base + 2L * round + 1);
                    var fa = pool.submit(() -> {
                        go.await();
                        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                            walletService.creditDisputeRefund(orderA.orderId(), new BigDecimal("10.00"), refA);
                            return "ok";
                        });
                    });
                    var fb = pool.submit(() -> {
                        go.await();
                        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                            walletService.creditDisputeRefund(orderB.orderId(), new BigDecimal("10.00"), refB);
                            return "ok";
                        });
                    });
                    go.countDown();
                    for (var f : List.of(fa, fb)) {
                        try {
                            f.get(60, TimeUnit.SECONDS);
                        } catch (java.util.concurrent.ExecutionException ex) {
                            problems.add(String.valueOf(ex.getCause()));
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(problems).describedAs("credits that failed (a deadlock is one)").isEmpty();
            for (var order : List.of(orderA, orderB)) {
                assertThat(count("select count(*) from wallet_transaction where supplier_order_id = ? and kind = 'DISPUTE_REFUND'",
                        order.orderId())).isEqualTo(rounds);
            }
            assertThat(balance(buyerA)).isEqualByComparingTo("400.00");
            assertThat(balance(buyerB)).isEqualByComparingTo("400.00");
        }

        // ── round 2 live campaign: a part on a payment the provider does not know ─────────────────────

        /** As Razorpay answers for a payment it does not know: the refunds list is 200 with nothing in it, the payment read is not found. */
        private void unknownLikeRazorpay(String providerPaymentId) {
            doReturn(List.of()).when(mockProvider).listRefunds(providerPaymentId);
            doThrow(new PaymentProviderException("Razorpay does not know that id (HTTP 400)", false, "NOT_FOUND"))
                    .when(mockProvider).inspect(providerPaymentId);
        }

        /** A payment made after the ones already there, which the provider knows: what the proof of the keys is read from. */
        private Submitted laterKnownPayment() throws Exception {
            return captured(newBuyer(), "400", 1);
        }

        private static final String ACCOUNT_EVIDENCE = "Payment is on the retired Razorpay account acc_OLD (old keys, closed in July); support ticket 88 confirms no refund there";

        private Map<String, Object> otherAccountBody(String note, String evidence, boolean confirmNoRefund, boolean confirmOtherAccount) {
            var body = recreditBody(note, confirmNoRefund, evidence);
            body.put("confirmPaymentOnOtherAccount", confirmOtherAccount);
            return body;
        }

        private java.util.List<String> actionsOf(long refundId) {
            return jdbc.queryForList("select action from audit_log where entity_type = 'REFUND' and entity_id = ? order by id",
                    String.class, refundId);
        }

        @Test
        @DisplayName("B1: a part on a payment Razorpay does not know (list 200 with nothing, payment not found) is verified as UNKNOWN_PAYMENT once the keys are proven, and put back by two different people, once, with the ledger right and every step audited")
        void aPartOnAnUnknownPaymentIsPutBackByTwoPeople() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            var later = laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            var verified = postOk(a, refund + "/verify", Map.of("note", "read: Razorpay does not know the payment"));

            assertThat(verified.get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(refundRow(w.refundId()).get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(jdbc.queryForObject("select reason from audit_log where entity_id = ? and action = 'REFUND_UNKNOWN_PAYMENT_PROOF'",
                    String.class, w.refundId())).describedAs("the record says which payment proved the keys").contains(pid(later));

            // Every gate of this kind of re-credit, at every call: evidence of the account, the confirmations, the note.
            String recredit = refund + "/recredit";
            postRefused(a, recredit, otherAccountBody("checked", null, true, true), 400, "VALIDATION_ERROR");
            postRefused(a, recredit, otherAccountBody("checked", "short", true, true), 400, "VALIDATION_ERROR");
            postRefused(a, recredit, otherAccountBody("checked", ACCOUNT_EVIDENCE, true, false), 400, "VALIDATION_ERROR");
            postRefused(a, recredit, otherAccountBody("checked", ACCOUNT_EVIDENCE, false, true), 400, "VALIDATION_ERROR");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();

            // ₹400 is far below the ordinary threshold and still needs two people.
            var asked = postOk(a, recredit, otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true));
            assertThat(asked.get("done").asBoolean()).isFalse();
            assertThat(asked.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("ops_action")).isEqualTo("RECREDIT_UNKNOWN");
            postRefused(a, recredit, otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true), 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            // The second person must say the same things too.
            postRefused(b, recredit, otherAccountBody("checked", null, true, true), 400, "VALIDATION_ERROR");
            postRefused(b, recredit, otherAccountBody("checked", ACCOUNT_EVIDENCE, true, false), 400, "VALIDATION_ERROR");

            var done = postOk(b, recredit, otherAccountBody("second look: same account, same ticket", ACCOUNT_EVIDENCE, true, true));

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(done.get("status").asText()).isEqualTo("REVERSED");
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("REVERSED");
            assertThat(row.get("reversed_by")).isEqualTo(b.userId());
            assertThat(row.get("verified_result")).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(row.get("ops_action")).isNull();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("PAYMENT_UNKNOWN");
            assertThat(actionsOf(w.refundId())).contains("REFUND_OPS_VERIFY", "REFUND_UNKNOWN_PAYMENT_PROOF",
                    "REFUND_OPS_APPROVAL_REQUESTED", "REFUND_REVERSED", "REFUND_OPS_RECREDIT");
            assertThat(jdbc.queryForObject("select reason from audit_log where entity_id = ? and action = 'REFUND_OPS_RECREDIT'",
                    String.class, w.refundId())).contains("confirmed").contains("acc_OLD");
            assertLedger(w.buyer());

            // Replayed by either person, or by the system: nothing more moves.
            postRefused(a, recredit, otherAccountBody("again", ACCOUNT_EVIDENCE, true, true), 409, "INVALID_STATE_TRANSITION");
            postRefused(b, recredit, otherAccountBody("again", ACCOUNT_EVIDENCE, true, true), 409, "INVALID_STATE_TRANSITION");
            paymentJobs.processRefunds();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertLedger(w.buyer());

            // The late success watch reads it too: nothing of ours on the list, the payment still unknown: no alarm, and it is not asked again at once.
            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(refundRow(w.refundId()).get("verified_at")).isNotNull();
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
        }

        @Test
        @DisplayName("B1: a provider that does not know EVERY payment (wrong keys, mode or base URL) is a configuration fault: verify is refused, nothing is recorded, and no re-credit can follow")
        void wrongKeysProveNothing() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            laterKnownPayment();
            doReturn(List.of()).when(mockProvider).listRefunds(anyString());
            doThrow(new PaymentProviderException("unknown", false, "NOT_FOUND")).when(mockProvider).inspect(anyString());
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            var error = postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE");

            assertThat(error.get("message").asText()).contains("could not be proven").contains("keys");
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();
            assertThat(actionsOf(w.refundId())).doesNotContain("REFUND_OPS_VERIFY", "REFUND_UNKNOWN_PAYMENT_PROOF");
            postRefused(a, refund + "/recredit", otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("B1: the newest payment has no later payment to prove the keys with, so it is not decided as unknown; the reads that prove nothing (a failed read, another order's answer) refuse too")
        void noProofNoDecision() throws Exception {
            // A payment the provider knows that was made BEFORE this one: no proof of anything about a later payment.
            laterKnownPayment();
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            // Nothing newer than this payment exists.
            assertThat(postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE").get("message").asText())
                    .contains("could not be proven");
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();

            // A later payment whose read fails for any reason but "unknown" proves nothing.
            var later = laterKnownPayment();
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).inspect(pid(later));
            assertThat(postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE").get("message").asText())
                    .contains("could not be proven").contains("failed").contains("Try again");
            doThrow(new PaymentProviderException("limit", true, "429")).when(mockProvider).inspect(pid(later));
            postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE");

            // A later payment the provider answers as another order's is not a proof either.
            doReturn(new PaymentProvider.ProviderPaymentFacts(pid(later), "order_someone_else",
                    PaymentProvider.ProviderPaymentStatus.CAPTURED, true, new BigDecimal("400.00"), BigDecimal.ZERO,
                    "upi", null, null, Instant.now())).when(mockProvider).inspect(pid(later));
            postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE");
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();
            // And a failed read of the newest later payment stops the proof, even with an older later one that would answer.
            var newest = laterKnownPayment();
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException())).when(mockProvider).inspect(pid(newest));
            doReturn(new PaymentProvider.ProviderPaymentFacts(pid(later),
                    jdbc.queryForObject("select provider_order_id from payment where id = ?", String.class, later.paymentId()),
                    PaymentProvider.ProviderPaymentStatus.CAPTURED, true, new BigDecimal("400.00"), BigDecimal.ZERO,
                    "upi", null, null, Instant.now())).when(mockProvider).inspect(pid(later));
            assertThat(postRefused(a, refund + "/verify", Map.of("note", "read"), 503, "PROVIDER_UNAVAILABLE").get("message").asText())
                    .contains("failed");
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("B1: the proof is the newest of the later payments that answers: one the provider does not know is skipped, an older later one that answers proves the keys")
        void anOlderLaterPaymentCanProveTheKeys() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            var olderLater = laterKnownPayment();
            var newestLater = laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            unknownLikeRazorpay(pid(newestLater));
            var a = ops("OPS_FINANCE");

            var verified = postOk(a, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "read"));

            assertThat(verified.get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(jdbc.queryForObject("select reason from audit_log where entity_id = ? and action = 'REFUND_UNKNOWN_PAYMENT_PROOF'",
                    String.class, w.refundId())).contains(pid(olderLater)).doesNotContain(pid(newestLater));
        }

        @Test
        @DisplayName("B1: a payment the provider DOES know that shows a refund that is not ours is never put back: verified as FOREIGN_REFUND, refused even with every confirmation; and a verification of 'unknown' that the provider's answer inside the call contradicts puts nothing back")
        void aKnownPaymentWithAForeignRefundIsStillRefused() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            laterKnownPayment();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();

            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            postRefused(a, refund + "/recredit", otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            // A second part: verified while the provider did not know the payment, approved by the first person; then the
            // payment is known again and shows a foreign refund: the second person's own read inside the call refuses.
            var second = inReview("1000", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            unknownLikeRazorpay(second.providerPaymentId());
            String refund2 = ADMIN + "/refunds/" + second.refundId();
            assertThat(postOk(a, refund2 + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(postOk(a, refund2 + "/recredit", otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true))
                    .get("awaitingSecondApprover").asBoolean()).isTrue();
            reset(mockProvider);
            mockProvider.refundedExternally(second.providerPaymentId(), new BigDecimal("100.00"));

            postRefused(b, refund2 + "/recredit", otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true), 422, "REFUND_VERIFICATION_FAILED");

            assertThat(rstatus(second.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(second.refundId())).isZero();
            assertThat(balance(second.buyer())).isEqualByComparingTo("0");
            assertLedger(second.buyer());
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("B1: a verification made while the provider knew the payment does not carry a re-credit once it does not: 409, verify again, nothing put back")
        void aKnownVerificationDoesNotCarryAnUnknownPayment() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            unknownLikeRazorpay(w.providerPaymentId());

            var error = postRefused(a, refund + "/recredit", recreditBody("checked", true, "ticket"), 409, "REFUND_VERIFICATION_REQUIRED");

            assertThat(error.get("message").asText()).contains("Verify this refund again");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("B1: a part on an unknown payment is not put back within the minimum time since its last send, whatever kind of failure it was; and a verification older than ten minutes does not carry it")
        void theUnknownPathKeepsTheMinimumAgeAndTheFreshVerification() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            var body = otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true);
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?", w.refundId());
                postRefused(a, refund + "/recredit", body, 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 11 minute) where id = ?", w.refundId());
            postRefused(a, refund + "/recredit", body, 409, "REFUND_VERIFICATION_REQUIRED");
            postOk(a, refund + "/verify", Map.of("note", "again"));
            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            // A new verification by anyone voids the request.
            postOk(b, refund + "/verify", Map.of("note", "again"));
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(reversalsOf(w.refundId())).isZero();
        }

        @Test
        @DisplayName("B1: the system's own paths are unchanged on an unknown payment: the refund job sends it to review as gone and records nothing, and its hourly read decides nothing and needs no proof")
        void theSystemPathIsUnchanged() throws Exception {
            var w = rejected("PAYMENT_UNKNOWN");
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());

            paymentJobs.processRefunds();

            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("PAYMENT_GONE");
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            var hourly = reversals.run(w.refundId(), null, com.costonomy.mp.payment.service.WithdrawalReversalService.Intent.VERIFY);
            assertThat(hourly).isEqualTo(com.costonomy.mp.payment.service.WithdrawalReversalService.Result.UNKNOWN_AT_PROVIDER);
            assertThat(refundRow(w.refundId()).get("verified_result")).isNull();
            assertThat(actionsOf(w.refundId())).doesNotContain("REFUND_UNKNOWN_PAYMENT_PROOF");
        }

        @Test
        @DisplayName("B1: a cancellation refund on a payment the provider does not know is sent to the wallet only by two people, each with the account evidence and confirmation; once")
        void toWalletOnAnUnknownPaymentNeedsTwoPeople() throws Exception {
            var submitted = submit("400.19", 1);
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            long refundId = jdbc.queryForObject("select id from refund where payment_id = ? and reason = 'CANCELLATION'",
                    Long.class, submitted.paymentId());
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            laterKnownPayment();
            unknownLikeRazorpay(pid(submitted));
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            var plain = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true);
            var full = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true,
                    "evidence", ACCOUNT_EVIDENCE, "confirmPaymentOnOtherAccount", true);

            assertThat(postOk(a, path + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            postRefused(a, path + "/to-wallet", plain, 400, "VALIDATION_ERROR");
            var asked = postOk(a, path + "/to-wallet", full);
            assertThat(asked.get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, path + "/to-wallet", full, 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");

            var done = postOk(b, path + "/to-wallet", full);

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(rstatus(refundId)).isEqualTo("REVERSED");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
            assertThat(count("select count(*) from wallet_transaction where refund_id in "
                    + "(select id from refund where idempotency_key = ?) and kind = 'REFUND'", "cancel-wallet-" + refundId)).isEqualTo(1);
            postRefused(b, path + "/to-wallet", full, 409, null);
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
            assertLedger(submitted.buyer());
        }

        // ── the check of the unknown-payment ops path (round 5) ───────────────────────────────────

        private static String sha16(String text) throws Exception {
            var sha = java.security.MessageDigest.getInstance("SHA-256").digest(text.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(sha, 0, 8);
        }

        private java.util.List<String> reasonsOf(long refundId, String action) {
            return jdbc.queryForList("select reason from audit_log where entity_type = 'REFUND' and entity_id = ? and action = ? order by id",
                    String.class, refundId, action);
        }

        /** A cancellation refund to the original payment, left in review (a UPI payment is never refunded to the original source). */
        private long cancellationInReview(Submitted submitted) throws Exception {
            payAndConfirm(submitted, "upi");
            assertThat(cancelAs(submitted)).isEqualTo(200);
            paymentJobs.settleCancellations();
            paymentJobs.processRefunds();
            long refundId = jdbc.queryForObject("select id from refund where payment_id = ? and reason = 'CANCELLATION'",
                    Long.class, submitted.paymentId());
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            return refundId;
        }

        private static final String LONG_NOTE = "N".repeat(400);
        private static final String LONG_EVIDENCE = ("account acc_OLD retired in July; ticket 88 says nothing was sent. " + "E".repeat(400)).substring(0, 400);

        @Test
        @DisplayName("F1: the longest note AND evidence a request can carry (400 and 400) on an unknown payment: the second person's re-credit SUCCEEDS (no 409), the part is REVERSED once, and exactly one REFUND_OPS_RECREDIT row exists, within the audit column, with the confirmation and the evidence's digest")
        void theLongestNoteAndEvidenceStillPutBackAndAuditOnce() throws Exception {
            assertThat(LONG_EVIDENCE).hasSize(400);
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            var body = otherAccountBody(LONG_NOTE, LONG_EVIDENCE, true, true);

            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            var done = postOk(b, refund + "/recredit", body);

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("REVERSED");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            var audits = reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT");
            assertThat(audits).describedAs("one audit row, written with the reversal").hasSize(1);
            assertThat(audits.get(0).length()).isLessThanOrEqualTo(500);
            assertThat(audits.get(0)).contains("confirmed").contains("Evidence (400 chars, sha256 " + sha16(LONG_EVIDENCE) + ")")
                    .contains("acc_OLD");
            var requested = reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED");
            assertThat(requested).hasSize(1);
            assertThat(requested.get(0).length()).isLessThanOrEqualTo(500);
            assertThat(requested.get(0)).contains("confirmed").contains("sha256 " + sha16(LONG_EVIDENCE)).contains("acc_OLD");
            assertLedger(w.buyer());
            postRefused(b, refund + "/recredit", body, 409, "INVALID_STATE_TRANSITION");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT")).hasSize(1);
        }

        @Test
        @DisplayName("F1: the same at the longest on the ordinary paths: an ambiguous part on a known payment (evidence required, one approver) and a cancellation refund sent to the wallet; each audit row fits, exactly one")
        void theLongestNoteAndEvidenceOnTheOrdinaryPaths() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            laterKnownPayment();
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));

            var done = postOk(a, refund + "/recredit", recreditBody(LONG_NOTE, true, LONG_EVIDENCE));

            assertThat(done.get("done").asBoolean()).isTrue();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            var audits = reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT");
            assertThat(audits).hasSize(1);
            assertThat(audits.get(0).length()).isLessThanOrEqualTo(500);
            assertThat(audits.get(0)).contains("sha256 " + sha16(LONG_EVIDENCE)).doesNotContain("confirmed");

            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            laterKnownPayment();
            String path = ADMIN + "/refunds/" + refundId;
            postOk(a, path + "/verify", Map.of("note", "read"));
            var toWallet = new java.util.HashMap<String, Object>(Map.of("note", LONG_NOTE, "confirmNoProviderRefund", true,
                    "evidence", LONG_EVIDENCE));
            assertThat(postOk(a, path + "/to-wallet", toWallet).get("done").asBoolean()).isTrue();
            var walletAudits = reasonsOf(refundId, "REFUND_OPS_TO_WALLET");
            assertThat(walletAudits).hasSize(1);
            assertThat(walletAudits.get(0).length()).isLessThanOrEqualTo(500);
            assertThat(walletAudits.get(0)).contains("sha256 " + sha16(LONG_EVIDENCE));
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
        }

        @Test
        @DisplayName("F1: the audit of a re-credit is written in the reversal's own transaction: when it cannot be stored, NOTHING moves (the part stays in review, no credit, no reversal audit) and the retry completes it once; it is never 'reversed but answered 409'")
        void theAuditAndTheReversalAreAtomic() throws Exception {
            var w = inReview("400", 1, "400.00", "AMBIGUOUS");
            laterKnownPayment();
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            // The database refuses exactly this kind of audit row from now on (removed after); rows already there stay valid.
            // (Every earlier re-credit audit belongs to an earlier refund, whose id is smaller.)
            jdbc.execute("alter table audit_log add constraint refuse_recredit_audit_test "
                    + "check (action <> 'REFUND_OPS_RECREDIT' or entity_id < " + w.refundId() + ")");
            try {
                var response = mvc.perform(MockMvcRequestBuilders.post(refund + "/recredit")
                                .header("Authorization", "Bearer " + a.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(recreditBody(LONG_NOTE, true, "ticket 42"))))
                        .andReturn().getResponse();
                assertThat(response.getStatus()).describedAs(response.getContentAsString()).isGreaterThanOrEqualTo(400);
            } finally {
                jdbc.execute("alter table audit_log drop check refuse_recredit_audit_test");
            }

            assertThat(rstatus(w.refundId())).describedAs("the reversal did not commit without its audit").isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(w.refundId(), "REFUND_REVERSED")).isEmpty();
            assertLedger(w.buyer());
            // With the column as it is, the retry completes it, once.
            assertThat(postOk(a, refund + "/recredit", recreditBody(LONG_NOTE, true, "ticket 42")).get("done").asBoolean()).isTrue();
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT")).hasSize(1);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F2: each person's evidence and confirmation are recorded: both approvers' audit rows of a re-credit and of a to-wallet on an unknown payment carry the confirmation, the evidence's length and digest and its first characters")
        void bothApproversEvidenceIsRecorded() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            String evidenceA = "First person: the payment is on the retired account acc_ALPHA, see ticket 41";
            String evidenceB = "Second person: checked the dashboard of acc_ALPHA, no refund of this payment there";

            postOk(a, refund + "/recredit", otherAccountBody("first look", evidenceA, true, true));
            postOk(b, refund + "/recredit", otherAccountBody("second look", evidenceB, true, true));

            var first = reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED").get(0);
            var second = reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT").get(0);
            assertThat(first).contains("first look").contains("confirmed").contains("(" + evidenceA.length() + " chars, sha256 " + sha16(evidenceA) + ")")
                    .contains("acc_ALPHA");
            assertThat(second).contains("second look").contains("confirmed").contains("(" + evidenceB.length() + " chars, sha256 " + sha16(evidenceB) + ")")
                    .contains("acc_ALPHA");

            // To the wallet: the same, for both people.
            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            laterKnownPayment();
            unknownLikeRazorpay(pid(submitted));
            String path = ADMIN + "/refunds/" + refundId;
            postOk(a, path + "/verify", Map.of("note", "read"));
            java.util.function.BiFunction<String, String, Map<String, Object>> full = (note, evidence) -> Map.of("note", note,
                    "confirmNoProviderRefund", true, "evidence", evidence, "confirmPaymentOnOtherAccount", true);
            postOk(a, path + "/to-wallet", full.apply("wallet first", evidenceA));
            postOk(b, path + "/to-wallet", full.apply("wallet second", evidenceB));

            var firstWallet = reasonsOf(refundId, "REFUND_OPS_APPROVAL_REQUESTED").get(0);
            var secondWallet = reasonsOf(refundId, "REFUND_OPS_TO_WALLET").get(0);
            assertThat(firstWallet).contains("wallet first").contains("confirmed").contains("sha256 " + sha16(evidenceA));
            assertThat(secondWallet).contains("wallet second").contains("confirmed").contains("sha256 " + sha16(evidenceB))
                    .contains("acc_ALPHA");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("400.19");
        }

        @Test
        @DisplayName("F4: a part on an unknown payment that is put back BLOCKS its source as PAYMENT_UNKNOWN whatever its own failure kind was (ambiguous, none, an unexplained refusal), so the next withdrawal does not draw from it")
        void anUnknownPathPutBackBlocksTheSource() throws Exception {
            for (String kind : new String[]{"AMBIGUOUS", null, "REJECTED_OTHER"}) {
                var w = inReview("400", 1, "400.00", kind);
                laterKnownPayment();
                unknownLikeRazorpay(w.providerPaymentId());
                var a = ops("OPS_FINANCE");
                var b = ops("OPS_FINANCE");
                String refund = ADMIN + "/refunds/" + w.refundId();
                postOk(a, refund + "/verify", Map.of("note", "read"));
                var body = otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true);
                postOk(a, refund + "/recredit", body);

                postOk(b, refund + "/recredit", body);

                assertThat(rstatus(w.refundId())).describedAs("kind " + kind).isEqualTo("REVERSED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
                assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).describedAs("kind " + kind)
                        .isEqualTo("PAYMENT_UNKNOWN");
                assertThat(count("select count(*) from audit_log where entity_type = 'PAYMENT' and entity_id = ? "
                        + "and action = 'PAYMENT_REFUND_BLOCKED'", w.source().paymentId())).isEqualTo(1);
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("F5: the late success watch of a part put back on an unknown payment whose refunds LIST itself answers not-found reads it once and moves on: marked read, not asked again at the next run")
        void theWatchMarksAReadWhenTheListIsNotFound() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            jdbc.update("update refund set status = 'REVERSED', reversed_at = date_sub(utc_timestamp(6), interval 11 minute), "
                    + "verified_at = null, verified_result = 'UNKNOWN_PAYMENT' where id = ?", w.refundId());
            doThrow(new PaymentProviderException("Razorpay does not know that id (HTTP 400)", false, "NOT_FOUND"))
                    .when(mockProvider).listRefunds(w.providerPaymentId());

            paymentJobs.auditReversedRefunds();

            assertThat(refundRow(w.refundId()).get("verified_at")).describedAs("marked as read").isNotNull();
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("UNKNOWN_PAYMENT");
            verify(mockProvider, times(1)).listRefunds(w.providerPaymentId());
            paymentJobs.auditReversedRefunds();
            paymentJobs.auditReversedRefunds();
            verify(mockProvider, times(1)).listRefunds(w.providerPaymentId());
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
            // A part reversed the ordinary way whose list says not-found is NOT marked: it is asked again.
            var other = inReview("400", 1, "400.00", "REJECTED_OTHER");
            jdbc.update("update refund set status = 'REVERSED', reversed_at = date_sub(utc_timestamp(6), interval 11 minute), "
                    + "verified_at = null, verified_result = 'NONE_OF_OURS' where id = ?", other.refundId());
            doThrow(new PaymentProviderException("Razorpay does not know that id (HTTP 400)", false, "NOT_FOUND"))
                    .when(mockProvider).listRefunds(other.providerPaymentId());
            paymentJobs.auditReversedRefunds();
            assertThat(refundRow(other.refundId()).get("verified_at")).isNull();
        }

        @Test
        @DisplayName("F7/X6: the provider is read AND the keys are proved again inside the completing call: a payment that verified fine, was approved, and then has no later payment the provider knows, is refused 503 for the second person and nothing moves")
        void theCompletingCallRepeatsTheProof() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            var later = laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            var body = otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true);
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            // Now every later payment is unknown too: the keys are not proven any more (every payment looks unknown).
            unknownLikeRazorpay(pid(later));

            var error = postRefused(b, refund + "/recredit", body, 503, "PROVIDER_UNAVAILABLE");

            assertThat(error.get("message").asText()).contains("could not be proven");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT")).isEmpty();
            assertLedger(w.buyer());
            // The same call, with the proof back, completes it.
            reset(mockProvider);
            unknownLikeRazorpay(w.providerPaymentId());
            assertThat(postOk(b, refund + "/recredit", body).get("done").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("F7/X1: to-wallet by ONE person of a cancellation refund verified while the provider knew the payment, once it no longer knows it: 409 and nothing moves, with or without a proof of the keys")
        void toWalletIgnoresNoReadThatFindsThePaymentUnknownAfterAKnownVerification() throws Exception {
            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            laterKnownPayment();
            var a = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            assertThat(postOk(a, path + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            unknownLikeRazorpay(pid(submitted));
            var plain = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true);

            var error = postRefused(a, path + "/to-wallet", plain, 409, "REFUND_VERIFICATION_REQUIRED");

            assertThat(error.get("message").asText()).contains("Verify this refund again");
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(refundId, "REFUND_OPS_TO_WALLET")).isEmpty();
            assertLedger(submitted.buyer());
        }

        @Test
        @DisplayName("F7/X2: to-wallet on an unknown payment keeps the minimum time since the last send whatever kind of failure it was (here an unexplained refusal, not an ambiguous one)")
        void toWalletOnAnUnknownPaymentKeepsTheMinimumAge() throws Exception {
            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            jdbc.update("update refund set failure_kind = 'REJECTED_OTHER' where id = ?", refundId);
            laterKnownPayment();
            unknownLikeRazorpay(pid(submitted));
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            postOk(a, path + "/verify", Map.of("note", "read"));
            var full = Map.<String, Object>of("note", "provider declines this payment", "confirmNoProviderRefund", true,
                    "evidence", ACCOUNT_EVIDENCE, "confirmPaymentOnOtherAccount", true);
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?", refundId);
                postRefused(a, path + "/to-wallet", full, 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(refundRow(refundId).get("ops_action")).isNull();
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", refundId);
                assertThat(postOk(a, path + "/to-wallet", full).get("awaitingSecondApprover").asBoolean()).isTrue();
                // Sent again in between: the second person is refused too.
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?", refundId);
                postRefused(b, path + "/to-wallet", full, 409, "REFUND_VERIFICATION_REQUIRED");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("F8: a part that carries a provider refund id (it was sent from the other account) is refused on the unknown-payment path, by the re-credit at the first call, by the to-wallet, and by the reversal itself under its lock; the way out is to check that refund")
        void aPartWithAProviderRefundIdIsNotClosedAsUnknown() throws Exception {
            String refundIdOnOtherAccount = "rfnd_other_" + UUID.randomUUID().toString().substring(0, 8);
            var w = inReview("400", 1, "400.00", "PROVIDER_FAILED");
            jdbc.update("update refund set provider_refund_id = ? where id = ?", refundIdOnOtherAccount, w.refundId());
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            var body = otherAccountBody("checked", ACCOUNT_EVIDENCE + ", and " + refundIdOnOtherAccount + " was not processed", true, true);

            var error = postRefused(a, refund + "/recredit", body, 409, "INVALID_STATE_TRANSITION");

            assertThat(error.get("message").asText()).contains(refundIdOnOtherAccount).contains("mark-completed")
                    .contains("confirmProcessedOnOtherAccount").contains("engineering");
            assertThat(refundRow(w.refundId()).get("ops_action")).describedAs("no request recorded").isNull();
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED")).isEmpty();
            // Not even with the evidence naming that refund id, and not for the second person.
            postRefused(b, refund + "/recredit", body, 409, "INVALID_STATE_TRANSITION");
            // Under the reversal's own lock too (a race past the front door).
            assertThatThrownBy(() -> reversals.run(w.refundId(), b.userId(),
                    com.costonomy.mp.payment.service.WithdrawalReversalService.Intent.RECREDIT, true))
                    .isInstanceOf(com.costonomy.mp.common.error.BusinessException.class).hasMessageContaining(refundIdOnOtherAccount);
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertLedger(w.buyer());

            // A cancellation refund that carries one: the same.
            var submitted = submit("400.19", 1);
            long cancellation = cancellationInReview(submitted);
            jdbc.update("update refund set provider_refund_id = ? where id = ?", refundIdOnOtherAccount + "_c", cancellation);
            laterKnownPayment();
            unknownLikeRazorpay(pid(submitted));
            String path = ADMIN + "/refunds/" + cancellation;
            postOk(a, path + "/verify", Map.of("note", "read"));
            postRefused(a, path + "/to-wallet", Map.of("note", "x", "confirmNoProviderRefund", true,
                    "evidence", ACCOUNT_EVIDENCE, "confirmPaymentOnOtherAccount", true), 409, "INVALID_STATE_TRANSITION");
            assertThat(rstatus(cancellation)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            // A part with no refund id of ours goes through (the other tests).
        }

        // ── round 3 of the live campaign: B2 (a full refund made by hand must never be excluded), B1 (legacy rows), B3 ──

        /** The live case: the payment refunded IN FULL by hand at the provider after a part of it was refused. */
        private Withdrawn heldForFullForeignRefund(String unitPrice, String part, PaymentProvider.ProviderRefundEntry[] foreignOut) throws Exception {
            var w = withdrawn(unitPrice, 1, part);
            var amount = mockProvider.inspect(w.providerPaymentId()).amount();
            foreignOut[0] = mockProvider.refundedExternally(w.providerPaymentId(), amount);
            refuseRefundsWith(ProviderFailureKind.ALREADY_REFUNDED, "400", "The payment has already been fully refunded");
            paymentJobs.processRefunds();
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(row.get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            reset(mockProvider);
            return w;
        }

        /** As if the two people had recorded the refund as not this part's before the gates existed, and a stale check said none of ours. */
        private void forceExclusionAndCleanVerification(long refundId, String providerRefundId) {
            jdbc.update("update refund set review_cause = 'FOREIGN_NOT_THIS_PART', review_ref = ?, verified_result = 'NONE_OF_OURS', "
                    + "verified_at = utc_timestamp(6), ops_action = null, ops_action_by = null, ops_action_at = null where id = ?",
                    providerRefundId, refundId);
        }

        private static final String UNRELATED = "ticket 4411: the refund is a goodwill refund on another order, not this part";

        @Test
        @DisplayName("B2 (live double payout): the payer was refunded the WHOLE payment by hand, then a part of it refused: two people cannot record that refund as 'not this part's' (422 at the first approval and at the second, nothing recorded), and the verification says refunded another way")
        void aFullRefundMadeByHandCannotBeExcluded() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForFullForeignRefund("430.50", "50.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");

            var refused = postRefused(a, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("worth at least this part").contains("confirmPayerRefundedInFull");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED")).isEmpty();
            postRefused(b, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
            // The second approval refuses too when the first was made before the provider showed the full refund.
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            // The exits are still there.
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("B2: even with an exclusion forced into the record (a refund worth the whole payment, which no two people can record) and a clean 'none of ours' verification, the part is not put back: the record does not hold, the refund is foreign again, and recredit refuses at the second person's own read (422), nothing moves; and the system path sends it to review")
        void thePayerAlreadyCoveredIsNeverPutBack() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForFullForeignRefund("430.50", "50.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            forceExclusionAndCleanVerification(w.refundId(), id);
            var body = recreditBody("the refund is unrelated", true, UNRELATED);

            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            var refused = postRefused(b, refund + "/recredit", body, 422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("refunded another way").contains("mark it completed");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("FOREIGN_REFUND");
            assertLedger(w.buyer());
            // A fresh verification now says so too, and the stale-check route is closed.
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(postRefused(a, refund + "/recredit", body, 409, "REFUND_VERIFICATION_REQUIRED").get("message").asText())
                    .contains("refunded another way");

            // The system's own pass over the same part (REJECTED again after a retry) sends it to review, never back.
            jdbc.update("update refund set status = 'REJECTED', failure_kind = 'REJECTED_OTHER', attempts = 1, "
                    + "sent_at = date_sub(utc_timestamp(6), interval 10 minute), verified_result = null where id = ?", w.refundId());
            assertThat(reversals.verifyAndReverse(w.refundId())).isEqualTo(WithdrawalReversalService.Result.NEEDS_REVIEW);
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
        }

        @Test
        @DisplayName("B2: the same for a cancellation refund sent to the wallet: a payment refunded in full by hand is not credited to the wallet as well, whatever an earlier check said")
        void toWalletRefusesAPayerAlreadyCovered() throws Exception {
            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            var amount = mockProvider.inspect(pid(submitted)).amount();
            mockProvider.refundedExternally(pid(submitted), amount);
            var a = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + refundId;
            jdbc.update("update refund set verified_result = 'NONE_OF_OURS', verified_at = utc_timestamp(6) where id = ?", refundId);

            var refused = postRefused(a, path + "/to-wallet", Map.of("note", "provider declines", "confirmNoProviderRefund", true),
                    422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("refunded another way");
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(refundId, "REFUND_OPS_TO_WALLET")).isEmpty();
        }

        @Test
        @DisplayName("F1 boundaries (payment 1000, part 400): a refund of 100 or of 399.99 (the part less a paisa) is recorded by two people and the part put back by two people, once; a refund of exactly 400.00, of 400.01 and of 600.01 is refused at the exclusion (422, nothing recorded) and, if forced into the record, is foreign again at the put-back")
        void theExclusionBoundary() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            for (String foreignAmount : new String[]{"100.00", "399.99"}) {
                var foreign = new PaymentProvider.ProviderRefundEntry[1];
                var w = heldForForeign("1000", 1, "400.00", foreignAmount, foreign);
                String id = foreign[0].providerRefundId();
                String refund = ADMIN + "/refunds/" + w.refundId();
                postOk(a, refund + "/verify", Map.of("note", "read"));
                excludeByTwo(a, b, w.refundId(), id);
                assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
                putBackByTwo(a, b, w.refundId());
                assertThat(balance(w.buyer())).describedAs(foreignAmount).isEqualByComparingTo("400.00");
                assertThat(reversalsOf(w.refundId())).isEqualTo(1);
                assertLedger(w.buyer());
            }
            for (String foreignAmount : new String[]{"400.00", "400.01", "600.01"}) {
                var foreign = new PaymentProvider.ProviderRefundEntry[1];
                var w = heldForForeign("1000", 1, "400.00", foreignAmount, foreign);
                String id = foreign[0].providerRefundId();
                String refund = ADMIN + "/refunds/" + w.refundId();
                postOk(a, refund + "/verify", Map.of("note", "read"));
                var refused = postRefused(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id), 422,
                        "REFUND_VERIFICATION_FAILED");
                assertThat(refused.get("message").asText()).describedAs(foreignAmount).contains("worth at least this part");
                assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
                postRefused(b, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
                assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
                // Forced into the record anyway: the proof does not leave it out.
                forceExclusionAndCleanVerification(w.refundId(), id);
                var body = recreditBody("unrelated", true, UNRELATED);
                postOk(a, refund + "/recredit", body);
                postRefused(b, refund + "/recredit", body, 422, "REFUND_VERIFICATION_FAILED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertThat(reversalsOf(w.refundId())).isZero();
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            }
        }

        @Test
        @DisplayName("F1 at the second approval and under the lock: the first approval was fine, then another refund is on record for the part (30 recorded, 30 named, part 50): the second person's approval is refused 422 and nothing changes")
        void theSecondApprovalChecksTheRuleAgain() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "50.00", List.of("30.00", "30.00"), ids);
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", ids.get(1))).get("awaitingSecondApprover").asBoolean()).isTrue();
            // Between the two approvals the other refund is recorded for this part (by a request that finished meanwhile).
            jdbc.update("update refund set review_cause = 'FOREIGN_NOT_THIS_PART', review_ref = ? where id = ?", ids.get(0), w.refundId());

            var refused = postRefused(b, path, notThisPart("goodwill", "ticket 1", ids.get(1)), 422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("60.00");
            assertThat(refundRow(w.refundId()).get("review_ref")).describedAs("what was recorded stays, nothing is added").isEqualTo(ids.get(0));
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_FOREIGN_NOT_THIS_PART")).isEmpty();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("F1 (E3): support refunds EXACTLY the failed part by hand (400 of a 1000 payment): it cannot be excluded at either approval, so the part is never put back on top of it; the plain mark-completed (same amount) is the exit, and the wallet is not credited")
        void aRefundOfExactlyThePartCannotBeExcluded() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "400.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            postOk(a, refund + "/verify", Map.of("note", "read"));

            var first = postRefused(a, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(first.get("message").asText()).contains("worth at least this part").contains("400.00");
            assertThat(refundRow(w.refundId()).get("ops_action")).describedAs("no request recorded").isNull();
            postRefused(b, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_FOREIGN_NOT_THIS_PART")).isEmpty();
            // The second approval refuses too, when a request is already waiting (recorded before the rule existed).
            jdbc.update("update refund set ops_action = ?, ops_action_by = ?, ops_action_at = utc_timestamp(6) where id = ?",
                    boundActionOf("NTP", id), a.userId(), w.refundId());
            postRefused(b, path, notThisPart("goodwill", "ticket 1", id), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            // Forced into the record (as if recorded before the rule): the put-back is refused, nothing is paid twice.
            forceExclusionAndCleanVerification(w.refundId(), id);
            var body = recreditBody("unrelated", true, UNRELATED);
            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(b, refund + "/recredit", body, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // The exit: the refund is the part's amount, so it closes the part as it is.
            var done = postOk(a, refund + "/mark-completed", Map.of("providerRefundId", id, "note", "support refunded the payer for exactly this part, checked"));
            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1: what is recorded as not this part's never adds up to the part: 30 and 30 against a part of 50 is refused in one request and across two (30 recorded, then 30 more), 20 and 20 against 50 is recorded and the part put back")
        void theRecordedRefundsTogetherStayBelowThePart() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            // 30 + 30 >= 50: each alone is fine, together they are not.
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "50.00", List.of("30.00", "30.00"), ids);
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            postOk(a, refund + "/verify", Map.of("note", "read"));
            var together = postRefused(a, path, notThisPart("goodwill", "ticket 1", ids.get(0), ids.get(1)), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(together.get("message").asText()).contains("add up to").contains("60.00").contains("50.00");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            // One at a time: the first is recorded, the second is refused against what is recorded already.
            excludeByTwo(a, b, w.refundId(), ids.get(0));
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(refundRow(w.refundId()).get("review_ref")).isEqualTo(ids.get(0));
            var second = postRefused(a, path, notThisPart("goodwill", "ticket 2", ids.get(1)), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(second.get("message").asText()).contains("60.00");
            assertThat(refundRow(w.refundId()).get("review_ref")).describedAs("what was recorded before stays").isEqualTo(ids.get(0));
            // The second refund is still foreign: the put-back is refused.
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // 20 + 20 < 50: both recorded, the part goes back once.
            var ids2 = new java.util.ArrayList<String>();
            var w2 = heldForForeigns("1000", "50.00", List.of("20.00", "20.00"), ids2);
            String refund2 = ADMIN + "/refunds/" + w2.refundId();
            postOk(a, refund2 + "/verify", Map.of("note", "read"));
            excludeByTwo(a, b, w2.refundId(), ids2.get(0), ids2.get(1));
            assertThat(postOk(a, refund2 + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            putBackByTwo(a, b, w2.refundId());
            assertThat(balance(w2.buyer())).isEqualByComparingTo("50.00");
            assertLedger(w2.buyer());
        }

        @Test
        @DisplayName("B2 scope: a payment refunded only by refunds of OURS that leave no room for a part (an over-refund race) is still put back by the system as before: the room rule applies only where some of the refunded amount is not ours")
        void anOverRefundOfOursIsStillPutBackBySystem() throws Exception {
            var w = rejected("REJECTED_OTHER");
            // Another refund of ours (its receipt says so) took the whole payment: 400 refunded, all of it ours, 400 more would exceed.
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "other-part-key-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-777777", Map.of("mandi_refund_id", "777777"), "normal"));

            var result = reversals.verifyAndReverse(w.refundId());

            assertThat(result).isEqualTo(WithdrawalReversalService.Result.REVERSED);
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F1/F2: a SECOND refund made by hand after a valid exclusion is foreign again: the first approval was fine, another refund then appears; the exclusion is recorded (it names only the first), the put-back is refused with nothing moved; more refunded than the list explains is foreign too")
        void aSecondForeignRefundAfterAnExclusionIsForeignAgain() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", id)).get("awaitingSecondApprover").asBoolean()).isTrue();
            var second = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("5.00"));
            assertThat(postOk(b, path, notThisPart("goodwill", "ticket 1", id)).get("done").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("review_ref")).isEqualTo(id);

            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(postRefused(a, refund + "/recredit", recreditBody("unrelated", true, UNRELATED), 409, "REFUND_VERIFICATION_REQUIRED")
                    .get("message").asText()).contains("refunded another way");
            // A clean verification that then goes stale against a refund made in between: the put-back's own read refuses.
            jdbc.update("update refund set verified_result = 'NONE_OF_OURS', verified_at = utc_timestamp(6) where id = ?", w.refundId());
            var body = recreditBody("unrelated", true, UNRELATED);
            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            var refused = postRefused(b, refund + "/recredit", body, 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("refunded another way").doesNotContain(id);
            assertThat(second.providerRefundId()).isNotBlank();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // An amount the list and the recorded refund do not explain (a refund the list does not show yet): foreign too.
            var real = mockProvider.inspect(w.providerPaymentId());
            var withoutSecond = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .filter(e -> !e.providerRefundId().equals(second.providerRefundId())).toList();
            doReturn(withoutSecond).when(mockProvider).listRefunds(w.providerPaymentId());
            doReturn(factsWithRefunded(real, real.amountRefunded())).when(mockProvider).inspect(w.providerPaymentId());
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            reset(mockProvider);
        }

        @Test
        @DisplayName("F2 scope: the late-success watch does not apply the room rule to a part put back after a valid exclusion that exceeds the payment with it (payment 1000, part 1000, goodwill 10): no alarm, nothing marked")
        void theWatchDoesNotApplyTheRoomRule(CapturedOutput output) throws Exception {
            var w = withdrawn("1000", 1, "1000.00");
            var goodwill = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("10.00"));
            paymentJobs.processRefunds();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            excludeByTwo(a, b, w.refundId(), goodwill.providerRefundId());
            postOk(a, refund + "/verify", Map.of("note", "read"));
            putBackByTwo(a, b, w.refundId());

            jdbc.update("update refund set reversed_at = date_sub(utc_timestamp(6), interval 11 minute), verified_at = null where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();
            jdbc.update("update refund set verified_at = date_sub(verified_at, interval 6 minute) where id = ?", w.refundId());
            paymentJobs.auditReversedRefunds();

            assertThat(output.getOut()).doesNotContain("CRITICAL refund " + w.refundId());
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("late_success_at")).isNull();
        }

        @Test
        @DisplayName("B2 gate 3: the put-back after an exclusion needs evidence and TWO different people even for a small part; the first person alone changes nothing")
        void thePutBackAfterAnExclusionNeedsTwoPeopleAndEvidence() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String id = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            postOk(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(b, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", id));
            postOk(a, refund + "/verify", Map.of("note", "read"));

            postRefused(a, refund + "/recredit", recreditBody("unrelated", true, null), 400, "VALIDATION_ERROR");
            postRefused(a, refund + "/recredit", recreditBody("unrelated", true, "short"), 400, "VALIDATION_ERROR");
            var asked = postOk(a, refund + "/recredit", recreditBody("unrelated", true, UNRELATED));
            assertThat(asked.get("done").asBoolean()).isFalse();
            assertThat(refundRow(w.refundId()).get("ops_action")).isEqualTo("RECREDIT");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            postRefused(a, refund + "/recredit", recreditBody("unrelated", true, UNRELATED), 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(postOk(b, refund + "/recredit", recreditBody("second look", true, UNRELATED)).get("done").asBoolean()).isTrue();
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED").get(1)).contains("Always two people").contains("sha256");
            assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
        }

        @Test
        @DisplayName("B1 (live): a LEGACY row (last_sent_at NULL) on an unknown payment is put back by two people with the PRODUCTION minimum age (30 minutes) and no workaround: the age counts from sent_at or created_at, which no approval or verification moves")
        void aLegacyRowCompletesWithTheProductionMinimumAge() throws Exception {
            for (boolean hasSentAt : new boolean[]{true, false}) {
                var w = inReview("400", 1, "400.00", "REJECTED_OTHER");
                jdbc.update("update refund set last_sent_at = null, sent_at = " + (hasSentAt ? "date_sub(utc_timestamp(6), interval 3 hour)" : "null")
                        + ", created_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                laterKnownPayment();
                unknownLikeRazorpay(w.providerPaymentId());
                var a = ops("OPS_FINANCE");
                var b = ops("OPS_FINANCE");
                String refund = ADMIN + "/refunds/" + w.refundId();
                var body = otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true);
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
                try {
                    assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
                    assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
                    // Recording the approval moved updated_at to now; the age must not depend on it.
                    assertThat(jdbc.queryForObject("select abs(timestampdiff(minute, updated_at, utc_timestamp(6))) from refund where id = ?",
                            Long.class, w.refundId())).describedAs("updated_at moved to now").isLessThan(5);
                    postRefused(a, refund + "/recredit", body, 409, "SECOND_APPROVER_REQUIRED");
                    var done = postOk(b, refund + "/recredit", body);
                    assertThat(done.get("status").asText()).isEqualTo("REVERSED");
                } finally {
                    org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
                }
                assertThat(balance(w.buyer())).isEqualByComparingTo("400.00");
                assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("B1: a legacy row younger than the minimum age is still refused (sent or created 5 minutes ago), whatever updated_at says")
        void aYoungLegacyRowIsRefused() throws Exception {
            var w = inReview("400", 1, "400.00", "REJECTED_OTHER");
            jdbc.update("update refund set last_sent_at = null, sent_at = date_sub(utc_timestamp(6), interval 5 minute), "
                    + "created_at = date_sub(utc_timestamp(6), interval 5 minute), updated_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
            laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            var body = otherAccountBody("checked", ACCOUNT_EVIDENCE, true, true);
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                postOk(a, refund + "/verify", Map.of("note", "read"));
                postRefused(a, refund + "/recredit", body, 409, "REFUND_VERIFICATION_REQUIRED");
                // Created long ago but sent 5 minutes ago (the last send we know of): refused too.
                jdbc.update("update refund set created_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                postRefused(a, refund + "/recredit", body, 409, "REFUND_VERIFICATION_REQUIRED");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("B3: the verify audit row names the payment that proved the provider's keys, bounded in length, beside the note")
        void theVerifyRowNamesTheProvingPayment() throws Exception {
            var w = inReview("400", 1, "400.00", "PAYMENT_UNKNOWN");
            var later = laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            var a = ops("OPS_FINANCE");

            postOk(a, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", LONG_NOTE));

            var row = reasonsOf(w.refundId(), "REFUND_OPS_VERIFY");
            assertThat(row).hasSize(1);
            assertThat(row.get(0)).contains("keys proven by reading payment " + pid(later)).contains("NNNN");
            assertThat(row.get(0).length()).isLessThanOrEqualTo(500);
        }

        @Test
        @DisplayName("B3: the refunds named as not this part's are checked at the provider at the FIRST approval: one that is not listed, is ours or failed is refused 422 and nothing is recorded")
        void namedRefundsAreCheckedAtTheFirstApproval() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            var a = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/foreign-refund-not-this-part";
            postOk(a, refund + "/verify", Map.of("note", "read"));

            // Not there at all, and one real one beside it: the whole request is refused.
            postRefused(a, path, notThisPart("goodwill", "ticket", foreign[0].providerRefundId(), "rfnd_garbage"), 422, "REFUND_VERIFICATION_FAILED");
            postRefused(a, path, notThisPart("goodwill", "ticket", "rfnd_garbage"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(actionsOf(w.refundId())).doesNotContain("REFUND_OPS_APPROVAL_REQUESTED");

            // Listed but failed: no money moved, nothing to record.
            mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("5.00"));
            var failed = new PaymentProvider.ProviderRefundEntry("rfnd_failed_x", new BigDecimal("5.00"),
                    PaymentProvider.ProviderRefundStatus.FAILED, null, null, Instant.now());
            var listed = new java.util.ArrayList<>(mockProvider.listRefunds(w.providerPaymentId()));
            listed.add(failed);
            doReturn(listed).when(mockProvider).listRefunds(w.providerPaymentId());
            postRefused(a, path, notThisPart("goodwill", "ticket", "rfnd_failed_x"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            reset(mockProvider);

            // The real one is accepted as the first approval, as before.
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket", foreign[0].providerRefundId()))
                    .get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(refundRow(w.refundId()).get("ops_action").toString()).startsWith("NTP");
        }

        // ── the fifth check: which foreign refunds can be excluded (F1), the room rule's scope (F2), the exit of a part
        //    that carries a refund id on an unknown payment (F3), and the mutations that survived (F4) ──

        /** As {@link #heldForForeign}, with several refunds made by hand. */
        private Withdrawn heldForForeigns(String unitPrice, String amount, List<String> foreignAmounts, List<String> idsOut) throws Exception {
            var w = withdrawn(unitPrice, 1, amount);
            for (String f : foreignAmounts) {
                idsOut.add(mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal(f)).providerRefundId());
            }
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            assertThat(refundRow(w.refundId()).get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            reset(mockProvider);
            return w;
        }

        /** Two different people record the named refunds as not this part's. */
        private void excludeByTwo(Ops a, Ops b, long refundId, String... ids) throws Exception {
            String path = ADMIN + "/refunds/" + refundId + "/foreign-refund-not-this-part";
            assertThat(postOk(a, path, notThisPart("goodwill", "ticket 1", ids)).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(postOk(b, path, notThisPart("goodwill", "ticket 1", ids)).get("done").asBoolean()).isTrue();
        }

        /** Two different people put the part back, with evidence: the second completes it. */
        private void putBackByTwo(Ops a, Ops b, long refundId) throws Exception {
            String path = ADMIN + "/refunds/" + refundId + "/recredit";
            assertThat(postOk(a, path, recreditBody("unrelated", true, UNRELATED)).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(postOk(b, path, recreditBody("second look", true, UNRELATED)).get("status").asText()).isEqualTo("REVERSED");
        }

        /** What the first approver's request is bound to (a prefix and the start of a hash of the ids), as the service makes it. */
        private static String boundActionOf(String prefix, String... ids) throws Exception {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", new java.util.TreeSet<>(List.of(ids))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefix + java.util.HexFormat.of().formatHex(digest).substring(0, 16 - prefix.length());
        }

        private static PaymentProvider.ProviderPaymentFacts factsWithRefunded(PaymentProvider.ProviderPaymentFacts real, BigDecimal refunded) {
            return new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(), real.captured(),
                    real.amount(), refunded, real.method(), real.methodDetail(), real.fee(), real.createdAt());
        }

        @Test
        @DisplayName("F2 (E1): payment 1000 fully withdrawn as one part, then a 10 goodwill refund by hand: the send is refused over-refund and the part goes to review; two people record the 10 as not this part's and two people put the 1000 back, once: wallet +1000, the payer keeps the 10, the ledger adds up")
        void aSmallGoodwillRefundDoesNotStrandAFullyWithdrawnPayment() throws Exception {
            var w = withdrawn("1000", 1, "1000.00");
            var goodwill = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("10.00"));
            paymentJobs.processRefunds();
            assertThat(refundRow(w.refundId()).get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("review_cause")).isEqualTo("REFUNDED_ANOTHER_WAY");
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");

            excludeByTwo(a, b, w.refundId(), goodwill.providerRefundId());
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("NONE_OF_OURS");
            // Evidence and two different people, as after every exclusion.
            postRefused(a, refund + "/recredit", recreditBody("unrelated", true, null), 400, "VALIDATION_ERROR");
            var body = recreditBody("unrelated", true, UNRELATED);
            assertThat(postOk(a, refund + "/recredit", body).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, refund + "/recredit", body, 409, "SECOND_APPROVER_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(postOk(b, refund + "/recredit", body).get("status").asText()).isEqualTo("REVERSED");

            assertThat(balance(w.buyer())).describedAs("the wallet gets the part once").isEqualByComparingTo("1000.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            assertThat(mockProvider.inspect(w.providerPaymentId()).amountRefunded()).describedAs("the payer keeps the goodwill refund only")
                    .isEqualByComparingTo("10.00");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_RECREDIT")).hasSize(1);
            assertLedger(w.buyer());
            // Once.
            postRefused(b, refund + "/recredit", body, 409, "INVALID_STATE_TRANSITION");
            assertThat(balance(w.buyer())).isEqualByComparingTo("1000.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
        }

        @Test
        @DisplayName("F4/T2: after an exclusion the SECOND person also has to give evidence: without it (none, or fewer than 15 characters) the put-back is refused 400 for the second person, the first's request stays, nothing moves")
        void theSecondPersonAlsoGivesEvidenceAfterAnExclusion() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            postOk(a, refund + "/verify", Map.of("note", "read"));
            excludeByTwo(a, b, w.refundId(), foreign[0].providerRefundId());
            postOk(a, refund + "/verify", Map.of("note", "read"));
            assertThat(postOk(a, refund + "/recredit", recreditBody("unrelated", true, UNRELATED)).get("awaitingSecondApprover").asBoolean()).isTrue();

            postRefused(b, refund + "/recredit", recreditBody("second look", true, null), 400, "VALIDATION_ERROR");
            postRefused(b, refund + "/recredit", recreditBody("second look", true, "too short"), 400, "VALIDATION_ERROR");

            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(refundRow(w.refundId()).get("ops_action")).isEqualTo("RECREDIT");
            assertThat(postOk(b, refund + "/recredit", recreditBody("second look", true, UNRELATED)).get("status").asText()).isEqualTo("REVERSED");
            assertLedger(w.buyer());
        }


        // ── the sixth check (rv17f): a refund counted both as excluded and as ours (F1), the exit for a hand refund that
        //    covers a part without refunding the payment in full (F2), a recorded refund that fails (F3), the second
        //    person's call that finds the part adopted (F4), the room rule with claims (F5), a pending refund (B1) ──

        private void partOfOursCompletedAgainst(Submitted source, String amount, String providerRefundId) {
            jdbc.update("insert into refund (payment_id, supplier_order_id, amount, reason, status, idempotency_key, attempts, "
                            + "provider_refund_id, completed_at) values (?, ?, ?, 'WALLET_WITHDRAWAL', 'COMPLETED', ?, 1, ?, utc_timestamp(6))",
                    source.paymentId(), source.orderId(), new BigDecimal(amount), "rv-y-" + UUID.randomUUID(), providerRefundId);
        }

        private String verifyResult(Ops who, long refundId) throws Exception {
            return postOk(who, ADMIN + "/refunds/" + refundId + "/verify", Map.of("note", "read")).get("result").asText();
        }

        private Map<String, Object> coversBody(String providerRefundId, String evidence) {
            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", providerRefundId);
            body.put("note", "support refunded the part and a goodwill amount in one refund, checked");
            if (evidence != null) {
                body.put("evidence", evidence);
            }
            body.put("confirmRefundCoversThisPart", true);
            return body;
        }

        private static final String COVERS_EVIDENCE = "ticket 5120: support refunded 400 for the failed withdrawal and 100 goodwill";

        @Test
        @DisplayName("F1 control: payment 1000, part X 500 refused, H 300 by hand excluded for X; the payment's figure says 600 refunded while the list shows only H: FOREIGN_REFUND")
        void anUnexplainedFigureAfterAnExclusionIsForeign() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "500.00", "300.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), h);
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("600.00"))).when(mockProvider).inspect(w.providerPaymentId());
            try {
                assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            } finally {
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("F1: the same, but H has since become OURS (part Y of 300 closed against H): H is counted once, as ours, not again as excluded; the hidden 300 of the figure keeps X foreign and it cannot be put back")
        void aRefundThatBecameOursIsNotAlsoCountedAsExcluded() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "500.00", "300.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), h);
            partOfOursCompletedAgainst(w.source(), "300.00", h);
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("600.00"))).when(mockProvider).inspect(w.providerPaymentId());
            try {
                assertThat(verifyResult(a, w.refundId())).describedAs("600 refunded; ours (H) explains 300").isEqualTo("FOREIGN_REFUND");
                postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/recredit", recreditBody("unrelated", true, UNRELATED), 409,
                        "REFUND_VERIFICATION_REQUIRED");
            } finally {
                reset(mockProvider);
            }
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("F1: the exclusion of H on X stays valid when H became ours and the figure agrees with the list: X is still put back, once")
        void anExclusionWhoseRefundBecameOursStillLetsThePartBePutBackWhenNothingIsHidden() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "500.00", "300.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), h);
            partOfOursCompletedAgainst(w.source(), "300.00", h);

            assertThat(verifyResult(a, w.refundId())).isEqualTo("NONE_OF_OURS");
            putBackByTwo(a, b, w.refundId());
            assertThat(balance(w.buyer())).isEqualByComparingTo("500.00");
            assertThat(reversalsOf(w.refundId())).isEqualTo(1);
            // (No ledger check: part Y of ours is a bare row made by the test, with no wallet debit behind it.)
        }

        @Test
        @DisplayName("F1: after H became ours (another part closed against it) it no longer counts toward the sum of the refunds recorded on X: a further refund K of 250 can be recorded (250 < 500) although H + K would be 550")
        void aRecordedRefundThatBecameOursDoesNotCountTowardANewExclusion() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "500.00", "300.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), h);
            partOfOursCompletedAgainst(w.source(), "300.00", h);
            String k = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("250.00")).providerRefundId();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");

            excludeByTwo(a, b, w.refundId(), k);

            assertThat(verifyResult(a, w.refundId())).isEqualTo("NONE_OF_OURS");
            assertThat(refundRow(w.refundId()).get("review_ref").toString()).contains(k);
        }

        @Test
        @DisplayName("RV-B: one hand refund H of 499.99 on a payment of 1000 withdrawn as two parts of 500, both refused: each part may exclude H (two people each) and both are put back: wallet 1000 while the payer holds 499.99 (bounded by H < the smallest part; a judgement the rule cannot catch, see D-110)")
        void sameRefundExcludedOnTwoPartsIsBoundedByTheSmallestPart() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            long x = withdraw(buyer, "500.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long y = withdraw(buyer, "500.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            var h = mockProvider.refundedExternally(pid(source), new BigDecimal("499.99")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            assertThat(rstatus(x)).isEqualTo("NEEDS_REVIEW");
            assertThat(rstatus(y)).isEqualTo("NEEDS_REVIEW");
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            for (long id : new long[]{x, y}) {
                assertThat(verifyResult(a, id)).isEqualTo("FOREIGN_REFUND");
                excludeByTwo(a, b, id, h);
                assertThat(verifyResult(a, id)).isEqualTo("NONE_OF_OURS");
                putBackByTwo(a, b, id);
            }
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(mockProvider.inspect(pid(source)).amountRefunded()).isEqualByComparingTo("499.99");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("RV-B2: two hand refunds 250 + 250 on that payment: neither part can be put back (each must exclude both, 500 is not below 500)")
        void distinctRefundsCannotEachBeExcludedByOnePart() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "1000.00");
            long x = withdraw(buyer, "500.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            withdraw(buyer, "500.00", UUID.randomUUID().toString());
            var h1 = mockProvider.refundedExternally(pid(source), new BigDecimal("250.00")).providerRefundId();
            var h2 = mockProvider.refundedExternally(pid(source), new BigDecimal("250.00")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, x)).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, x, h1);
            assertThat(verifyResult(a, x)).isEqualTo("FOREIGN_REFUND");
            postRefused(a, ADMIN + "/refunds/" + x + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 2", h2), 422,
                    "REFUND_VERIFICATION_FAILED");
            assertThat(balance(buyer)).isEqualByComparingTo("0");
        }

        private List<PaymentProvider.ProviderRefundEntry> listedWithStatus(String providerPaymentId, String id,
                                                                           PaymentProvider.ProviderRefundStatus status) {
            var listed = new java.util.ArrayList<PaymentProvider.ProviderRefundEntry>();
            for (var e : mockProvider.listRefunds(providerPaymentId)) {
                listed.add(e.providerRefundId().equals(id)
                        ? new PaymentProvider.ProviderRefundEntry(e.providerRefundId(), e.amount(), status, e.receipt(), e.mandiRefundId(), e.createdAt())
                        : e);
            }
            return listed;
        }

        @Test
        @DisplayName("F3: two refunds recorded (10 and 15, part 400); the 10 later FAILS at the provider: it counts as 0, the record still holds (15 < 400) and the part is not poisoned: verify answers NONE_OF_OURS")
        void aRecordedRefundThatFailsCountsAsNothingAndDoesNotVoidTheRecord() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "400.00", List.of("10.00", "15.00"), ids);
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), ids.get(0), ids.get(1));
            assertThat(verifyResult(a, w.refundId())).isEqualTo("NONE_OF_OURS");
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(listedWithStatus(w.providerPaymentId(), ids.get(0), PaymentProvider.ProviderRefundStatus.FAILED))
                    .when(mockProvider).listRefunds(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("15.00"))).when(mockProvider).inspect(w.providerPaymentId());
            try {
                assertThat(verifyResult(a, w.refundId())).describedAs("the failed 10 moved nothing").isEqualTo("NONE_OF_OURS");
            } finally {
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("F3: the 10 failed and a NEW refund of 20 appears: foreign again; recording the 20 is accepted although the failed 10 is still on the record (it counts 0), and X is then put back")
        void aFailedRecordedRefundDoesNotBlockRecordingANewOne() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "400.00", List.of("10.00", "15.00"), ids);
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), ids.get(0), ids.get(1));
            String twenty = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("20.00")).providerRefundId();
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(listedWithStatus(w.providerPaymentId(), ids.get(0), PaymentProvider.ProviderRefundStatus.FAILED))
                    .when(mockProvider).listRefunds(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("35.00"))).when(mockProvider).inspect(w.providerPaymentId());
            try {
                assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
                // The failed id itself is still refused as a NEW exclusion: it moved no money.
                var again = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/foreign-refund-not-this-part",
                        notThisPart("x", "ticket", ids.get(0)), 422, "REFUND_VERIFICATION_FAILED");
                assertThat(again.get("message").asText()).contains("failed at the provider");
                excludeByTwo(a, b, w.refundId(), twenty);
                assertThat(refundRow(w.refundId()).get("review_ref").toString()).contains(ids.get(0)).contains(ids.get(1)).contains(twenty);
                assertThat(verifyResult(a, w.refundId())).isEqualTo("NONE_OF_OURS");
            } finally {
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("F3: a recorded refund the provider no longer lists: the next exclusion is refused 422 naming THAT refund, not a false 'add up to' sum")
        void aRecordedRefundThatIsNoLongerListedIsNamedInTheRefusal() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "400.00", List.of("10.00"), ids);
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), ids.get(0));
            String twenty = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("20.00")).providerRefundId();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            var withoutTheTen = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .filter(e -> !e.providerRefundId().equals(ids.get(0))).toList();
            doReturn(withoutTheTen).when(mockProvider).listRefunds(w.providerPaymentId());
            try {
                var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/foreign-refund-not-this-part",
                        notThisPart("goodwill", "ticket 7", twenty), 422, "REFUND_VERIFICATION_FAILED");
                assertThat(refused.get("message").asText()).contains(ids.get(0)).contains("any more")
                        .doesNotContain("add up to");
            } finally {
                reset(mockProvider);
            }
        }

        @Test
        @DisplayName("F4: first approval of the other-account exit, then the provider knows the payment and lists the part's own refund: the second person's call adopts it: 200 ADOPTED, part COMPLETED, wallet not credited, audited, nothing else closed")
        void theSecondPersonOfTheOtherAccountExitFindsThePartAdopted() throws Exception {
            String other = "rfnd_other_" + UUID.randomUUID().toString().substring(0, 8);
            var w = carryingARefundOfAnotherAccount(other);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("UNKNOWN_PAYMENT");
            assertThat(postOk(a, refund + "/mark-completed", processedBody(other, PROCESSED_EVIDENCE, true)).get("awaitingSecondApprover").asBoolean()).isTrue();
            reset(mockProvider);
            doReturn(List.of(new PaymentProvider.ProviderRefundEntry(other, new BigDecimal("400.00"), PaymentProvider.ProviderRefundStatus.COMPLETED, null, null, Instant.now())))
                    .when(mockProvider).listRefunds(w.providerPaymentId());
            try {
                var done = postOk(b, refund + "/mark-completed", processedBody(other, PROCESSED_EVIDENCE, true));
                assertThat(done.get("result").asText()).isEqualTo("ADOPTED");
                assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
                assertThat(done.get("done").asBoolean()).isTrue();
            } finally {
                reset(mockProvider);
            }
            assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED")).hasSize(1);
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED").get(0)).contains("adopted");
            assertLedger(w.buyer());
        }

        @Test
        @DisplayName("F4: after an exclusion, a put-back refused because a NEW refund appeared names that refund instead of 'more has gone back than our refunds explain'")
        void aRefusalAfterAnExclusionNamesTheNewRefund() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "10.00", foreign);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), foreign[0].providerRefundId());
            assertThat(verifyResult(a, w.refundId())).isEqualTo("NONE_OF_OURS");
            String fresh = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("20.00")).providerRefundId();
            String path = ADMIN + "/refunds/" + w.refundId() + "/recredit";
            assertThat(postOk(a, path, recreditBody("unrelated", true, UNRELATED)).get("awaitingSecondApprover").asBoolean()).isTrue();
            var refused = postRefused(b, path, recreditBody("second look", true, UNRELATED), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains(fresh).doesNotContain("more has gone back than our refunds explain");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("F5 (R2/R3): a valid exclusion together with a CLAIMED refund where the room still binds: payment 1000 refunded 900 (700 claimed by part Z, 200 goodwill excluded on X=400): 700 + 400 > 1000, X is refused; with 500 claimed it is not (500 + 400 <= 1000) and X is put back")
        void aValidExclusionTogetherWithAClaimedRefundStillBindsTheRoom() throws Exception {
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            var w = inReview("1000", 1, "400.00", "REJECTED_OTHER");
            var claimed = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("700.00"));
            partClaimed(w.source(), "700.00", claimed.providerRefundId());
            String goodwill = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("200.00")).providerRefundId();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w.refundId(), goodwill);
            assertThat(verifyResult(a, w.refundId())).describedAs("700 + 400 > 1000: the payer is covered").isEqualTo("FOREIGN_REFUND");
            postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/recredit", recreditBody("unrelated", true, UNRELATED), 409,
                    "REFUND_VERIFICATION_REQUIRED");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();

            // Control: with only 500 claimed the room is there (500 + 400 <= 1000).
            var w2 = inReview("1000", 1, "400.00", "REJECTED_OTHER");
            var claimed2 = mockProvider.refundedExternally(w2.providerPaymentId(), new BigDecimal("500.00"));
            partClaimed(w2.source(), "500.00", claimed2.providerRefundId());
            String goodwill2 = mockProvider.refundedExternally(w2.providerPaymentId(), new BigDecimal("200.00")).providerRefundId();
            assertThat(verifyResult(a, w2.refundId())).isEqualTo("FOREIGN_REFUND");
            excludeByTwo(a, b, w2.refundId(), goodwill2);
            assertThat(verifyResult(a, w2.refundId())).isEqualTo("NONE_OF_OURS");
            putBackByTwo(a, b, w2.refundId());
            assertThat(balance(w2.buyer())).isEqualByComparingTo("400.00");
        }

        private void partClaimed(Submitted source, String amount, String providerRefundId) {
            jdbc.update("insert into refund (payment_id, supplier_order_id, amount, reason, status, idempotency_key, attempts, "
                            + "review_cause, review_ref, completed_at) values (?, ?, ?, 'WALLET_WITHDRAWAL', 'COMPLETED', ?, 1, "
                            + "'COMPLETED_BY_OTHER_REFUND', ?, utc_timestamp(6))",
                    source.paymentId(), source.orderId(), new BigDecimal(amount), "claim-" + UUID.randomUUID(), providerRefundId);
        }

        @Test
        @DisplayName("F2: payment 1000, part 400 refused, support refunded 500 by hand (400 + 100 goodwill): exclusion refused (500 >= 400), plain mark-completed refused (500 != 400), confirmPayerRefundedInFull refused (not refunded in full); two people with evidence and confirmRefundCoversThisPart close the part COMPLETED: provider_refund_id empty, claim recorded, source blocked REFUNDED_ELSEWHERE, wallet unchanged, retry never needed")
        void aHandRefundThatCoversThePartWithoutRefundingInFullClosesItByTwoPeople() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String markCompleted = refund + "/mark-completed";
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");

            // Every other exit is closed, as before.
            postRefused(a, refund + "/foreign-refund-not-this-part", notThisPart("goodwill", "ticket 1", h), 422, "REFUND_VERIFICATION_FAILED");
            postRefused(a, refund + "/recredit", recreditBody("x", true, UNRELATED), 422, "REFUND_VERIFICATION_FAILED");
            var plain = postRefused(a, markCompleted, Map.of("providerRefundId", h, "note", "x"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(plain.get("message").asText()).contains("confirmRefundCoversThisPart");
            var inFull = new java.util.HashMap<String, Object>(Map.of("providerRefundId", h, "note", "x"));
            inFull.put("confirmPayerRefundedInFull", true);
            postRefused(a, markCompleted, inFull, 422, "REFUND_VERIFICATION_FAILED");
            // The refusals tell a person that a retry sends the part again.
            var foreignMessage = postRefused(a, refund + "/recredit", recreditBody("x", true, UNRELATED), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(foreignMessage.get("message").asText()).contains("confirmRefundCoversThisPart").contains("a retry sends the part again");

            // Evidence is needed at every call.
            postRefused(a, markCompleted, coversBody(h, null), 400, "VALIDATION_ERROR");
            postRefused(a, markCompleted, coversBody(h, "too short"), 400, "VALIDATION_ERROR");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();

            // One person is never enough.
            assertThat(postOk(a, markCompleted, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, markCompleted, coversBody(h, COVERS_EVIDENCE), 409, "SECOND_APPROVER_REQUIRED");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action").toString()).startsWith("CV");
            // The second person's evidence is needed too.
            postRefused(b, markCompleted, coversBody(h, "short"), 400, "VALIDATION_ERROR");
            var done = postOk(b, markCompleted, coversBody(h, COVERS_EVIDENCE));

            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(done.get("done").asBoolean()).isTrue();
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("provider_refund_id")).describedAs("no one-to-one claim: the unique index is not touched").isNull();
            assertThat(row.get("review_cause")).isEqualTo("COMPLETED_BY_OTHER_REFUND");
            assertThat(row.get("review_ref")).isEqualTo(h);
            assertThat(row.get("ops_action")).isNull();
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("REFUNDED_ELSEWHERE");
            assertThat(balance(w.buyer())).describedAs("the payer has the money; the wallet is not credited").isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED")).hasSize(1);
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED").get(0)).contains("Evidence").contains("to cover this part");
            assertThat(count("select count(*) from audit_log where entity_type = 'REFUND' and entity_id = ? and action = 'REFUND_OPS_APPROVAL_REQUESTED'",
                    w.refundId())).isEqualTo(1);
            assertLedger(w.buyer());
            // Once: a completed part is not closed again, and nothing is sent to the provider for it.
            postRefused(b, markCompleted, coversBody(h, COVERS_EVIDENCE), 409, "INVALID_STATE_TRANSITION");
            assertThat(mockProvider.listRefunds(w.providerPaymentId())).hasSize(1);
        }

        @Test
        @DisplayName("F2: the new close needs a fresh verification that showed a refund that is not ours (none, an old one or another answer is refused), a withdrawal part, one confirmation only, and a refund that is processed, listed and not ours")
        void theCoversExitNeedsItsGates() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";

            // No verification yet (the part was sent to review by the system, never read by a person).
            postRefused(a, path, coversBody(h, COVERS_EVIDENCE), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            // An old one.
            jdbc.update("update refund set verified_at = date_sub(utc_timestamp(6), interval 11 minute) where id = ?", w.refundId());
            postRefused(a, path, coversBody(h, COVERS_EVIDENCE), 409, "REFUND_VERIFICATION_REQUIRED");
            // Another answer.
            jdbc.update("update refund set verified_at = utc_timestamp(6), verified_result = 'NONE_OF_OURS' where id = ?", w.refundId());
            postRefused(a, path, coversBody(h, COVERS_EVIDENCE), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");

            // One confirmation only.
            var both = coversBody(h, COVERS_EVIDENCE);
            both.put("confirmPayerRefundedInFull", true);
            postRefused(a, path, both, 400, "VALIDATION_ERROR");
            // An id the provider does not list.
            postRefused(a, path, coversBody("rfnd_ghost", COVERS_EVIDENCE), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");

            // A verification between the two people voids the first approval.
            assertThat(postOk(a, path, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            assertThat(postOk(b, path, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean())
                    .describedAs("the second person is the first of a new request").isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("F2: the new close is for a withdrawal part only; a refund of ours cannot close a part this way")
        void theCoversExitIsForWithdrawalPartsAndForeignRefundsOnly() throws Exception {
            var submitted = submit("400.19", 1);
            long cancellation = cancellationInReview(submitted);
            var a = ops("OPS_FINANCE");
            postRefused(a, ADMIN + "/refunds/" + cancellation + "/mark-completed", coversBody("rfnd_x", COVERS_EVIDENCE), 400, "VALIDATION_ERROR");

            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            var mine = mockProvider.refund(w.providerPaymentId(), new BigDecimal("100.00"), "other-part-key-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-999998", Map.of("mandi_refund_id", "999998"), "normal"));
            assertThat(mine).isNotNull();
            var oursListed = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .filter(e -> "mandi-refund-999998".equals(e.receipt())).findFirst().orElseThrow();
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed",
                    coversBody(oursListed.providerRefundId(), COVERS_EVIDENCE), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("one of ours");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("F2 (claims cap): payment 1000, one hand refund of 500, parts of 400, 400 and 100: the first 400 closes against it, the second 400 is refused (100 left), the 100 closes; the parts closed against it never add up to more than 500, the wallet is not credited, the ledger adds up")
        void partsClosedAgainstOneCoveringRefundNeverExceedIt() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "900.00");
            long p1 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p3 = withdraw(buyer, "100.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            String h = mockProvider.refundedExternally(pid(source), new BigDecimal("500.00")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            assertThat(List.of(rstatus(p1), rstatus(p2), rstatus(p3))).containsOnly("NEEDS_REVIEW");
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            java.util.function.LongConsumer closeByTwo = id -> {
                try {
                    String path = ADMIN + "/refunds/" + id + "/mark-completed";
                    assertThat(verifyResult(a, id)).isEqualTo("FOREIGN_REFUND");
                    assertThat(postOk(a, path, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
                    assertThat(postOk(b, path, coversBody(h, COVERS_EVIDENCE)).get("status").asText()).isEqualTo("COMPLETED");
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            };

            closeByTwo.accept(p1);
            assertThat(verifyResult(a, p2)).isEqualTo("FOREIGN_REFUND");
            var refused = postRefused(a, ADMIN + "/refunds/" + p2 + "/mark-completed", coversBody(h, COVERS_EVIDENCE), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("already closed against it").contains("500.00").contains("400.00");
            assertThat(rstatus(p2)).isEqualTo("NEEDS_REVIEW");
            closeByTwo.accept(p3);
            assertThat(rstatus(p3)).isEqualTo("COMPLETED");
            assertThat(count("select count(*) from refund where review_cause = 'COMPLETED_BY_OTHER_REFUND' and review_ref = ? and status = 'COMPLETED'", h))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("select sum(amount) from refund where review_cause = 'COMPLETED_BY_OTHER_REFUND' and review_ref = ?",
                    BigDecimal.class, h)).isEqualByComparingTo("500.00");
            assertThat(rstatus(p2)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("B1: a refund still PENDING at the provider: mark-completed says it exists but is not processed yet, with or without the covering confirmation; an exclusion naming an id the list does not show says so and that a refund made a moment ago may not be listed yet")
        void aPendingRefundIsNamedAsNotProcessedYet() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            String markCompleted = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
            doReturn(listedWithStatus(w.providerPaymentId(), h, PaymentProvider.ProviderRefundStatus.PENDING))
                    .when(mockProvider).listRefunds(w.providerPaymentId());
            try {
                var covers = postRefused(a, markCompleted, coversBody(h, COVERS_EVIDENCE), 422, "REFUND_VERIFICATION_FAILED");
                assertThat(covers.get("message").asText()).contains("exists at the provider but is not processed yet").doesNotContain("lists no refund");
                var plain = postRefused(a, markCompleted, Map.of("providerRefundId", h, "note", "x"), 422, "REFUND_VERIFICATION_FAILED");
                assertThat(plain.get("message").asText()).contains("exists at the provider but is not processed yet").doesNotContain("lists no refund");
            } finally {
                reset(mockProvider);
            }
            var unlisted = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/foreign-refund-not-this-part",
                    notThisPart("goodwill", "ticket", "rfnd_not_listed_yet"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(unlisted.get("message").asText()).contains("The provider lists no refund rfnd_not_listed_yet")
                    .contains("may not be listed until the provider has processed it");
            var plainUnlisted = postRefused(a, markCompleted, Map.of("providerRefundId", "rfnd_not_listed_yet", "note", "x"), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(plainUnlisted.get("message").asText()).contains("The provider lists no refund rfnd_not_listed_yet");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
        }

        // ── the seventh check (rv17g): the close against a refund made by hand, its minimum age, its cap and its gates ──

        private int postStatus(Ops who, String path, Object body) throws Exception {
            return mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + who.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus();
        }

        /** Waits until the thread is inside the wallet lock call (held by another transaction, so it queues on it). */
        private void awaitQueuedOnTheWallet(Thread thread) throws Exception {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (var frame : thread.getStackTrace()) {
                    if (frame.getClassName().contains("WalletService") && frame.getMethodName().equals("lock")) {
                        Thread.sleep(500);
                        return;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("the call never reached the wallet lock");
        }

        /**
         * Two calls started in this order while the outlet's wallet is held by a third transaction: each has passed every check made
         * before the lock and waits for it, and the wallet is released only when both wait. The statuses come back in the order started.
         */
        private int[] raceUnderTheWalletLock(long outletId, java.util.concurrent.Callable<Integer> first,
                                             java.util.concurrent.Callable<Integer> second) throws Exception {
            var pool = Executors.newSingleThreadExecutor();
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var one = new java.util.concurrent.FutureTask<>(first);
            var two = new java.util.concurrent.FutureTask<>(second);
            var t1 = new Thread(one, "race-first");
            var t2 = new Thread(two, "race-second");
            try {
                var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                    walletService.lock(outletId);
                    held.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return "released";
                }));
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                t1.start();
                awaitQueuedOnTheWallet(t1);
                t2.start();
                awaitQueuedOnTheWallet(t2);
                release.countDown();
                holder.get(30, TimeUnit.SECONDS);
                return new int[]{one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS)};
            } finally {
                release.countDown();
                pool.shutdownNow();
                t1.interrupt();
                t2.interrupt();
            }
        }

        @Test
        @DisplayName("X1: payment 1000, parts 300 and 400 refused, a hand refund of 400: two people close the 300 against it, then ONE person's plain mark-completed of the 400 (400 = 400) is refused (300 are already closed against it): the part stays in review, owns no provider refund id, and 700 are never closed against a refund of 400")
        void aPlainMarkCompletedNeverAdoptsARefundThatOtherPartsAreClosedAgainst() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "700.00");
            long p1 = withdraw(buyer, "300.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            String h = mockProvider.refundedExternally(pid(source), new BigDecimal("400.00")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            assertThat(List.of(rstatus(p1), rstatus(p2))).containsOnly("NEEDS_REVIEW");
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path1 = ADMIN + "/refunds/" + p1 + "/mark-completed";
            assertThat(verifyResult(a, p1)).isEqualTo("FOREIGN_REFUND");
            assertThat(postOk(a, path1, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(postOk(b, path1, coversBody(h, COVERS_EVIDENCE)).get("status").asText()).isEqualTo("COMPLETED");

            var refused = postRefused(a, ADMIN + "/refunds/" + p2 + "/mark-completed", Map.of("providerRefundId", h, "note", "the amounts are equal"),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("already closed against that provider refund").contains("300.00");
            assertThat(rstatus(p2)).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(p2).get("provider_refund_id")).isNull();
            assertThat(jdbc.queryForObject("select coalesce(sum(amount),0) from refund where payment_id = ? and status = 'COMPLETED' "
                    + "and (review_ref = ? or provider_refund_id = ?)", BigDecimal.class, source.paymentId(), h, h)).isEqualByComparingTo("300.00");
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertLedger(buyer);
        }

        /** Two calls racing for one refund: one part is closed against it by two people (second approval), the other takes it with a plain mark-completed. */
        private void racePlainMarkCompletedAgainstACoversClose(boolean coversFirst) throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "700.00");
            long p1 = withdraw(buyer, "300.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            String h = mockProvider.refundedExternally(pid(source), new BigDecimal("400.00")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path1 = ADMIN + "/refunds/" + p1 + "/mark-completed";
            String path2 = ADMIN + "/refunds/" + p2 + "/mark-completed";
            assertThat(verifyResult(a, p1)).isEqualTo("FOREIGN_REFUND");
            assertThat(postOk(a, path1, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
            java.util.concurrent.Callable<Integer> covers = () -> postStatus(b, path1, coversBody(h, COVERS_EVIDENCE));
            java.util.concurrent.Callable<Integer> plain = () -> postStatus(a, path2, Map.of("providerRefundId", h, "note", "the amounts are equal"));
            int[] statuses = coversFirst
                    ? raceUnderTheWalletLock(buyer.outletId(), covers, plain)
                    : raceUnderTheWalletLock(buyer.outletId(), plain, covers);
            assertThat(statuses).describedAs("one wins, the other is refused under the lock").containsExactlyInAnyOrder(200, 422);
            assertThat(List.of(rstatus(p1), rstatus(p2))).describedAs("exactly one part is closed against the refund").containsExactlyInAnyOrder("COMPLETED", "NEEDS_REVIEW");
            long closed = rstatus(p1).equals("COMPLETED") ? p1 : p2;
            BigDecimal paid = jdbc.queryForObject("select coalesce(sum(amount),0) from refund where payment_id = ? and status = 'COMPLETED' "
                    + "and (review_ref = ? or provider_refund_id = ?)", BigDecimal.class, source.paymentId(), h, h);
            assertThat(paid).describedAs("what is closed against a refund of 400").isLessThanOrEqualTo(new BigDecimal("400.00"));
            assertThat(refundRow(closed).get("completed_at")).isNotNull();
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("X1 under the lock (the covering close commits first): the plain mark-completed that passed its checks before it checks again under the locks and is refused")
        void aPlainMarkCompletedThatWaitedForAClaimIsRefusedUnderTheLock() throws Exception {
            racePlainMarkCompletedAgainstACoversClose(true);
        }

        @Test
        @DisplayName("X1 under the lock (the plain mark-completed commits first): the covering close that passed its checks before sees the refund is now a refund of ours and is refused under the lock")
        void aCoversCloseThatWaitedForAPlainMarkCompletedIsRefusedUnderTheLock() throws Exception {
            racePlainMarkCompletedAgainstACoversClose(false);
        }

        @Test
        @DisplayName("M4b: parts of 400 and 400, one hand refund of 500, both second approvals at the same moment: both passed the room check before the lock, the second to get the lock is refused by the check made under it, and no more than 500 is closed against the refund")
        void twoPartsClosedAtTheSameMomentAgainstOneRefundCannotExceedIt() throws Exception {
            var buyer = newBuyer();
            var source = captured(buyer, "1000", 1);
            creditWallet(source, "800.00");
            long p1 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            long p2 = withdraw(buyer, "400.00", UUID.randomUUID().toString()).at("/data/parts/0/refundId").asLong();
            String h = mockProvider.refundedExternally(pid(source), new BigDecimal("500.00")).providerRefundId();
            refuseRefundsWith(ProviderFailureKind.REJECTED_OTHER, "400", "Refund not supported for this payment");
            paymentJobs.processRefunds();
            reset(mockProvider);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            for (long id : new long[]{p1, p2}) {
                assertThat(verifyResult(a, id)).isEqualTo("FOREIGN_REFUND");
                assertThat(postOk(a, ADMIN + "/refunds/" + id + "/mark-completed", coversBody(h, COVERS_EVIDENCE))
                        .get("awaitingSecondApprover").asBoolean()).isTrue();
            }
            int[] statuses = raceUnderTheWalletLock(buyer.outletId(),
                    () -> postStatus(b, ADMIN + "/refunds/" + p1 + "/mark-completed", coversBody(h, COVERS_EVIDENCE)),
                    () -> postStatus(b, ADMIN + "/refunds/" + p2 + "/mark-completed", coversBody(h, COVERS_EVIDENCE)));
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);
            assertThat(List.of(rstatus(p1), rstatus(p2))).containsExactlyInAnyOrder("COMPLETED", "NEEDS_REVIEW");
            assertThat(jdbc.queryForObject("select coalesce(sum(amount),0) from refund where payment_id = ? and status = 'COMPLETED' "
                    + "and review_cause = 'COMPLETED_BY_OTHER_REFUND' and review_ref = ?", BigDecimal.class, source.paymentId(), h))
                    .isEqualByComparingTo("400.00");
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertLedger(buyer);
        }

        @Test
        @DisplayName("X2: a part whose send was never answered (AMBIGUOUS) is not closed against a hand refund that covers it within 30 minutes of its last send: refused at the first approval (nothing recorded) and at the second, and closed by two people once it is older; the lost send never lands on top of it")
        void aCoversCloseIsRefusedWithinTheMinimumAgeOfAnAmbiguousSend() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var w = inReview("1000", 1, "400.00", "AMBIGUOUS");
                String h = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("500.00")).providerRefundId();
                var a = ops("OPS_FINANCE");
                var b = ops("OPS_FINANCE");
                String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
                assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
                jdbc.update("update refund set last_sent_at = utc_timestamp(6), sent_at = utc_timestamp(6) where id = ?", w.refundId());
    
                var young = postRefused(a, path, coversBody(h, COVERS_EVIDENCE), 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(young.get("message").asText()).contains("less than 30 minutes ago");
                assertThat(refundRow(w.refundId()).get("ops_action")).describedAs("nothing recorded").isNull();
    
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                assertThat(postOk(a, path, coversBody(h, COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();
                // The part is sent again between the two people (a later send of ours): the second call is refused as well.
                jdbc.update("update refund set last_sent_at = utc_timestamp(6) where id = ?", w.refundId());
                postRefused(b, path, coversBody(h, COVERS_EVIDENCE), 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
    
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                assertThat(postOk(b, path, coversBody(h, COVERS_EVIDENCE)).get("status").asText()).isEqualTo("COMPLETED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertLedger(w.buyer());
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("X2: the same minimum age for the close against a payment refunded in full (confirmPayerRefundedInFull): refused within 30 minutes of the last send of an AMBIGUOUS part, accepted after")
        void aPayerRefundedInFullCloseIsRefusedWithinTheMinimumAgeOfAnAmbiguousSend() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var foreign = new PaymentProvider.ProviderRefundEntry[1];
                var w = heldForForeign("1000", 1, "400.00", "1000.00", foreign);
                String id = foreign[0].providerRefundId();
                jdbc.update("update refund set failure_kind = 'AMBIGUOUS', last_sent_at = utc_timestamp(6), sent_at = utc_timestamp(6) where id = ?", w.refundId());
                var a = ops("OPS_FINANCE");
                var b = ops("OPS_FINANCE");
                String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
                var body = new java.util.HashMap<String, Object>();
                body.put("providerRefundId", id);
                body.put("note", "the payer got all of it back in the dashboard, checked");
                body.put("confirmPayerRefundedInFull", true);
    
                var young = postRefused(a, path, body, 409, "REFUND_VERIFICATION_REQUIRED");
                assertThat(young.get("message").asText()).contains("less than 30 minutes ago");
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
                assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
    
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 3 hour) where id = ?", w.refundId());
                var first = postOk(a, path, body);
                if (first.has("awaitingSecondApprover") && first.get("awaitingSecondApprover").asBoolean()) {
                    first = postOk(b, path, body);
                }
                assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("H1 (rv17h): the plain mark-completed (a provider refund of exactly the part's amount) keeps the minimum age too: an AMBIGUOUS part last sent 1 minute ago is refused (our own lost send may still land on top of a refund made by hand) and stays in review; the same part aged beyond 30 minutes is completed")
        void aPlainMarkCompletedIsRefusedWithinTheMinimumAgeOfAnAmbiguousSend() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var w = inReview("1000", 1, "400.00", "AMBIGUOUS");
                String h = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00")).providerRefundId();
                var a = ops("OPS_FINANCE");
                String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
                var body = Map.<String, Object>of("providerRefundId", h, "note", "support refunded exactly this part by hand, checked");
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 1 minute), "
                        + "sent_at = date_sub(utc_timestamp(6), interval 1 minute) where id = ?", w.refundId());

                var young = postRefused(a, path, body, 409, "REFUND_VERIFICATION_REQUIRED");

                assertThat(young.get("message").asText()).contains("less than 30 minutes ago");
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
                assertThat(refundRow(w.refundId()).get("provider_refund_id")).describedAs("the refund is not adopted").isNull();
                assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED")).isEmpty();
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");

                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 31 minute) where id = ?", w.refundId());
                assertThat(postOk(a, path, body).get("status").asText()).isEqualTo("COMPLETED");
                assertThat(rstatus(w.refundId())).isEqualTo("COMPLETED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertLedger(w.buyer());
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("H1 (rv17h): the minimum age of the plain mark-completed is for a part whose send was AMBIGUOUS only: a part refused for another reason (PROVIDER_FAILED) last sent 1 minute ago is completed against the same refund")
        void aPlainMarkCompletedOfANonAmbiguousPartIsNotHeldByTheMinimumAge() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var w = inReview("1000", 1, "400.00", "PROVIDER_FAILED");
                String h = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00")).providerRefundId();
                var a = ops("OPS_FINANCE");
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 1 minute), "
                        + "sent_at = date_sub(utc_timestamp(6), interval 1 minute) where id = ?", w.refundId());

                var done = postOk(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed",
                        Map.of("providerRefundId", h, "note", "support refunded exactly this part by hand, checked"));

                assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
                assertLedger(w.buyer());
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("H1 before the locks (rv17h): a plain mark-completed of an AMBIGUOUS part sent 1 minute ago is refused before it asks for any lock: with the outlet's wallet held by another transaction it still answers at once (it does not queue behind the lock only to be refused under it)")
        void aYoungPlainMarkCompletedIsRefusedBeforeItQueuesForTheLocks() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var w = inReview("1000", 1, "400.00", "AMBIGUOUS");
                String h = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00")).providerRefundId();
                var a = ops("OPS_FINANCE");
                String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
                var body = Map.<String, Object>of("providerRefundId", h, "note", "support refunded exactly this part by hand, checked");
                jdbc.update("update refund set last_sent_at = date_sub(utc_timestamp(6), interval 1 minute) where id = ?", w.refundId());
                var pool = Executors.newFixedThreadPool(2);
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                try {
                    var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                        walletService.lock(w.buyer().outletId());
                        held.countDown();
                        try {
                            release.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return "released";
                    }));
                    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                    var call = pool.submit(() -> postStatus(a, path, body));

                    assertThat(call.get(10, TimeUnit.SECONDS)).describedAs("answered while the wallet is held").isEqualTo(409);
                    release.countDown();
                    holder.get(30, TimeUnit.SECONDS);
                } finally {
                    release.countDown();
                    pool.shutdownNow();
                }
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("H1 under the lock (rv17h): a plain mark-completed that passed every check (the part was old enough) while the part was sent again before it got the locks is refused under them: the part stays in review and is not adopted")
        void aPlainMarkCompletedOfAPartSentAgainWhileItWaitedForTheLocksIsRefused() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ofMinutes(30));
            try {
                var w = inReview("1000", 1, "400.00", "AMBIGUOUS");
                String h = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal("400.00")).providerRefundId();
                var a = ops("OPS_FINANCE");
                String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
                var body = Map.<String, Object>of("providerRefundId", h, "note", "support refunded exactly this part by hand, checked");
                var pool = Executors.newSingleThreadExecutor();
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var call = new java.util.concurrent.FutureTask<Integer>(() -> postStatus(a, path, body));
                var thread = new Thread(call, "race-plain");
                try {
                    var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
                        walletService.lock(w.buyer().outletId());
                        held.countDown();
                        try {
                            release.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return "released";
                    }));
                    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                    thread.start();
                    // It has passed the checks made before the locks (the part is 3 hours old) and waits for the wallet.
                    awaitQueuedOnTheWallet(thread);
                    jdbc.update("update refund set last_sent_at = utc_timestamp(6) where id = ?", w.refundId());
                    release.countDown();
                    holder.get(30, TimeUnit.SECONDS);
                    assertThat(call.get(60, TimeUnit.SECONDS)).isEqualTo(409);
                } finally {
                    release.countDown();
                    pool.shutdownNow();
                    thread.interrupt();
                }
                assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
                assertThat(refundRow(w.refundId()).get("provider_refund_id")).isNull();
                assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(refundOperations, "recreditMinAge", java.time.Duration.ZERO);
            }
        }

        @Test
        @DisplayName("G1: the second person's approval of a covering close is bound to the refund: naming another refund than the first person did is not the second approval but the first of a new request, and the part stays in review; the same refund then completes it")
        void theSecondApprovalOfACoversCloseMustNameTheSameRefund() throws Exception {
            var ids = new java.util.ArrayList<String>();
            var w = heldForForeigns("1000", "400.00", List.of("450.00", "500.00"), ids);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String path = ADMIN + "/refunds/" + w.refundId() + "/mark-completed";
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            assertThat(postOk(a, path, coversBody(ids.get(0), COVERS_EVIDENCE)).get("awaitingSecondApprover").asBoolean()).isTrue();

            var other = postOk(b, path, coversBody(ids.get(1), COVERS_EVIDENCE));
            assertThat(other.get("awaitingSecondApprover").asBoolean()).describedAs("the first of a new request, not the second of the old").isTrue();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            var row = refundRow(w.refundId());
            assertThat(row.get("ops_action")).isEqualTo(boundActionOf("CV", ids.get(1)));
            assertThat(((Number) row.get("ops_action_by")).longValue()).isEqualTo(b.userId());
            assertThat(row.get("review_ref")).isNotEqualTo(ids.get(1));

            assertThat(postOk(a, path, coversBody(ids.get(1), COVERS_EVIDENCE)).get("status").asText()).isEqualTo("COMPLETED");
            assertThat(refundRow(w.refundId()).get("review_ref")).isEqualTo(ids.get(1));
        }

        @Test
        @DisplayName("G2: a refund that is ours only by the provider refund id another refund of ours carries (no receipt, no notes: a legacy refund) cannot close a part as a foreign refund, even with a fresh verification that showed it as foreign before")
        void aRefundOursByProviderRefundIdCannotCloseAPartAsForeign() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            var row = refundRow(w.refundId());
            // Another refund of ours was recorded against this very refund (a legacy refund, listed with no receipt and no notes).
            jdbc.update("insert into refund (payment_id, supplier_order_id, amount, reason, status, idempotency_key, attempts, "
                            + "provider_refund_id, completed_at) values (?, ?, ?, 'WALLET_WITHDRAWAL', 'COMPLETED', ?, 1, ?, utc_timestamp(6))",
                    row.get("payment_id"), row.get("supplier_order_id"), new BigDecimal("500.00"), "g2-" + UUID.randomUUID(), h);
            var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed", coversBody(h, COVERS_EVIDENCE),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("one of ours");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
        }

        @Test
        @DisplayName("G3: a provider refund that FAILED cannot close a part: it moved no money; refused with its status, nothing recorded")
        void aFailedProviderRefundCannotCloseAPartAsCovering() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            doReturn(listedWithStatus(w.providerPaymentId(), h, PaymentProvider.ProviderRefundStatus.FAILED)).when(mockProvider).listRefunds(w.providerPaymentId());
            try {
                var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed", coversBody(h, COVERS_EVIDENCE),
                        422, "REFUND_VERIFICATION_FAILED");
                assertThat(refused.get("message").asText()).contains("not processed at the provider").contains("FAILED");
            } finally {
                reset(mockProvider);
            }
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
        }

        @Test
        @DisplayName("G4: the part's own refund turns up at the provider after the verification (the lost send landed): a covering close is refused, the part is adopted by a verify instead")
        void aCoversCloseIsRefusedWhenThePartsOwnRefundIsListed() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            mockProvider.refund(w.providerPaymentId(), new BigDecimal("400.00"), "late-" + w.refundId(),
                    new PaymentProvider.RefundOptions("mandi-refund-" + w.refundId(), Map.of("mandi_refund_id", String.valueOf(w.refundId())), "normal"));
            var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed", coversBody(h, COVERS_EVIDENCE),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(refused.get("message").asText()).contains("holds a refund of this part's own");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
        }

        @Test
        @DisplayName("G5: a payment the provider no longer shows as captured cannot have a part closed against a refund of it")
        void aCoversCloseIsRefusedWhenThePaymentIsNotCaptured() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(new PaymentProvider.ProviderPaymentFacts(real.providerPaymentId(), real.providerOrderId(), real.status(), false,
                    real.amount(), real.amountRefunded(), real.method(), real.methodDetail(), real.fee(), real.createdAt()))
                    .when(mockProvider).inspect(w.providerPaymentId());
            try {
                var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed", coversBody(h, COVERS_EVIDENCE),
                        422, "REFUND_VERIFICATION_FAILED");
                assertThat(refused.get("message").asText()).contains("does not show this payment as captured");
            } finally {
                reset(mockProvider);
            }
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
        }

        @Test
        @DisplayName("G8: the covering confirmation cannot be combined with confirmProcessedOnOtherAccount either: 400, nothing recorded")
        void aCoversCloseCannotBeCombinedWithTheOtherAccountConfirmation() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "500.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            assertThat(verifyResult(a, w.refundId())).isEqualTo("FOREIGN_REFUND");
            var both = coversBody(h, COVERS_EVIDENCE);
            both.put("confirmProcessedOnOtherAccount", true);
            var refused = postRefused(a, ADMIN + "/refunds/" + w.refundId() + "/mark-completed", both, 400, "VALIDATION_ERROR");
            assertThat(refused.get("message").asText()).contains("confirmProcessedOnOtherAccount");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(refundRow(w.refundId()).get("ops_action")).isNull();
        }

        @Test
        @DisplayName("M14: a refund recorded as not this part's that is listed with a receipt of ours (a refund of ours whose provider refund id is not recorded yet) is not counted twice: as ours and as excluded would hide a second, unlisted refund of the same amount")
        void aRecordedRefundThatTurnsOutOursByReceiptIsNotCountedTwice() throws Exception {
            var foreign = new PaymentProvider.ProviderRefundEntry[1];
            var w = heldForForeign("1000", 1, "400.00", "100.00", foreign);
            String h = foreign[0].providerRefundId();
            var a = ops("OPS_FINANCE");
            forceExclusionAndCleanVerification(w.refundId(), h);
            var real = mockProvider.inspect(w.providerPaymentId());
            var listed = mockProvider.listRefunds(w.providerPaymentId()).stream()
                    .map(e -> e.providerRefundId().equals(h)
                            ? new PaymentProvider.ProviderRefundEntry(e.providerRefundId(), e.amount(), e.status(), "mandi-refund-999997", e.mandiRefundId(), e.createdAt())
                            : e).toList();
            // The provider says 200 have gone back; the list shows only the 100 (ours by its receipt): 100 more, unlisted.
            doReturn(listed).when(mockProvider).listRefunds(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("200.00"))).when(mockProvider).inspect(w.providerPaymentId());
            try {
                assertThat(verifyResult(a, w.refundId())).describedAs("100 of the 200 are explained by ours, the other 100 by nothing").isEqualTo("FOREIGN_REFUND");
            } finally {
                reset(mockProvider);
            }
        }

        /** A withdrawal part of ours already closed against a refund made by hand, as mark-completed with confirmPayerRefundedInFull leaves it. */
        private void claimAgainst(Submitted source, String amount, String providerRefundId) {
            jdbc.update("insert into refund (payment_id, supplier_order_id, amount, reason, status, idempotency_key, attempts, "
                            + "review_cause, review_ref, completed_at) values (?, ?, ?, 'WALLET_WITHDRAWAL', 'COMPLETED', ?, 1, "
                            + "'COMPLETED_BY_OTHER_REFUND', ?, utc_timestamp(6))",
                    source.paymentId(), source.orderId(), new BigDecimal(amount), "claim-" + UUID.randomUUID(), providerRefundId);
        }

        /** A part of {@code part} from a payment of 1000, in review, with a refund of {@code foreignAmount} by hand that a part of ours is closed against. */
        private Withdrawn heldWithAClaimedRefund(String part, String foreignAmount, String[] foreignIdOut) throws Exception {
            var w = inReview("1000", 1, part, "REJECTED_OTHER");
            var foreign = mockProvider.refundedExternally(w.providerPaymentId(), new BigDecimal(foreignAmount));
            claimAgainst(w.source(), foreignAmount, foreign.providerRefundId());
            foreignIdOut[0] = foreign.providerRefundId();
            return w;
        }

        @Test
        @DisplayName("F4/R1/R2: the room rule counts what a refund closed against a part of ours leaves (payment 1000, part 400): refunded 599.99 or 600.00 by hand leaves room, 600.01 does not; and the total is the LARGER of the provider's own figure and its list, so a figure that lags behind the list (here 500 for 600.01 listed) does not open the room")
        void theRoomRuleUsesTheLargerOfTheFigureAndTheList() throws Exception {
            var a = ops("OPS_FINANCE");
            for (String amount : new String[]{"599.99", "600.00"}) {
                var id = new String[1];
                var w = heldWithAClaimedRefund("400.00", amount, id);
                assertThat(postOk(a, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "read")).get("result").asText())
                        .describedAs(amount).isEqualTo("NONE_OF_OURS");
            }
            var id = new String[1];
            var w = heldWithAClaimedRefund("400.00", "600.01", id);
            String verify = ADMIN + "/refunds/" + w.refundId() + "/verify";
            assertThat(postOk(a, verify, Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            assertThat(refundRow(w.refundId()).get("verified_result")).isEqualTo("FOREIGN_REFUND");

            // The provider's own figure lags behind its list (500 against 600.01 listed): the list's total counts (R1).
            var real = mockProvider.inspect(w.providerPaymentId());
            doReturn(factsWithRefunded(real, new BigDecimal("500.00"))).when(mockProvider).inspect(w.providerPaymentId());
            assertThat(postOk(a, verify, Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            // The figure ahead of the list (700 against 600.01 listed): more went back than is explained, foreign too (R2).
            doReturn(factsWithRefunded(real, new BigDecimal("700.00"))).when(mockProvider).inspect(w.providerPaymentId());
            assertThat(postOk(a, verify, Map.of("note", "read")).get("result").asText()).isEqualTo("FOREIGN_REFUND");
            reset(mockProvider);
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
        }

        @Test
        @DisplayName("F4/R3: a FAILED refund on the provider's list moved no money and does not count in the room total (payment 1000, part 400, 100 refunded by hand and claimed, a failed refund of 950 listed): none of ours")
        void aFailedRefundOnTheListDoesNotCountInTheRoom() throws Exception {
            var id = new String[1];
            var w = heldWithAClaimedRefund("400.00", "100.00", id);
            var failed = new PaymentProvider.ProviderRefundEntry("rfnd_failed_room", new BigDecimal("950.00"),
                    PaymentProvider.ProviderRefundStatus.FAILED, null, null, Instant.now());
            var listed = new java.util.ArrayList<>(mockProvider.listRefunds(w.providerPaymentId()));
            listed.add(failed);
            doReturn(listed).when(mockProvider).listRefunds(w.providerPaymentId());
            var a = ops("OPS_FINANCE");

            assertThat(postOk(a, ADMIN + "/refunds/" + w.refundId() + "/verify", Map.of("note", "read")).get("result").asText())
                    .isEqualTo("NONE_OF_OURS");
            reset(mockProvider);
        }

        @Test
        @DisplayName("F4/R4: a cancellation refund sent to the wallet is refused 422 when the payment is refunded for at least its amount by a refund that is accounted for (a part of ours closed against it): the to-wallet read reaches the covered answer")
        void toWalletRefusesWhenTheRoomIsGone() throws Exception {
            var submitted = submit("400.19", 1);
            long refundId = cancellationInReview(submitted);
            var foreign = mockProvider.refundedExternally(pid(submitted), new BigDecimal("300.00"));
            claimAgainst(submitted, "300.00", foreign.providerRefundId());
            var a = ops("OPS_FINANCE");
            jdbc.update("update refund set verified_result = 'NONE_OF_OURS', verified_at = utc_timestamp(6) where id = ?", refundId);

            var refused = postRefused(a, ADMIN + "/refunds/" + refundId + "/to-wallet",
                    Map.of("note", "provider declines", "confirmNoProviderRefund", true), 422, "REFUND_VERIFICATION_FAILED");

            assertThat(refused.get("message").asText()).contains("already been refunded to the payer for at least this amount");
            assertThat(rstatus(refundId)).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(submitted.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(refundId, "REFUND_OPS_TO_WALLET")).isEmpty();
        }

        // ── F3: a part that carries a provider refund id on a payment the provider does not know ──

        private Submitted lastLater;

        private Withdrawn carryingARefundOfAnotherAccount(String refundIdOnOtherAccount) throws Exception {
            var w = inReview("400", 1, "400.00", "PROVIDER_FAILED");
            jdbc.update("update refund set provider_refund_id = ? where id = ?", refundIdOnOtherAccount, w.refundId());
            lastLater = laterKnownPayment();
            unknownLikeRazorpay(w.providerPaymentId());
            return w;
        }

        private Map<String, Object> processedBody(String providerRefundId, String evidence, boolean confirm) {
            var body = new java.util.HashMap<String, Object>();
            body.put("providerRefundId", providerRefundId);
            body.put("note", "checked on the old account's dashboard");
            if (evidence != null) {
                body.put("evidence", evidence);
            }
            body.put("confirmProcessedOnOtherAccount", confirm);
            return body;
        }

        private static final String PROCESSED_EVIDENCE = "acc_OLD dashboard shows refund processed to the payer's UPI, ticket 91";

        @Test
        @DisplayName("F3 (E2): a part that carries a provider refund id on an unknown payment has an exit: two different people close it with mark-completed, evidence and confirmProcessedOnOtherAccount; it ends COMPLETED, the wallet is NOT credited, the source is blocked PAYMENT_UNKNOWN, every step is audited once")
        void aPartWithAnotherAccountsRefundIdIsClosedByTwoPeople() throws Exception {
            String other = "rfnd_other_" + UUID.randomUUID().toString().substring(0, 8);
            var w = carryingARefundOfAnotherAccount(other);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            var body = processedBody(other, PROCESSED_EVIDENCE, true);

            var first = postOk(a, refund + "/mark-completed", body);
            assertThat(first.get("awaitingSecondApprover").asBoolean()).isTrue();
            assertThat(first.get("done").asBoolean()).isFalse();
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            postRefused(a, refund + "/mark-completed", body, 409, "SECOND_APPROVER_REQUIRED");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            var done = postOk(b, refund + "/mark-completed", body);

            assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(done.get("done").asBoolean()).isTrue();
            var row = refundRow(w.refundId());
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("review_cause")).isEqualTo("COMPLETED_OTHER_ACCOUNT");
            assertThat(row.get("review_ref")).isEqualTo(other);
            assertThat(row.get("provider_refund_id")).isEqualTo(other);
            assertThat(row.get("completed_at")).isNotNull();
            assertThat(row.get("ops_action")).isNull();
            assertThat(balance(w.buyer())).describedAs("the payer has the money; the wallet is not credited").isEqualByComparingTo("0");
            assertThat(reversalsOf(w.refundId())).isZero();
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_reason")).isEqualTo("PAYMENT_UNKNOWN");
            assertLedger(w.buyer());
            var closing = reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED");
            assertThat(closing).hasSize(1);
            assertThat(closing.get(0)).contains("processed on another account").contains(other).contains("sha256").contains("ticket 91");
            var asked = reasonsOf(w.refundId(), "REFUND_OPS_APPROVAL_REQUESTED");
            assertThat(asked).hasSize(1);
            assertThat(asked.get(0)).contains("Always two people").contains("sha256");
            // Once; and the re-credit of it stays refused.
            postRefused(b, refund + "/mark-completed", body, 409, "INVALID_STATE_TRANSITION");
            postRefused(b, refund + "/recredit", otherAccountBody("x", ACCOUNT_EVIDENCE, true, true), 409, "INVALID_STATE_TRANSITION");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("F3 refusals: no evidence or short evidence 400; no confirmation reads the provider's list as before (422); another refund id 422; a part with no refund id of its own 422; no verification, a verification that is not UNKNOWN_PAYMENT, or one older than ten minutes 409; a cancellation refund 409; the same person twice 409; nothing is closed in any of them")
        void theOtherAccountExitRefusesWhatItShould() throws Exception {
            String other = "rfnd_other_" + UUID.randomUUID().toString().substring(0, 8);
            var w = carryingARefundOfAnotherAccount(other);
            var a = ops("OPS_FINANCE");
            var b = ops("OPS_FINANCE");
            String refund = ADMIN + "/refunds/" + w.refundId();
            String path = refund + "/mark-completed";
            // No verification at all.
            postRefused(a, path, processedBody(other, PROCESSED_EVIDENCE, true), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            // Evidence.
            postRefused(a, path, processedBody(other, null, true), 400, "VALIDATION_ERROR");
            postRefused(a, path, processedBody(other, "too short", true), 400, "VALIDATION_ERROR");
            // The confirmation is the switch: without it the ordinary mark-completed reads the list, which has nothing.
            postRefused(a, path, processedBody(other, PROCESSED_EVIDENCE, false), 422, "REFUND_VERIFICATION_FAILED");
            // Another refund id.
            postRefused(a, path, processedBody("rfnd_not_the_one", PROCESSED_EVIDENCE, true), 422, "REFUND_VERIFICATION_FAILED");
            assertThat(refundRow(w.refundId()).get("ops_action")).describedAs("no request recorded by any refusal").isNull();
            // A verification of another kind, and a stale one.
            jdbc.update("update refund set verified_result = 'NONE_OF_OURS' where id = ?", w.refundId());
            postRefused(a, path, processedBody(other, PROCESSED_EVIDENCE, true), 409, "REFUND_VERIFICATION_REQUIRED");
            jdbc.update("update refund set verified_result = 'UNKNOWN_PAYMENT', verified_at = date_sub(utc_timestamp(6), interval 11 minute) where id = ?", w.refundId());
            postRefused(a, path, processedBody(other, PROCESSED_EVIDENCE, true), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(postOk(a, refund + "/verify", Map.of("note", "read")).get("result").asText()).isEqualTo("UNKNOWN_PAYMENT");
            // The same person twice is not two people.
            assertThat(postOk(a, path, processedBody(other, PROCESSED_EVIDENCE, true)).get("awaitingSecondApprover").asBoolean()).isTrue();
            postRefused(a, path, processedBody(other, PROCESSED_EVIDENCE, true), 409, "SECOND_APPROVER_REQUIRED");
            // The provider is read again inside the call: the keys are no longer proven to work.
            unknownLikeRazorpay(pid(lastLater));
            var lost = postRefused(b, path, processedBody(other, PROCESSED_EVIDENCE, true), 503, "PROVIDER_UNAVAILABLE");
            assertThat(lost.get("message").asText()).contains("could not be proven");
            // The provider now knows the payment (keys fixed): its answer is no longer "unknown".
            reset(mockProvider);
            var changed = postRefused(b, path, processedBody(other, PROCESSED_EVIDENCE, true), 409, "REFUND_VERIFICATION_REQUIRED");
            assertThat(changed.get("message").asText()).contains("no longer answers as unknown");
            assertThat(rstatus(w.refundId())).isEqualTo("NEEDS_REVIEW");
            assertThat(balance(w.buyer())).isEqualByComparingTo("0");
            assertThat(reasonsOf(w.refundId(), "REFUND_OPS_MARK_COMPLETED")).isEmpty();
            assertThat(payRow(w.source().paymentId()).get("provider_refund_blocked_at")).isNull();

            // A part with no refund id of its own.
            var none = inReview("400", 1, "400.00", "PROVIDER_FAILED");
            laterKnownPayment();
            unknownLikeRazorpay(none.providerPaymentId());
            postOk(a, ADMIN + "/refunds/" + none.refundId() + "/verify", Map.of("note", "read"));
            var noId = postRefused(a, ADMIN + "/refunds/" + none.refundId() + "/mark-completed", processedBody(other, PROCESSED_EVIDENCE, true),
                    422, "REFUND_VERIFICATION_FAILED");
            assertThat(noId.get("message").asText()).contains("carries none");
            assertThat(rstatus(none.refundId())).isEqualTo("NEEDS_REVIEW");

            // A cancellation refund: no exit of this kind.
            var submitted = submit("400.19", 1);
            long cancellation = cancellationInReview(submitted);
            jdbc.update("update refund set provider_refund_id = ? where id = ?", other + "_c", cancellation);
            laterKnownPayment();
            unknownLikeRazorpay(pid(submitted));
            postOk(a, ADMIN + "/refunds/" + cancellation + "/verify", Map.of("note", "read"));
            postRefused(a, ADMIN + "/refunds/" + cancellation + "/mark-completed", processedBody(other + "_c", PROCESSED_EVIDENCE, true),
                    409, "INVALID_STATE_TRANSITION");
            assertThat(rstatus(cancellation)).isEqualTo("NEEDS_REVIEW");
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

    // ── Pay an unpaid card order another way (D-152) ─────────────────────

    @Nested
    @DisplayName("paying an unpaid card order another way")
    class AnotherWay {

        private void topUp(Buyer buyer, String amount) throws Exception {
            long userId = api.get(buyer.token(), "/api/v1/auth/me").at("/data/user/id").asLong();
            var intent = walletTopUps.create(userId, buyer.outletId(), new BigDecimal(amount),
                    UUID.randomUUID().toString());
            var payment = mockProvider.completeCheckout(intent.razorpayOrderId());
            walletTopUps.confirm(buyer.outletId(), intent.topUpId(), payment.providerPaymentId(),
                    MockPaymentProvider.TEST_SIGNATURE);
        }

        private int switchTo(String token, long orderId, String method, String key) throws Exception {
            return mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + orderId + "/payment-method")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("method", method))))
                    .andReturn().getResponse().getStatus();
        }

        private String orderMethod(long orderId) {
            return jdbc.queryForObject("select payment_method from supplier_order where id = ?", String.class, orderId);
        }

        @Test
        @DisplayName("the wallet pays, the order is released, and the card payment can no longer fund it")
        void cardToWallet() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            var submitted = submit(buyer, "400", 1);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");

            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString()))
                    .isEqualTo(200);

            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
            assertThat(orderMethod(submitted.orderId())).isEqualTo("WALLET");
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
            assertThat(jdbc.queryForObject("select cancel_requested_at is not null from payment where id = ?",
                    Boolean.class, submitted.paymentId())).isTrue();
            var intent = api.get(buyer.token(), "/api/v1/supplier-orders/" + submitted.orderId() + "/payment-intent")
                    .at("/data");
            assertThat(intent.get("payable").asBoolean()).isFalse();
            assertThat(intent.get("switchable").asBoolean()).isFalse();
            assertThat(intent.get("orderPaymentMethod").asText()).isEqualTo("WALLET");
        }

        @Test
        @DisplayName("a wallet that is short changes nothing: still a card order, still payable")
        void walletTooShort() throws Exception {
            var buyer = newBuyer();
            var submitted = submit(buyer, "400", 1);

            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString()))
                    .isGreaterThanOrEqualTo(400);

            assertThat(orderStatus(submitted.orderId())).isEqualTo("DRAFT");
            assertThat(orderMethod(submitted.orderId())).isEqualTo("PREPAID");
            assertThat(jdbc.queryForObject("select cancel_requested_at is null from payment where id = ?",
                    Boolean.class, submitted.paymentId())).isTrue();
            var intent = api.get(buyer.token(), "/api/v1/supplier-orders/" + submitted.orderId() + "/payment-intent")
                    .at("/data");
            assertThat(intent.get("payable").asBoolean()).isTrue();
            assertThat(intent.get("switchable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an order already paid by card can't be switched, and the wallet is untouched")
        void alreadyPaid() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            var submitted = submit(buyer, "400", 1);
            payAndConfirm(submitted);

            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString()))
                    .isEqualTo(409);
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(orderMethod(submitted.orderId())).isEqualTo("PREPAID");
        }

        @Test
        @DisplayName("money that still arrives on the card afterwards is not taken for the order")
        void lateCardMoney() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            var submitted = submit(buyer, "400", 1);
            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString()))
                    .isEqualTo(200);

            payAndConfirm(submitted);

            assertThat(orderMethod(submitted.orderId())).isEqualTo("WALLET");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class,
                    submitted.paymentId())).isIn("CANCEL_PENDING", "CAPTURED", "RELEASED", "CREATED");
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("repeating the request with the same key debits once")
        void replayDebitsOnce() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            var submitted = submit(buyer, "400", 1);
            String key = UUID.randomUUID().toString();

            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", key)).isEqualTo(200);
            assertThat(switchTo(buyer.token(), submitted.orderId(), "WALLET", key)).isEqualTo(200);

            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("somebody else's order is not found")
        void otherTenant() throws Exception {
            var buyer = newBuyer();
            var stranger = newBuyer();
            topUp(stranger, "1000.00");
            var submitted = submit(buyer, "400", 1);

            assertThat(switchTo(stranger.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString()))
                    .isEqualTo(404);
            assertThat(balance(stranger)).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("two switches at once fund the order once")
        void racingSwitches() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            var submitted = submit(buyer, "400", 1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var latch = new java.util.concurrent.CountDownLatch(1);
            try {
                var results = new ArrayList<java.util.concurrent.Future<Integer>>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        latch.await();
                        return switchTo(buyer.token(), submitted.orderId(), "WALLET", UUID.randomUUID().toString());
                    }));
                }
                latch.countDown();
                var codes = new ArrayList<Integer>();
                for (var result : results) {
                    codes.add(result.get());
                }
                assertThat(codes).contains(200).doesNotContain(500);
            } finally {
                pool.shutdownNow();
            }
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("cancelling an unpaid order leaves nothing to pay")
        void cancelUnpaid() throws Exception {
            var buyer = newBuyer();
            var submitted = submit(buyer, "400", 1);

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + submitted.orderId() + "/cancel")
                            .header("Authorization", "Bearer " + buyer.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("reason", "Cancelled before paying"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(200);
            assertThat(orderStatus(submitted.orderId())).isEqualTo("CANCELLED");
            var intent = api.get(buyer.token(), "/api/v1/supplier-orders/" + submitted.orderId() + "/payment-intent")
                    .at("/data");
            assertThat(intent.get("payable").asBoolean()).isFalse();
        }
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

    // ── The wallet's other money meets the money-safety stack ────────────

    /**
     * QuickScan (D-106), Razorpay top-ups (D-107) and the history and statements (D-108) were built beside the
     * withdrawal rules of D-104 and D-110, and share one wallet with them. What must hold when they meet: only
     * card refund money can go back to a card, whatever else is in the balance; every balance change is an
     * atomic wallet update with its ledger row, so the ledger always adds up to the balance; nobody waits for
     * a lock another holds while holding one the other needs; and what a person reads, on the screen or on a
     * statement, says what happened, reversals included.
     */
    @Nested
    @DisplayName("QuickScan, top-ups and withdrawals share one wallet")
    class WalletInterplay {

        @BeforeEach
        void prepare() {
            // As in WithdrawalFailures: the refund job sends every refund it finds, and the database is shared.
            jdbc.update("update refund set status = 'NEEDS_REVIEW', failure_kind = null "
                    + "where status in ('REQUESTED', 'FAILED', 'REJECTED') "
                    + "or (status = 'PROCESSING' and provider_refund_id is null)");
            jdbc.update("update refund set verified_at = utc_timestamp(6) where status in ('REVERSED', 'NEEDS_REVIEW')");
            jdbc.update("update payment set review_required_at = utc_timestamp(6), "
                    + "review_reason = 'left by an earlier test' "
                    + "where status = 'CANCEL_PENDING' and review_required_at is null");
            alertThrottle.clear();
            quickScanEnabled(true);
        }

        @org.junit.jupiter.api.AfterEach
        void restore() {
            quickScanEnabled(false);
            reset(mockProvider);
        }

        private void quickScanEnabled(boolean on) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(quickScans);
            org.springframework.test.util.ReflectionTestUtils.setField(target, "enabled", on);
        }

        // ── helpers ──────────────────────────────────────────────────────

        private Submitted captured(Buyer buyer, String unitPrice, int quantity) throws Exception {
            var submitted = submit(buyer, unitPrice, quantity);
            payAndConfirm(submitted);
            acceptAndCapture(submitted);
            return submitted;
        }

        private String pid(Submitted submitted) {
            return jdbc.queryForObject("select provider_payment_id from payment where id = ?",
                    String.class, submitted.paymentId());
        }

        private long userId(Buyer buyer) throws Exception {
            return api.get(buyer.token(), "/api/v1/auth/me").at("/data/user/id").asLong();
        }

        /** Money in through Razorpay's checkout, captured on payment, credited by the confirm (D-107). */
        private void topUp(Buyer buyer, long userId, String amount) {
            var intent = walletTopUps.create(userId, buyer.outletId(), new BigDecimal(amount),
                    UUID.randomUUID().toString());
            var payment = mockProvider.completeCheckout(intent.razorpayOrderId());
            walletTopUps.confirm(buyer.outletId(), intent.topUpId(), payment.providerPaymentId(),
                    MockPaymentProvider.TEST_SIGNATURE);
        }

        private com.costonomy.mp.quickscan.web.dto.QuickScanDtos.PaymentResponse quickScan(
                Buyer buyer, long userId, String amount) {
            return quickScans.payFromWallet(userId, buyer.outletId(),
                    new com.costonomy.mp.quickscan.service.QuickScanService.PayRequest(
                            "shop@okhdfcbank", "Shop", new BigDecimal(amount), "test"),
                    UUID.randomUUID().toString());
        }

        private String errorCode(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
            return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .at("/error/code").asText();
        }

        private long count(String sql, Object... args) {
            return jdbc.queryForObject(sql, Long.class, args);
        }

        private long ledgerRowsOf(Buyer buyer, String kind) {
            return count("select count(*) from wallet_transaction wt join wallet w on w.id = wt.wallet_id "
                    + "where w.outlet_id = ? and wt.kind = ?", buyer.outletId(), kind);
        }

        /** After every scenario: the ledger adds up to the balance, each row's balance follows the row before. */
        private void assertBooks(Buyer buyer) {
            BigDecimal net = jdbc.queryForObject("""
                    select coalesce(sum(case wt.direction when 'CREDIT' then wt.amount else -wt.amount end), 0)
                      from wallet_transaction wt join wallet w on w.id = wt.wallet_id where w.outlet_id = ?
                    """, BigDecimal.class, buyer.outletId());
            assertThat(net).describedAs("sum of the ledger").isEqualByComparingTo(balance(buyer));
            assertThat(balance(buyer)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            var rows = jdbc.queryForList("""
                    select wt.direction, wt.amount, wt.balance_after from wallet_transaction wt
                      join wallet w on w.id = wt.wallet_id where w.outlet_id = ? order by wt.id
                    """, buyer.outletId());
            BigDecimal running = BigDecimal.ZERO;
            for (var row : rows) {
                var amount = (BigDecimal) row.get("amount");
                running = "CREDIT".equals(row.get("direction")) ? running.add(amount) : running.subtract(amount);
                assertThat((BigDecimal) row.get("balance_after")).describedAs("balance after a row")
                        .isEqualByComparingTo(running);
            }
            assertThat(count("""
                    select count(*) from wallet_transaction wt join wallet w on w.id = wt.wallet_id
                     where w.outlet_id = ? and wt.kind = 'WITHDRAWAL_REVERSAL'
                    """, buyer.outletId())).describedAs("reversal rows")
                    .isEqualTo(count("""
                            select count(*) from refund r join payment p on p.id = r.payment_id
                             where p.outlet_id = ? and r.reason = 'WALLET_WITHDRAWAL' and r.status = 'REVERSED'
                            """, buyer.outletId()));
        }

        // ── what may leave ───────────────────────────────────────────────

        @Test
        @DisplayName("a top-up is not refund money: only what a card refunded can go back to a card, whatever the balance")
        void topUpMoneyCannotBeWithdrawn() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            var source = captured(buyer, "400", 1);
            creditWallet(source, "100.00");
            topUp(buyer, user, "500.00");
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");

            var refused = withdrawCall(buyer, "100.01", UUID.randomUUID().toString());

            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(errorCode(refused)).isEqualTo("WITHDRAWAL_EXCEEDS_REFUNDABLE");
            assertThat(json.readTree(refused.getContentAsString()).at("/error/details/withdrawableNow").decimalValue())
                    .isEqualByComparingTo("100.00");
            assertThat(balance(buyer)).isEqualByComparingTo("600.00");
            assertThat(withdrawalsOf(source)).isZero();

            withdraw(buyer, "100.00", UUID.randomUUID().toString());
            paymentJobs.processRefunds();
            assertThat(mockProvider.refundedOf(pid(source))).isEqualByComparingTo("100.00");
            assertThat(balance(buyer)).isEqualByComparingTo("500.00");
            // The top-up's own row is untouched: it is the wallet's money, not the card's refund.
            assertThat(ledgerRowsOf(buyer, "TOP_UP")).isEqualTo(1);
            assertBooks(buyer);
        }

        @Test
        @DisplayName("a QuickScan return is not a card refund: a declined or reversed payout comes back spendable, not withdrawable")
        void quickScanReturnCannotBeWithdrawn() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            topUp(buyer, user, "500.00");

            var declined = quickScan(buyer, user, "75.13");
            assertThat(declined.status().name()).isEqualTo("FAILED");
            var pending = quickScan(buyer, user, "20.31");
            assertThat(pending.status().name()).isEqualTo("PAYOUT_PENDING");
            quickScans.settlePending(pending.id());   // the provider pulled it back: REVERSED

            assertThat(balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(ledgerRowsOf(buyer, "QUICKSCAN_PAYMENT")).isEqualTo(2);
            assertThat(ledgerRowsOf(buyer, "QUICKSCAN_RETURN")).isEqualTo(2);

            // 500 in the wallet, none of it a card's refund.
            var refused = withdrawCall(buyer, "1.00", UUID.randomUUID().toString());
            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(errorCode(refused)).isEqualTo("WITHDRAWAL_EXCEEDS_REFUNDABLE");
            assertThat(count("select count(*) from refund r join payment p on p.id = r.payment_id "
                    + "where p.outlet_id = ? and r.reason = 'WALLET_WITHDRAWAL'", buyer.outletId())).isZero();

            // Refund money arrives; exactly that much can go, the returns and the top-up still cannot.
            var source = captured(buyer, "400", 1);
            creditWallet(source, "50.00");
            assertThat(withdrawStatus(buyer, "50.01", UUID.randomUUID().toString())).isEqualTo(422);
            assertThat(withdraw(buyer, "50.00", UUID.randomUUID().toString()).at("/data/balance").decimalValue())
                    .isEqualByComparingTo("500.00");
            assertBooks(buyer);
        }

        @Test
        @DisplayName("refund money a QuickScan took and gave back is still the card's: it can go back, once, and no more than was refunded")
        void quickScanReturnOfRefundMoneyStaysBoundedByTheCardRefund() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            var source = captured(buyer, "400", 1);
            creditWallet(source, "100.13");
            quickScan(buyer, user, "100.13");           // declined, returned
            assertThat(balance(buyer)).isEqualByComparingTo("100.13");

            withdraw(buyer, "100.13", UUID.randomUUID().toString());
            assertThat(withdrawStatus(buyer, "0.01", UUID.randomUUID().toString())).isEqualTo(400);
            paymentJobs.processRefunds();
            assertThat(mockProvider.refundedOf(pid(source))).isEqualByComparingTo("100.13");
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertBooks(buyer);
        }

        @Test
        @DisplayName("QuickScan spends refund money like any other balance, and what is left goes back, no more")
        void quickScanSpendingReducesWhatCanGoBack() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            var source = captured(buyer, "400", 1);
            creditWallet(source, "100.00");
            topUp(buyer, user, "200.00");
            quickScan(buyer, user, "250.00");           // paid: balance 50
            assertThat(balance(buyer)).isEqualByComparingTo("50.00");

            assertThat(withdrawStatus(buyer, "50.01", UUID.randomUUID().toString())).isEqualTo(400);
            withdraw(buyer, "50.00", UUID.randomUUID().toString());
            assertThat(balance(buyer)).isEqualByComparingTo("0");
            assertBooks(buyer);
        }

        // ── what a person reads ──────────────────────────────────────────

        @Test
        @DisplayName("a withdrawal put back reads as RETURNED with its own WITHDRAWAL_REVERSAL credit, in the history and on a statement that still adds up")
        void reversalIsShownAndStatementReconciles() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            var source = captured(buyer, "400", 1);
            topUp(buyer, user, "300.00");
            creditWallet(source, "400.00");
            long refundId = withdraw(buyer, "400.00", UUID.randomUUID().toString())
                    .at("/data/parts/0/refundId").asLong();
            quickScan(buyer, user, "50.00");
            doThrow(new PaymentProviderException("refused", false, "400",
                    ProviderFailureKind.NOT_CAPTURED, "The payment status should be captured"))
                    .when(mockProvider).refund(eq(pid(source)), any(), anyString(), any());

            // The refund is refused and none of ours is at the provider: the money goes back (D-110).
            paymentJobs.processRefunds();

            assertThat(jdbc.queryForObject("select status from refund where id = ?", String.class, refundId))
                    .isEqualTo("REVERSED");
            assertThat(balance(buyer)).isEqualByComparingTo("650.00");

            String base = "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions";
            var all = api.get(buyer.token(), base).at("/data/items");
            JsonNode withdrawalRow = null;
            JsonNode reversalRow = null;
            for (var item : all) {
                if ("WITHDRAWAL".equals(item.get("kind").asText())) {
                    withdrawalRow = item;
                } else if ("WITHDRAWAL_REVERSAL".equals(item.get("kind").asText())) {
                    reversalRow = item;
                }
            }
            assertThat(withdrawalRow).isNotNull();
            assertThat(withdrawalRow.get("status").asText()).isEqualTo("RETURNED");
            assertThat(withdrawalRow.get("refundStatus").asText()).isEqualTo("REVERSED");
            assertThat(withdrawalRow.get("direction").asText()).isEqualTo("DEBIT");
            assertThat(reversalRow).isNotNull();
            assertThat(reversalRow.get("direction").asText()).isEqualTo("CREDIT");
            assertThat(reversalRow.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(reversalRow.get("amount").decimalValue()).isEqualByComparingTo("400.00");
            assertThat(reversalRow.get("refundStatus").isNull()).isTrue();

            // Filters: RETURNED is the withdrawal that came back; COMPLETED is everything but it; IN_PROGRESS is nothing.
            var returned = api.get(buyer.token(), base + "?statuses=RETURNED").at("/data/items");
            assertThat(returned).hasSize(1);
            assertThat(returned.get(0).get("kind").asText()).isEqualTo("WITHDRAWAL");
            var completed = api.get(buyer.token(), base + "?statuses=COMPLETED").at("/data/items");
            assertThat(completed).extracting(item -> item.get("kind").asText())
                    .doesNotContain("WITHDRAWAL").contains("WITHDRAWAL_REVERSAL", "TOP_UP", "QUICKSCAN_PAYMENT");
            assertThat(api.get(buyer.token(), base + "?statuses=IN_PROGRESS").at("/data/items")).isEmpty();
            var byKind = api.get(buyer.token(), base + "?kinds=WITHDRAWAL_REVERSAL").at("/data/items");
            assertThat(byKind).hasSize(1);

            // A refund the provider refused and that is being checked is on its way, not returned yet.
            jdbc.update("update refund set status = 'REJECTED' where id = ?", refundId);
            assertThat(api.get(buyer.token(), base + "?statuses=IN_PROGRESS").at("/data/items")).hasSize(1);
            assertThat(api.get(buyer.token(), base + "?statuses=RETURNED").at("/data/items")).isEmpty();
            jdbc.update("update refund set status = 'REVERSED' where id = ?", refundId);

            // The statement is built from the ledger and refuses to exist unless it reconciles.
            var csv = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/outlets/" + buyer.outletId() + "/wallet/statement?range=LAST_30&format=CSV")
                            .header("Authorization", "Bearer " + buyer.token()))
                    .andReturn().getResponse();
            assertThat(csv.getStatus()).describedAs(csv.getContentAsString()).isEqualTo(200);
            String text = csv.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).contains("Withdrawal returned to your wallet").contains("Sent back to your card or bank")
                    .contains("Paid a shop (QuickScan)").contains("Money added");
            var pdf = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/outlets/" + buyer.outletId() + "/wallet/statement?range=LAST_30&format=PDF")
                            .header("Authorization", "Bearer " + buyer.token()))
                    .andReturn().getResponse();
            assertThat(pdf.getStatus()).isEqualTo(200);
            assertBooks(buyer);
        }

        // ── concurrency over one wallet ──────────────────────────────────

        @Test
        @DisplayName("what a withdrawal took cannot be spent by QuickScan, and what QuickScan spent cannot be withdrawn: refused, nothing moves")
        void eachSeesWhatTheOtherTook() throws Exception {
            var buyer = newBuyer();
            long user = userId(buyer);
            var source = captured(buyer, "400", 1);
            creditWallet(source, "400.00");
            topUp(buyer, user, "500.00");                // 900

            withdraw(buyer, "400.00", UUID.randomUUID().toString());     // 500 left
            assertThatThrownBy(() -> quickScan(buyer, user, "600.00"))
                    .isInstanceOf(BusinessException.class);
            assertThat(balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(count("select count(*) from quickscan_payment where outlet_id = ?", buyer.outletId())).isZero();
            assertBooks(buyer);

            quickScan(buyer, user, "450.00");                              // 50 left
            var refused = withdrawCall(buyer, "60.00", UUID.randomUUID().toString());
            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("50.00");
            assertBooks(buyer);
        }

        @Test
        @DisplayName("a QuickScan payment and a withdrawal of the same wallet at once: no deadlock, both or exactly one succeed, the ledger adds up")
        void quickScanAndWithdrawalAtOnce() throws Exception {
            for (int race = 0; race < 5; race++) {
                var buyer = newBuyer();
                long user = userId(buyer);
                var source = captured(buyer, "400", 1);
                creditWallet(source, "400.00");
                topUp(buyer, user, "500.00");             // 900 in all, 400 of it the card's
                // 400 + 500 fits in 900 (both succeed); 400 + 600 does not (exactly one does).
                boolean fits = race % 2 == 0;
                String scan = fits ? "500.00" : "600.00";

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(2);
                var withdrawing = pool.submit(() -> {
                    start.await();
                    return withdrawStatus(buyer, "400.00", UUID.randomUUID().toString());
                });
                var scanning = pool.submit(() -> {
                    start.await();
                    try {
                        return quickScan(buyer, user, scan).status().name();
                    } catch (BusinessException refused) {
                        return "REFUSED";
                    }
                });
                start.countDown();
                int withdrawal = withdrawing.get(60, TimeUnit.SECONDS);
                String payment = scanning.get(60, TimeUnit.SECONDS);
                pool.shutdown();

                if (fits) {
                    assertThat(withdrawal).isEqualTo(200);
                    assertThat(payment).isEqualTo("PAID");
                    assertThat(balance(buyer)).isEqualByComparingTo("0");
                } else {
                    // Exactly one of them had the money; never both, never neither.
                    assertThat((withdrawal == 200 ? 1 : 0) + ("PAID".equals(payment) ? 1 : 0)).isEqualTo(1);
                    assertThat(balance(buyer)).isEqualByComparingTo(withdrawal == 200 ? "500.00" : "300.00");
                }
                assertBooks(buyer);
            }
        }

        @Test
        @DisplayName("top-ups, QuickScan payments, withdrawals and a reversal on one wallet at once: nobody deadlocks, the ledger adds up, each thing happens once")
        void everythingAtOnceOnOneWallet() throws Exception {
            for (int round = 0; round < 3; round++) {
                var buyer = newBuyer();
                long user = userId(buyer);
                var refused = captured(buyer, "400", 1);     // its refund will be refused: a reversal
                var good = captured(buyer, "400", 1);
                topUp(buyer, user, "1000.00");
                creditWallet(refused, "300.00");
                long refusedRefund = withdraw(buyer, "300.00", UUID.randomUUID().toString())
                        .at("/data/parts/0/refundId").asLong();
                creditWallet(good, "200.00");
                // 1000 + 300 - 300 + 200
                assertThat(balance(buyer)).isEqualByComparingTo("1200.00");
                doThrow(new PaymentProviderException("refused", false, "400",
                        ProviderFailureKind.NOT_CAPTURED, "The payment status should be captured"))
                        .when(mockProvider).refund(eq(pid(refused)), any(), anyString(), any());

                var pool = Executors.newFixedThreadPool(6);
                var start = new CountDownLatch(1);
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                // The reversal, +300.
                jobs.add(pool.submit(() -> { start.await(); paymentJobs.processRefunds(); return null; }));
                // Three QuickScan payments, -300 (one declined and returned: net 0 for it).
                for (String amount : List.of("100.00", "100.00", "100.00", "40.13")) {
                    jobs.add(pool.submit(() -> { start.await(); return quickScan(buyer, user, amount); }));
                }
                // A top-up confirmed, +200.
                jobs.add(pool.submit(() -> { start.await(); topUp(buyer, user, "200.00"); return null; }));
                // A withdrawal of what the card refunded, -200.
                jobs.add(pool.submit(() -> {
                    start.await();
                    return withdrawStatus(buyer, "200.00", UUID.randomUUID().toString());
                }));
                start.countDown();
                for (var job : jobs) {
                    job.get(90, TimeUnit.SECONDS);
                }
                pool.shutdown();
                // Whatever the job did not reach while the withdrawal was being made is reached now.
                paymentJobs.processRefunds();
                paymentJobs.processRefunds();

                assertThat(jdbc.queryForObject("select status from refund where id = ?", String.class, refusedRefund))
                        .isEqualTo("REVERSED");
                assertThat(count("select count(*) from wallet_transaction where refund_id = ? "
                        + "and kind = 'WITHDRAWAL_REVERSAL'", refusedRefund)).isEqualTo(1);
                // 1200 + 300 (reversal) - 300 (three paid) - 40.13 + 40.13 (declined, returned) + 200 - 200
                assertThat(balance(buyer)).isEqualByComparingTo("1200.00");
                assertBooks(buyer);
                var statement = mvc.perform(MockMvcRequestBuilders
                                .get("/api/v1/outlets/" + buyer.outletId() + "/wallet/statement?range=LAST_30&format=CSV")
                                .header("Authorization", "Bearer " + buyer.token()))
                        .andReturn().getResponse();
                assertThat(statement.getStatus()).describedAs("a statement of a wallet everything happened to")
                        .isEqualTo(200);
                reset(mockProvider);
            }
        }
    }
}
