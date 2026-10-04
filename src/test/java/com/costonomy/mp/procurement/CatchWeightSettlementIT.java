package com.costonomy.mp.procurement;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestOrder;
import com.costonomy.mp.wallet.service.WalletService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-124: catch-weight settles at dispatch.
 *
 * <p>Rs 100/kg, 5% GST, 10 kg accepted: the agreed line is 1,000 + 50 = Rs 1,050. Every case asserts what
 * the ledgers, the payment, the invoice and the order say afterwards, and every refusal asserts that
 * <em>nothing was written</em>, because a rejection that still half-applied is the bug this exists to prevent.
 */
@AutoConfigureMockMvc
class CatchWeightSettlementIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider mockProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private WalletService walletService;

    private ApiClient api;
    private TestOrder orders;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    private record Placed(Buyer buyer, Seller seller, TestOrder.Created created, long itemId) {
        long orderId() {
            return created.orderId();
        }
    }

    private record Reply(int status, JsonNode body) {
        String errorCode() {
            return body.at("/error/code").asText();
        }
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

    private Seller newSeller() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Fresh Meats Pvt Ltd", "displayName", "Fresh Meats",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Fresh Meats store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /** A KG SKU at Rs 100 + 5% GST, flagged catch-weight unless {@code catchWeight} is false. */
    private long sku(Seller seller, boolean catchWeight) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, "chicken");
        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "CHK-" + productId,
                        "name", "Chicken", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "100", "gstRate", "5")).at("/data/id").asLong();
        jdbc.update("update supplier_sku set is_catch_weight = ? where id = ?", catchWeight, skuId);
        return skuId;
    }

    private long itemOf(long orderId) {
        return jdbc.queryForObject("select id from supplier_order_item where supplier_order_id = ? limit 1",
                Long.class, orderId);
    }

    /** A card order, paid and confirmed: held, not yet taken. */
    private Placed cardOrder(boolean catchWeight) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long skuId = sku(seller, catchWeight);
        var created = orders.place(buyer.token(), buyer.outletId(), seller.token(), skuId, 10);
        var payment = mockProvider.completeCheckout(created.providerOrderId());
        api.post(buyer.token(), "/api/v1/payments/" + created.paymentId() + "/confirm",
                Map.of("providerPaymentId", payment.providerPaymentId()));
        assertThat(status(created.orderId())).isEqualTo("CONFIRMED");
        return new Placed(buyer, seller, created, itemOf(created.orderId()));
    }

    /** A wallet order: funded from a topped-up balance, confirmed at once. */
    private Placed walletOrder() throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long skuId = sku(seller, true);
        api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up",
                Map.of("amount", "5000.00"));
        var created = orders.place(buyer.token(), buyer.outletId(), seller.token(), skuId, 10, 10,
                "PICKUP", "WALLET", null);
        assertThat(status(created.orderId())).isEqualTo("CONFIRMED");
        return new Placed(buyer, seller, created, itemOf(created.orderId()));
    }

    /** A credit order: drawn and invoiced when the supplier's answer confirms it. */
    private Placed creditOrder() throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long skuId = sku(seller, true);
        mvc.perform(MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("creditEnabled", true,
                        "defaultCreditPeriodDays", 30, "defaultGracePeriodDays", 5))));
        long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                "requestedLimit", "100000", "requestedDays", 30,
                "purpose", "PROCUREMENT")).at("/data/id").asLong();
        api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve", Map.of());
        var created = orders.place(buyer.token(), buyer.outletId(), seller.token(), skuId, 10, 10,
                "PICKUP", "CREDIT", null);
        assertThat(status(created.orderId())).isEqualTo("CONFIRMED");
        return new Placed(buyer, seller, created, itemOf(created.orderId()));
    }

    private Reply post(String token, String path, Object body) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        String text = result.getContentAsString();
        return new Reply(result.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private Reply weigh(Placed p, String... itemAndReading) throws Exception {
        var weights = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < itemAndReading.length; i += 2) {
            weights.add(Map.of("supplierOrderItemId", Long.parseLong(itemAndReading[i]),
                    "dispatchedWeight", itemAndReading[i + 1]));
        }
        return post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/weights",
                Map.of("weights", weights));
    }

    private Reply weighItem(Placed p, String reading) throws Exception {
        return weigh(p, String.valueOf(p.itemId()), reading);
    }

    private Reply step(Placed p, String step) throws Exception {
        return post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/" + step, Map.of());
    }

    private void toPreparing(Placed p) throws Exception {
        assertThat(step(p, "preparing").status()).isEqualTo(200);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId);
    }

    private BigDecimal orderFigure(long orderId, String column) {
        return jdbc.queryForObject("select " + column + " from supplier_order where id = ?",
                BigDecimal.class, orderId);
    }

    private BigDecimal itemFigure(long itemId, String column) {
        return jdbc.queryForObject("select " + column + " from supplier_order_item where id = ?",
                BigDecimal.class, itemId);
    }

    private BigDecimal captured(long paymentId) {
        return jdbc.queryForObject("select captured_amount from payment where id = ?",
                BigDecimal.class, paymentId);
    }

    private int walletRows(long orderId) {
        return jdbc.queryForObject("select count(*) from wallet_transaction where supplier_order_id = ?",
                Integer.class, orderId);
    }

    private int walletRows(long orderId, String kind) {
        return jdbc.queryForObject(
                "select count(*) from wallet_transaction where supplier_order_id = ? and kind = ?",
                Integer.class, orderId, kind);
    }

    private BigDecimal walletBalance(long outletId) {
        return walletService.balanceOf(outletId);
    }

    /** Ready, then the capture job: for a card, the moment the money is taken. */
    private void readyAndCapture(Placed p) throws Exception {
        assertThat(step(p, "ready").status()).isEqualTo(200);
        paymentJobs.capturePending();
    }

    // ── card ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a card order")
    class Card {

        @Test
        @DisplayName("weighing 9.6 kg captures 1,008: the shortfall is released, never charged or credited")
        void underweightCapturesWeighedAmount() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);

            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);

            // Weighing moved no money: the card is still only held and the wallet untouched.
            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("0");
            assertThat(walletRows(p.orderId())).isZero();
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount")).isEqualByComparingTo("42.00");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1008.00");

            readyAndCapture(p);

            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("1008.00");
            assertThat(walletRows(p.orderId())).isZero();
            assertThat(orderFigure(p.orderId(), "accepted_amount")).isEqualByComparingTo("1050.00");
            assertThat(itemFigure(p.itemId(), "billable_quantity")).isEqualByComparingTo("9.6");
            assertThat(itemFigure(p.itemId(), "dispatched_weight")).isEqualByComparingTo("9.6");
        }

        @Test
        @DisplayName("weighing 10.4 kg bills 10: the supplier gives the extra 0.4 kg away")
        void overweightWithinBandIsBilledAtAccepted() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);

            assertThat(weighItem(p, "10.4").status()).isEqualTo(200);
            readyAndCapture(p);

            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("1050.00");
            assertThat(itemFigure(p.itemId(), "billable_quantity")).isEqualByComparingTo("10");
            assertThat(itemFigure(p.itemId(), "dispatched_weight")).isEqualByComparingTo("10.4");
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount")).isEqualByComparingTo("0.00");
            assertThat(walletRows(p.orderId())).isZero();
        }

        @Test
        @DisplayName("a reading outside -20%/+10% is refused, and the order's figures are untouched")
        void outsideTheBandIsRefusedAndNothingChanges() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);

            for (String reading : List.of("12", "7.9")) {
                var reply = weighItem(p, reading);
                assertThat(reply.status()).describedAs(reading).isGreaterThanOrEqualTo(400);
                assertThat(reply.errorCode()).isEqualTo("VALIDATION_ERROR");
            }

            assertThat(itemFigure(p.itemId(), "dispatched_weight")).isNull();
            assertThat(itemFigure(p.itemId(), "billable_quantity")).isNull();
            assertThat(itemFigure(p.itemId(), "line_total")).isEqualByComparingTo("1050.00");
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount")).isEqualByComparingTo("0.00");
            assertThat(walletRows(p.orderId())).isZero();
        }

        @Test
        @DisplayName("the same line twice in one request is refused, and nothing is written")
        void duplicateLinesAreRefused() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            String item = String.valueOf(p.itemId());

            // 50 copies would have credited the wallet about fifty lines' worth.
            String[] copies = new String[100];
            for (int i = 0; i < 100; i += 2) {
                copies[i] = item;
                copies[i + 1] = "9.6";
            }
            var reply = weigh(p, copies);

            assertThat(reply.status()).isGreaterThanOrEqualTo(400);
            assertThat(itemFigure(p.itemId(), "dispatched_weight")).isNull();
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount")).isEqualByComparingTo("0.00");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isNull();
            assertThat(walletRows(p.orderId())).isZero();
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a line that is not sold by catch weight cannot be weighed")
        void aFixedPackLineCannotBeWeighed() throws Exception {
            var p = cardOrder(false);
            toPreparing(p);

            var reply = weighItem(p, "9.6");

            assertThat(reply.status()).isGreaterThanOrEqualTo(400);
            assertThat(itemFigure(p.itemId(), "dispatched_weight")).isNull();
            assertThat(itemFigure(p.itemId(), "line_total")).isEqualByComparingTo("1050.00");
        }

        @Test
        @DisplayName("an order with an unweighed catch-weight line cannot be marked ready")
        void unweighedLineBlocksReady() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);

            var reply = step(p, "ready");

            assertThat(reply.status()).isGreaterThanOrEqualTo(400);
            assertThat(status(p.orderId())).isEqualTo("PREPARING");
            paymentJobs.capturePending();
            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("once ready, weighing is refused and the settled figures do not move")
        void weighingAfterReadyIsRefused() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            readyAndCapture(p);

            var reply = weighItem(p, "8.5");

            assertThat(reply.status()).isGreaterThanOrEqualTo(400);
            assertThat(itemFigure(p.itemId(), "billable_quantity")).isEqualByComparingTo("9.6");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1008.00");
            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("1008.00");
        }

        @Test
        @DisplayName("a re-weigh replaces the earlier reading: the last one before ready decides")
        void reWeighLastReadingWins() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);

            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1008.00");
            assertThat(weighItem(p, "10.0").status()).isEqualTo(200);
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1050.00");
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount")).isEqualByComparingTo("0.00");
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);

            readyAndCapture(p);

            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("1008.00");
            assertThat(walletRows(p.orderId())).isZero();
        }

        @Test
        @DisplayName("weighing then cancelling releases the hold in full: there is nothing to reverse")
        void weighThenCancelLeavesNoCredit() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);

            var cancel = post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/supplier-cancel",
                    Map.of("reason", "OUT_OF_STOCK"));
            assertThat(cancel.status()).isEqualTo(200);
            paymentJobs.capturePending();

            assertThat(status(p.orderId())).isEqualTo("CANCELLED");
            assertThat(captured(p.created().paymentId())).isEqualByComparingTo("0");
            // The platform used to lose the 42 here: hold released AND a wallet credit kept.
            assertThat(walletRows(p.orderId())).isZero();
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("0");
        }
    }

    // ── wallet ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a wallet order")
    class Wallet {

        @Test
        @DisplayName("weighing 9.6 kg returns the difference once, at ready")
        void underweightReturnsTheDifferenceOnce() throws Exception {
            var p = walletOrder();
            BigDecimal afterOrder = walletBalance(p.buyer().outletId());
            assertThat(afterOrder).isEqualByComparingTo("3950.00");
            toPreparing(p);

            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            // Weighing alone moves nothing.
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("3950.00");
            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isZero();

            assertThat(step(p, "ready").status()).isEqualTo(200);

            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select amount from wallet_transaction
                     where supplier_order_id = ? and kind = 'ORDER_ADJUSTMENT' and direction = 'CREDIT'
                       and reference = ?""", BigDecimal.class, p.orderId(), "order-settle-" + p.orderId()))
                    .isEqualByComparingTo("42.00");
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("3992.00");

            // A repeat (a retry, a duplicate event) credits nothing more.
            walletService.settleOrder(p.orderId(), orderFigure(p.orderId(), "final_payable_amount"));
            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isEqualTo(1);
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("3992.00");
            assertThat(walletService.refundableForOrder(p.orderId())).isEqualByComparingTo("1008.00");
        }

        @Test
        @DisplayName("weighing 10.4 kg changes nothing: no debit, no credit")
        void overweightMovesNoMoney() throws Exception {
            var p = walletOrder();
            toPreparing(p);

            assertThat(weighItem(p, "10.4").status()).isEqualTo(200);
            assertThat(step(p, "ready").status()).isEqualTo(200);

            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isZero();
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("3950.00");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1050.00");
        }

        @Test
        @DisplayName("weighing then cancelling returns the full payment once, with no separate adjustment")
        void weighThenCancelReturnsThePaymentOnce() throws Exception {
            var p = walletOrder();
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);

            var cancel = post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/supplier-cancel",
                    Map.of("reason", "OUT_OF_STOCK"));
            assertThat(cancel.status()).isEqualTo(200);

            assertThat(walletRows(p.orderId(), "ORDER_REFUND")).isEqualTo(1);
            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isZero();
            // Back to where it started: not 42 richer.
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("5000.00");
        }
    }

    // ── credit ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a credit order")
    class Credit {

        @Test
        @DisplayName("weighing 9.6 kg brings the invoice and the drawn amount down to 1,008, once")
        void underweightReducesTheInvoiceAndExposure() throws Exception {
            var p = creditOrder();
            long invoiceId = jdbc.queryForObject("select id from credit_invoice where supplier_order_id = ?",
                    Long.class, p.orderId());
            long agreementId = jdbc.queryForObject("select credit_agreement_id from credit_invoice where id = ?",
                    Long.class, invoiceId);
            assertThat(jdbc.queryForObject("select amount from credit_invoice where id = ?",
                    BigDecimal.class, invoiceId)).isEqualByComparingTo("1050.00");
            assertThat(jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?",
                    BigDecimal.class, agreementId)).isEqualByComparingTo("1050.00");
            toPreparing(p);

            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            // Weighing alone leaves the debt alone.
            assertThat(jdbc.queryForObject("select amount from credit_invoice where id = ?",
                    BigDecimal.class, invoiceId)).isEqualByComparingTo("1050.00");

            assertThat(step(p, "ready").status()).isEqualTo(200);

            assertThat(jdbc.queryForObject("select amount from credit_invoice where id = ?",
                    BigDecimal.class, invoiceId)).isEqualByComparingTo("1008.00");
            assertThat(jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?",
                    BigDecimal.class, agreementId)).isEqualByComparingTo("1008.00");
            assertThat(jdbc.queryForObject("""
                    select count(*) from credit_transaction
                     where supplier_order_id = ? and transaction_type = 'ADJUSTMENT'""",
                    Integer.class, p.orderId())).isEqualTo(1);
            // No cash appears in a wallet: credit money never passed through Mandi.
            assertThat(walletRows(p.orderId())).isZero();
        }
    }

    // ── receiving ────────────────────────────────────────────────────────

    private Reply receive(Placed p, String received, String damaged, String missing) throws Exception {
        return post(p.buyer().token(), "/api/v1/supplier-orders/" + p.orderId() + "/receive",
                Map.of("items", List.of(Map.of("supplierOrderItemId", p.itemId(),
                        "receivedQuantity", received, "damagedQuantity", damaged,
                        "missingQuantity", missing, "rejectionReason", "SHORT_DELIVERY"))));
    }

    @Nested
    @DisplayName("receiving a weighed order")
    class Receiving {

        @Test
        @DisplayName("the buyer accounts for the weighed 9.6 kg, not the ordered 10: no second refund for the shortfall")
        void receivesAgainstTheWeighedQuantity() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            readyAndCapture(p);

            // The ordered 10 is no longer what has to add up.
            assertThat(receive(p, "10", "0", "0").status()).isGreaterThanOrEqualTo(400);
            assertThat(status(p.orderId())).isEqualTo("READY_FOR_PICKUP");

            var ok = receive(p, "9.6", "0", "0");

            assertThat(ok.status()).isEqualTo(200);
            assertThat(status(p.orderId())).isEqualTo("COMPLETED");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("1008.00");
            assertThat(orderFigure(p.orderId(), "doorstep_refund_amount")).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("select count(*) from refund where supplier_order_id = ?",
                    Integer.class, p.orderId())).isZero();
            assertThat(walletRows(p.orderId())).isZero();
        }

        @Test
        @DisplayName("a card order's doorstep rejection is a withdrawable refund of exactly the rejected goods")
        void cardRejectionIsAWithdrawableRefund() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            readyAndCapture(p);

            // 0.1 kg of the 9.6 weighed is missing: 10.00 + 0.50 GST.
            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);

            assertThat(orderFigure(p.orderId(), "doorstep_refund_amount")).isEqualByComparingTo("10.50");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("997.50");
            assertThat(jdbc.queryForObject("""
                    select amount from refund where supplier_order_id = ?
                       and reason = 'DOORSTEP_REJECTION' and destination = 'WALLET'""",
                    BigDecimal.class, p.orderId())).isEqualByComparingTo("10.50");
            // Kind REFUND is the one a withdrawal can send back to the card; the closed ORDER_ADJUSTMENT
            // balance for card money is gone.
            assertThat(walletRows(p.orderId(), "REFUND")).isEqualTo(1);
            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isZero();
            assertThat(jdbc.queryForObject("select refunded_amount from payment where id = ?",
                    BigDecimal.class, p.created().paymentId())).isEqualByComparingTo("10.50");
        }

        @Test
        @DisplayName("a wallet order's doorstep rejection is credited back to the wallet once")
        void walletRejectionIsCreditedBack() throws Exception {
            var p = walletOrder();
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            assertThat(step(p, "ready").status()).isEqualTo(200);
            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("3992.00");

            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);

            assertThat(walletBalance(p.buyer().outletId())).isEqualByComparingTo("4002.50");
            assertThat(walletRows(p.orderId(), "ORDER_ADJUSTMENT")).isEqualTo(2);
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("997.50");
        }

        @Test
        @DisplayName("a credit order's doorstep rejection brings the invoice down, with no cash credited")
        void creditRejectionReducesTheInvoice() throws Exception {
            var p = creditOrder();
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            assertThat(step(p, "ready").status()).isEqualTo(200);

            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);

            assertThat(jdbc.queryForObject("select amount from credit_invoice where supplier_order_id = ?",
                    BigDecimal.class, p.orderId())).isEqualByComparingTo("997.50");
            assertThat(walletRows(p.orderId())).isZero();
        }

        @Test
        @DisplayName("rejecting the whole weighed line refunds exactly what it was billed")
        void wholeLineRejectionRefundsTheBilledTotal() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "9.6").status()).isEqualTo(200);
            readyAndCapture(p);

            assertThat(receive(p, "0", "0", "9.6").status()).isEqualTo(200);

            assertThat(orderFigure(p.orderId(), "doorstep_refund_amount")).isEqualByComparingTo("1008.00");
            assertThat(orderFigure(p.orderId(), "final_payable_amount")).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("select refunded_amount from payment where id = ?",
                    BigDecimal.class, p.created().paymentId())).isEqualByComparingTo("1008.00");
        }
    }

    // ── concurrency ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("racing requests")
    class Concurrency {

        @Test
        @DisplayName("two weighings of one order at once leave figures that add up to one of the readings")
        void twoWeighingsSettleToOneReading() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var one = pool.submit(() -> { start.await(); return weighItem(p, "9.6"); });
                var two = pool.submit(() -> { start.await(); return weighItem(p, "9.8"); });
                start.countDown();
                one.get();
                two.get();
            } finally {
                pool.shutdownNow();
            }

            BigDecimal billable = itemFigure(p.itemId(), "billable_quantity");
            assertThat(billable.compareTo(new BigDecimal("9.6")) == 0
                    || billable.compareTo(new BigDecimal("9.8")) == 0).isTrue();
            // The order's total is exactly the sum of its lines, whichever reading won.
            assertThat(orderFigure(p.orderId(), "weight_adjustment_amount"))
                    .isEqualByComparingTo(itemFigure(p.itemId(), "weight_delta_amount"));
            assertThat(orderFigure(p.orderId(), "final_payable_amount"))
                    .isEqualByComparingTo(orderFigure(p.orderId(), "accepted_amount")
                            .subtract(itemFigure(p.itemId(), "weight_delta_amount")));
        }

        @Test
        @DisplayName("a weighing racing the ready tap never leaves the capture different from the final payable")
        void weighingRacingReadyNeverDisagreesWithTheCapture() throws Exception {
            var p = cardOrder(true);
            toPreparing(p);
            assertThat(weighItem(p, "10.0").status()).isEqualTo(200);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var weighing = pool.submit(() -> { start.await(); return weighItem(p, "9.6"); });
                var ready = pool.submit(() -> { start.await(); return step(p, "ready"); });
                start.countDown();
                weighing.get();
                ready.get();
            } finally {
                pool.shutdownNow();
            }
            paymentJobs.capturePending();

            // Either the weighing landed first (1,008 captured and 1,008 final) or ready did (1,050 and
            // 1,050, the late weighing refused). Never one of each.
            assertThat(captured(p.created().paymentId()))
                    .isEqualByComparingTo(orderFigure(p.orderId(), "final_payable_amount"));
        }
    }
}
