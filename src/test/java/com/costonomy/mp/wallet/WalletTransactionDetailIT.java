package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One wallet entry for the transaction-details page (D-112): the counterparty, the references we
 * really hold, the masked payee, and who may look. Rows are seeded straight into the database;
 * the reads go through the API.
 */
@AutoConfigureMockMvc
class WalletTransactionDetailIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private WalletTopUpSupport t;
    private Buyer buyer;
    private LedgerSeed seed;

    @BeforeEach
    void setUp() throws Exception {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "5000", "start");
    }

    private Reply get(Buyer who, Buyer outlet, String key) throws Exception {
        return t.call("GET", who.token(), "/api/v1/outlets/" + outlet.outletId() + "/wallet/transactions/" + key,
                null, null);
    }

    private JsonNode detail(long entryId) throws Exception {
        var reply = get(buyer, buyer, String.valueOf(entryId));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    /** Inserts with foreign keys off (rows beside the point of the test are missing), returns the new id. */
    private long insert(String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (Statement off = con.createStatement()) {
                off.execute("SET FOREIGN_KEY_CHECKS=0");
            }
            try (var insert = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < args.length; i++) {
                    insert.setObject(i + 1, args[i]);
                }
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            } finally {
                try (Statement on = con.createStatement()) {
                    on.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
        });
    }

    private long quickScan(String vpa, String name) throws Exception {
        return insert("""
                insert into quickscan_payment (outlet_id, created_by, payee_vpa, payee_name, amount, method, status,
                       idempotency_key, created_at, updated_at, version)
                values (?, ?, ?, ?, 250, 'WALLET', 'PAID', ?, now(6), now(6), 0)
                """, buyer.outletId(), t.userId(buyer.token()), vpa, name, "qs-" + UUID.randomUUID());
    }

    private void reference(String reference) {
        jdbc.update("update wallet_transaction set reference = ? where id = ?", reference, seed.lastId);
    }

    /** A supplier order with a number in a shop with a name. */
    private long order(String number, String shop) {
        long store = insert("""
                insert into supplier_store (supplier_organization_id, name, address_line1, city, state,
                       created_at, updated_at, version)
                values (1, ?, 'MG Road', 'Hyderabad', 'Telangana', now(6), now(6), 0)
                """, shop);
        return insert("""
                insert into supplier_order (procurement_id, supplier_store_id, outlet_id, order_number,
                       delivery_mode, created_at, updated_at, version)
                values (?, ?, ?, ?, 'COSTONOMY', now(6), now(6), 0)
                """, System.nanoTime(), store, buyer.outletId(), number);
    }

    private static List<String> refs(JsonNode data) {
        var out = new ArrayList<String>();
        data.get("references").forEach(r -> out.add(r.get("label").asText() + "=" + r.get("value").asText()));
        return out;
    }

    @Test
    @DisplayName("a QuickScan payment: payee, masked VPA, its payment id, and pay again with the full VPA")
    void quickScanPayment() throws Exception {
        long payment = quickScan("sriram@okhdfc", "Sri Ram Tea Stall");
        seed.debit(AT, QUICKSCAN_PAYMENT, "250", "QuickScan payment");
        reference("quickscan-" + payment);

        var d = detail(seed.lastId);

        assertThat(d.get("transactionId").asText()).isEqualTo(String.valueOf(seed.lastId));
        assertThat(d.get("key").asText()).isEqualTo("L" + seed.lastId);
        assertThat(d.get("kind").asText()).isEqualTo("QUICKSCAN_PAYMENT");
        assertThat(d.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(d.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("250");
        assertThat(d.get("balanceAfter").decimalValue()).isEqualByComparingTo("4750");
        assertThat(d.get("counterpartyName").asText()).isEqualTo("Sri Ram Tea Stall");
        assertThat(d.get("counterpartyDetail").asText()).isEqualTo("sr••••@okhdfc");
        assertThat(refs(d)).containsExactly("QuickScan payment=" + payment);
        assertThat(d.get("references").get(0).get("copyable").asBoolean()).isTrue();
        assertThat(d.at("/actions/canPayAgain").asBoolean()).isTrue();
        assertThat(d.at("/actions/payeeVpa").asText()).isEqualTo("sriram@okhdfc");
        // The full VPA appears in exactly one place: the pay-again action.
        assertThat(d.toString().split("sriram@okhdfc", -1)).hasSize(2);
    }

    @Test
    @DisplayName("a QuickScan payment whose payee VPA is no longer valid cannot be paid again and shows no full VPA")
    void invalidVpa() throws Exception {
        long payment = quickScan("not a vpa@@", "Old Shop");
        seed.debit(AT, QUICKSCAN_PAYMENT, "10", "QuickScan payment");
        reference("quickscan-" + payment);

        var d = detail(seed.lastId);

        assertThat(d.at("/actions/canPayAgain").asBoolean()).isFalse();
        assertThat(d.at("/actions/payeeVpa").isMissingNode()).isTrue();
        assertThat(d.toString()).doesNotContain("not a vpa@@");
    }

    @Test
    @DisplayName("a returned QuickScan payment: masked payee, never the full VPA, no pay again")
    void quickScanReturn() throws Exception {
        long payment = quickScan("sriram@okhdfc", "Sri Ram Tea Stall");
        jdbc.update("update quickscan_payment set provider_payout_id = 'pout_Abc123' where id = ?", payment);
        seed.credit(AT, QUICKSCAN_RETURN, "250", "QuickScan payment returned");
        reference("quickscan-return-" + payment);

        var d = detail(seed.lastId);

        assertThat(d.get("kind").asText()).isEqualTo("QUICKSCAN_RETURN");
        assertThat(d.get("counterpartyDetail").asText()).isEqualTo("sr••••@okhdfc");
        assertThat(refs(d)).containsExactly("QuickScan payment=" + payment, "Payout reference=pout_Abc123");
        assertThat(d.at("/actions/canPayAgain").asBoolean()).isFalse();
        assertThat(d.toString()).doesNotContain("sriram@okhdfc").doesNotContain("payeeVpa");
    }

    @Test
    @DisplayName("a mock payout id is not shown as a payout reference")
    void mockPayoutHasNoReference() throws Exception {
        long payment = quickScan("sriram@okhdfc", "Sri Ram");
        jdbc.update("update quickscan_payment set provider_payout_id = 'mock_payout_ab12' where id = ?", payment);
        seed.debit(AT, QUICKSCAN_PAYMENT, "10", "QuickScan payment");
        reference("quickscan-" + payment);

        assertThat(refs(detail(seed.lastId))).containsExactly("QuickScan payment=" + payment);
    }

    @Test
    @DisplayName("order payment, order refund and dispute refund name the shop and the order number")
    void orderKinds() throws Exception {
        long order = order("ORD-2026-0042", "Metro Fresh Mart");
        long payment = insert("""
                insert into payment (supplier_order_id, procurement_id, outlet_id, provider, provider_payment_id,
                       created_at, updated_at, version)
                values (?, 1, ?, 'RAZORPAY', 'pay_OrderPaid1', now(6), now(6), 0)
                """, order, buyer.outletId());
        assertThat(payment).isPositive();
        seed.add(AT, "DEBIT", ORDER_PAYMENT, "400", "Order payment", order, null);
        long paid = seed.lastId;
        seed.add(AT, "CREDIT", ORDER_REFUND, "400", "Order cancelled", order, null);
        long refunded = seed.lastId;
        seed.add(AT, "CREDIT", DISPUTE_REFUND, "30", "Refund", order, null);
        long disputed = seed.lastId;

        var p = detail(paid);
        assertThat(p.get("counterpartyName").asText()).isEqualTo("Metro Fresh Mart");
        assertThat(p.get("counterpartyDetail").asText()).isEqualTo("ORD-2026-0042");
        assertThat(refs(p)).containsExactly("Order number=ORD-2026-0042", "Razorpay payment id=pay_OrderPaid1");
        assertThat(p.at("/actions/canPayAgain").asBoolean()).isFalse();

        for (long id : new long[]{refunded, disputed}) {
            var d = detail(id);
            assertThat(d.get("counterpartyName").asText()).isEqualTo("Metro Fresh Mart");
            assertThat(d.get("counterpartyDetail").asText()).isEqualTo("ORD-2026-0042");
            assertThat(refs(d)).containsExactly("Order number=ORD-2026-0042");
        }
        assertThat(detail(refunded).get("kind").asText()).isEqualTo("ORDER_REFUND");
        assertThat(detail(disputed).get("kind").asText()).isEqualTo("DISPUTE_REFUND");
    }

    @Test
    @DisplayName("a top-up names the instrument in the history's masked form and its Razorpay payment id")
    void topUp() throws Exception {
        long topUp = seed.returnedTopUp(t.userId(buyer.token()), AT, "700", "card", "1111");
        jdbc.update("update wallet_top_up set status = 'CREDITED', razorpay_payment_id = 'pay_TopUp77' where id = ?",
                topUp);
        seed.credit(AT, TOP_UP, "700", "Wallet top-up");
        reference("topup-" + topUp);

        var d = detail(seed.lastId);

        assertThat(d.get("instrument").asText()).isEqualTo("Card •1111");
        assertThat(d.get("counterpartyName").asText()).isEqualTo("Card •1111");
        assertThat(d.get("counterpartyDetail").isNull()).isTrue();
        assertThat(refs(d)).containsExactly("Razorpay payment id=pay_TopUp77");
        assertThat(d.toString()).doesNotContain("order_");
    }

    @Test
    @DisplayName("a withdrawal and its reversal give the refund ids; no payout reference or UTR is held or invented")
    void withdrawalAndReversal() throws Exception {
        long order = order("ORD-9", "Any Shop");
        long payment = insert("""
                insert into payment (supplier_order_id, procurement_id, outlet_id, provider, provider_payment_id,
                       created_at, updated_at, version)
                values (?, 1, ?, 'RAZORPAY', 'pay_Card55', now(6), now(6), 0)
                """, order, buyer.outletId());
        long refund = seed.refund("COMPLETED");
        jdbc.update("update refund set payment_id = ?, provider_refund_id = 'rfnd_W1' where id = ?", payment, refund);
        seed.add(AT, "DEBIT", WITHDRAWAL, "300", "Withdrawal to the original payment method", null, refund);
        long withdrawal = seed.lastId;
        seed.add(AT, "CREDIT", WITHDRAWAL_REVERSAL, "300", "Withdrawal returned to your wallet", null, refund);
        long reversal = seed.lastId;

        var w = detail(withdrawal);
        assertThat(w.get("counterpartyName").asText()).isEqualTo("Your card or bank");
        assertThat(refs(w)).containsExactly("Refund id=" + refund, "Razorpay refund id=rfnd_W1",
                "Razorpay payment id=pay_Card55");
        assertThat(w.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(w.toString()).doesNotContainIgnoringCase("utr").doesNotContain("Payout");

        var r = detail(reversal);
        assertThat(r.get("kind").asText()).isEqualTo("WITHDRAWAL_REVERSAL");
        assertThat(refs(r)).contains("Refund id=" + refund);
    }

    @Test
    @DisplayName("a refund to the wallet gives the order number and the refund id")
    void refundKind() throws Exception {
        long order = order("ORD-77", "Green Grocers");
        long refund = seed.refund("COMPLETED");
        seed.add(AT, "CREDIT", REFUND, "55", "Refund", order, refund);

        var d = detail(seed.lastId);

        assertThat(d.get("counterpartyName").asText()).isEqualTo("Refund");
        assertThat(d.get("counterpartyDetail").asText()).isEqualTo("ORD-77");
        assertThat(refs(d)).containsExactly("Order number=ORD-77", "Refund id=" + refund);
    }

    @Test
    @DisplayName("the list's own key, another outlet's entry, a missing entry and a bad id are 404; no token is 401")
    void who() throws Exception {
        seed.debit(AT, ORDER_PAYMENT, "1", "mine");
        long mine = seed.lastId;
        var stranger = t.newBuyer();
        var strangerSeed = new LedgerSeed(t, jdbc, stranger);
        strangerSeed.credit(AT, TOP_UP, "9", "stranger money");
        long theirs = strangerSeed.lastId;

        assertThat(get(buyer, buyer, "L" + mine).status()).isEqualTo(200);
        // Their entry through my outlet, my entry through theirs, and their outlet with my token.
        var cross = get(buyer, buyer, String.valueOf(theirs));
        assertThat(cross.status()).isEqualTo(404);
        assertThat(cross.body().toString()).doesNotContain("stranger money");
        assertThat(get(stranger, stranger, String.valueOf(mine)).status()).isEqualTo(404);
        assertThat(get(stranger, buyer, String.valueOf(mine)).status()).isEqualTo(404);
        assertThat(get(buyer, buyer, "99999999").status()).isEqualTo(404);
        assertThat(get(buyer, buyer, "abc").status()).isEqualTo(404);
        assertThat(get(buyer, buyer, "T1").status()).isEqualTo(404);
        var anonymous = t.call("GET", null, "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions/" + mine,
                null, null);
        assertThat(anonymous.status()).isEqualTo(401);
    }
}
