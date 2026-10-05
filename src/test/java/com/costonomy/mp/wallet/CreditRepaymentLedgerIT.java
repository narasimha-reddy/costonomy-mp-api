package com.costonomy.mp.wallet;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.invoice.service.BillTracking;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A credit repayment from the wallet is its own ledger kind (D-122): the domain method that writes it,
 * and everything that reads the ledger by kind (history, statements, the details page, the bill rules).
 * There is no endpoint yet; the method is called inside a transaction after locking the wallet, as the
 * pay-from-wallet endpoint will.
 */
@AutoConfigureMockMvc
class CreditRepaymentLedgerIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WalletService wallet;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private BillTracking tracking;

    private WalletTopUpSupport t;
    private Buyer buyer;
    private LedgerSeed seed;
    private long store;
    private long agreement;

    @BeforeEach
    void setUp() throws Exception {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "1000.00", "start").sync();
        store = insert("""
                insert into supplier_store (supplier_organization_id, name, address_line1, city, state,
                       created_at, updated_at, version)
                values (1, 'Anand Wholesale', 'MG Road', 'Hyderabad', 'Telangana', now(6), now(6), 0)
                """);
        agreement = insert("""
                insert into credit_agreement (outlet_id, restaurant_id, supplier_store_id, supplier_organization_id,
                       status, created_at, updated_at, version)
                values (?, 1, ?, 1, 'ACTIVE', now(6), now(6), 0)
                """, buyer.outletId(), store);
    }

    @AfterEach
    void tearDown() {
        tracking.setStartForTests(null);
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

    private long repayment(String amount) {
        return insert("""
                insert into credit_repayment (credit_agreement_id, outlet_id, supplier_store_id, amount, source,
                       idempotency_key, created_at, updated_at, version)
                values (?, ?, ?, ?, 'WALLET', ?, now(6), now(6), 0)
                """, agreement, buyer.outletId(), store, new BigDecimal(amount), "crl-" + UUID.randomUUID());
    }

    /** An invoice of the agreement, and the credit_payment row that this repayment settled it with. */
    private void settles(long repaymentId, String invoiceNumber, String amount) {
        long order = seed.order("ORD-" + invoiceNumber);
        long invoice = insert("""
                insert into credit_invoice (credit_agreement_id, supplier_order_id, outlet_id, supplier_store_id,
                       invoice_number, status, amount, paid_amount, issued_at, due_date, overdue_after,
                       created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 'PAID', ?, ?, now(6), curdate(), curdate(), now(6), now(6), 0)
                """, agreement, order, buyer.outletId(), store, invoiceNumber, new BigDecimal(amount),
                new BigDecimal(amount));
        insert("""
                insert into credit_payment (credit_invoice_id, credit_agreement_id, amount, method, paid_at,
                       idempotency_key, source, credit_repayment_id, created_at)
                values (?, ?, ?, 'WALLET', now(6), ?, 'WALLET', ?, now(6))
                """, invoice, agreement, new BigDecimal(amount), "crl-" + UUID.randomUUID(), repaymentId);
    }

    /** What the pay-from-wallet endpoint will do: lock, then debit, in one transaction. */
    private void debit(long repaymentId, String amount) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            wallet.lock(buyer.outletId());
            wallet.debitCreditRepayment(buyer.outletId(), repaymentId, new BigDecimal(amount));
        });
    }

    private List<java.util.Map<String, Object>> rows() {
        return jdbc.queryForList("select * from wallet_transaction where wallet_id = ? and kind = 'CREDIT_REPAYMENT'",
                seed.walletId);
    }

    private BigDecimal balance() {
        return t.balance(buyer);
    }

    // ── the domain method ────────────────────────────────────────────────

    @Test
    @DisplayName("debitCreditRepayment writes one DEBIT row of its own kind and lowers the balance by the amount")
    void debits() {
        long id = repayment("250.50");

        debit(id, "250.50");

        var rows = rows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("direction")).isEqualTo("DEBIT");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("250.50");
        assertThat(row.get("reference")).isEqualTo("credit-repayment-" + id);
        assertThat(row.get("supplier_order_id")).isNull();
        assertThat(row.get("refund_id")).isNull();
        assertThat((String) row.get("reason")).contains("Credit repayment");
        assertThat((BigDecimal) row.get("balance_after")).isEqualByComparingTo("749.50");
        assertThat(balance()).isEqualByComparingTo("749.50");
        assertThat(t.ledgerSum(buyer)).isEqualByComparingTo("749.50");
    }

    @Test
    @DisplayName("more than the balance is refused with the wallet's usual error, and leaves no row and no change")
    void insufficient() {
        long id = repayment("1000.01");

        assertThatThrownBy(() -> debit(id, "1000.01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(rows()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
        // The whole balance is spendable: the boundary is inclusive.
        debit(id, "1000.00");
        assertThat(balance()).isEqualByComparingTo("0");
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("the same repayment twice debits once: the second call is refused and rolls back whole")
    void twiceDebitsOnce() {
        long id = repayment("100.00");
        debit(id, "100.00");

        assertThatThrownBy(() -> debit(id, "100.00")).isInstanceOf(BusinessException.class);

        assertThat(rows()).hasSize(1);
        assertThat(balance()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("a wallet on hold cannot repay")
    void walletOnHold() {
        long id = repayment("10.00");
        jdbc.update("update wallet set status = 'SUSPENDED' where id = ?", seed.walletId);

        assertThatThrownBy(() -> debit(id, "10.00")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(rows()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    // ── the history ──────────────────────────────────────────────────────

    private JsonNode history(String query) throws Exception {
        var reply = t.call("GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions"
                + (query.isEmpty() ? "" : "?" + query), null, null);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    @Test
    @DisplayName("history: a repayment is a DEBIT with no bill chip, counts as spent, and the kind filter finds it")
    void historyRows() throws Exception {
        tracking.setStartForTests(LocalDate.of(2026, 8, 1)); // bill tracking on, so a payment would be PENDING
        long id = repayment("200.00");
        debit(id, "200.00");
        seed.debit("2026-09-02 10:00:00.000000", ORDER_PAYMENT, "50.00", "other spend");
        long entry = jdbc.queryForObject("select id from wallet_transaction where reference = ?", Long.class,
                "credit-repayment-" + id);
        // Same month as the seeded rows, so the month's totals can be read from one place.
        jdbc.update("update wallet_transaction set created_at = ? where id = ?", "2026-09-03 10:00:00.000000", entry);

        var data = history("months=2026-09");
        JsonNode item = null;
        for (var i : data.get("items")) {
            if (i.get("key").asText().equals("L" + entry)) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("kind").asText()).isEqualTo("CREDIT_REPAYMENT");
        assertThat(item.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(item.get("bill").isNull()).isTrue();
        assertThat(item.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(item.get("supplierOrderId").isNull()).isTrue();
        assertThat(data.get("monthTotals").get(0).get("spent").decimalValue()).isEqualByComparingTo("250.00");
        // The bill counts: the order payment is waiting for a bill, the repayment is not.
        assertThat(data.get("monthTotals").get(0).get("billsPending").asInt()).isEqualTo(1);
        assertThat(data.at("/billSummary/pending").asInt()).isEqualTo(1);

        var only = history("kinds=CREDIT_REPAYMENT");
        List<String> keys = new ArrayList<>();
        only.get("items").forEach(i -> keys.add(i.get("key").asText()));
        assertThat(keys).containsExactly("L" + entry);
        assertThat(history("kinds=credit_repayment,ORDER_PAYMENT").get("items")).hasSize(2);
        // A bill filter does not find it either.
        assertThat(history("bills=PENDING&kinds=CREDIT_REPAYMENT").get("items")).isEmpty();
    }

    // ── the statement ────────────────────────────────────────────────────

    @Test
    @DisplayName("statement: the CSV row says 'Credit repayment', as a debit, and the file reconciles")
    void statement() throws Exception {
        long id = repayment("125.00");
        debit(id, "125.00");
        // Statements are by day in India; put the row on a known recent day.
        var day = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        jdbc.update("update wallet_transaction set created_at = ? where wallet_id = ? and kind = 'CREDIT_REPAYMENT'",
                day + " 06:30:00.000000", seed.walletId);
        jdbc.update("update wallet_transaction set created_at = ? where wallet_id = ? and kind = 'TOP_UP'",
                day.minusDays(2) + " 06:30:00.000000", seed.walletId);

        var response = csv("range=LAST_30&format=CSV");
        var body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).describedAs(body).isEqualTo(200);
        assertThat(body).contains(",Credit repayment,,Debit,125.00,875.00,");
        assertThat(body).containsPattern("Total spent \\(INR\\),125\\.00,");
        assertThat(csv("range=LAST_30&format=PDF").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse csv(String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/outlets/" + buyer.outletId() + "/wallet/statement?" + query)
                .header("Authorization", "Bearer " + buyer.token())).andReturn().getResponse();
    }

    // ── the details page ─────────────────────────────────────────────────

    @Test
    @DisplayName("details: the supplier is the counterparty, the invoices it settled and the credit line are listed, and no bill is asked for")
    void detail() throws Exception {
        tracking.setStartForTests(LocalDate.of(2026, 8, 1));
        long id = repayment("300.00");
        settles(id, "INV-001", "100.00");
        settles(id, "INV-002", "200.00");
        debit(id, "300.00");
        long entry = jdbc.queryForObject("select id from wallet_transaction where reference = ?", Long.class,
                "credit-repayment-" + id);

        var reply = t.call("GET", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions/" + entry, null, null);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();

        assertThat(d.get("kind").asText()).isEqualTo("CREDIT_REPAYMENT");
        assertThat(d.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(d.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(d.get("balanceAfter").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(d.get("counterpartyName").asText()).isEqualTo("Anand Wholesale");
        assertThat(d.get("counterpartyDetail").asText()).isEqualTo("INV-001, INV-002");
        var refs = new ArrayList<String>();
        d.get("references").forEach(r -> refs.add(r.get("label").asText() + "=" + r.get("value").asText()));
        assertThat(refs).containsExactly("Credit invoice=INV-001", "Credit invoice=INV-002",
                "Credit line=" + agreement, "Credit repayment=" + id);
        // A repayment never asks for a bill, and cannot be paid again as a shop payment.
        assertThat(d.get("billStatus").isNull()).isTrue();
        assertThat(d.at("/actions/canAddBill").asBoolean()).isFalse();
        assertThat(d.at("/actions/canWaiveBill").asBoolean()).isFalse();
        assertThat(d.at("/actions/canUndoWaiver").asBoolean()).isFalse();
        assertThat(d.at("/actions/canPayAgain").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("details: another outlet's repayment is never shown, even if a ledger row points at it")
    void detailIsScopedToTheOutlet() throws Exception {
        var other = t.newBuyer();
        long id = insert("""
                insert into credit_repayment (credit_agreement_id, outlet_id, supplier_store_id, amount, source,
                       idempotency_key, created_at, updated_at, version)
                values (?, ?, ?, 10, 'WALLET', ?, now(6), now(6), 0)
                """, agreement, other.outletId(), store, "crl-" + UUID.randomUUID());
        seed.debit("2026-09-02 10:00:00.000000", CREDIT_REPAYMENT, "10.00", "Credit repayment");
        jdbc.update("update wallet_transaction set reference = ? where id = ?", "credit-repayment-" + id, seed.lastId);

        var reply = t.call("GET", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions/" + seed.lastId, null, null);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("counterpartyName").isNull()).isTrue();
        assertThat(reply.data().toString()).doesNotContain("Anand Wholesale");
    }
}
