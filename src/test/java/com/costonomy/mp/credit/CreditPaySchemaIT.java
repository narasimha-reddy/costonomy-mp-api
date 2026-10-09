package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditPaySupport.Setup;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V88 itself guarantees, straight against the tables (D-196): the unique keys that make a capture apply once and
 * a refund be made once, and the checks on amounts and values. These are the last line behind the attempt row lock.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPaySchemaIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;
    private Setup setup;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
        setup = p.single("1000.00");
        userId = p.e.userId(setup.buyer().token());
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    /** An attempt row with the given overrides; everything else valid and unique. */
    private long attempt(String column, Object value) {
        String key = unique();
        String sql = "insert into credit_payment_attempt (outlet_id, created_by, method, channel, status, invoice_count, "
                + "amount, fee_percent, fee_amount, total_charged, currency, idempotency_key, open_selection_key, "
                + "razorpay_order_id, razorpay_payment_id) values (?, ?, 'UPI', 'ONLINE', 'CREATED', 1, 100, 0, 0, 100, "
                + "'INR', ?, ?, ?, ?)";
        jdbc.update(sql, setup.buyer().outletId(), userId, "k-" + key, "s-" + key, "o-" + key, "p-" + key);
        long id = jdbc.queryForObject("select id from credit_payment_attempt where idempotency_key = ?", Long.class,
                "k-" + key);
        if (column != null) {
            jdbc.update("update credit_payment_attempt set " + column + " = ? where id = ?", value, id);
        }
        return id;
    }

    private long attempt() {
        return attempt(null, null);
    }

    private long refund(long attemptId, String paymentId, String reason) {
        jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount) "
                + "values (?, ?, ?, 10)", attemptId, paymentId, reason);
        return jdbc.queryForObject("select max(id) from credit_payment_refund", Long.class);
    }

    private long repayment(Long attemptId, long agreementId) {
        String key = unique();
        jdbc.update("insert into credit_repayment (credit_agreement_id, outlet_id, supplier_store_id, amount, source, "
                + "idempotency_key, credit_payment_attempt_id) values (?, ?, ?, 10, 'ONLINE', ?, ?)",
                agreementId, setup.buyer().outletId(), setup.line(0).seller().storeId(), "r-" + key, attemptId);
        return jdbc.queryForObject("select id from credit_repayment where idempotency_key = ?", Long.class, "r-" + key);
    }

    private void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        // A check violation is not always mapped to DataIntegrityViolationException, so any data access error counts.
        assertThatThrownBy(call).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the tables and the repayment's attempt column exist")
    void objectsExist() {
        for (String table : new String[]{"credit_payment_attempt", "credit_payment_attempt_invoice",
                "credit_payment_refund"}) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                    + "where table_schema = database() and table_name = ?", Long.class, table)).describedAs(table)
                    .isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() "
                + "and table_name = 'credit_repayment' and column_name = 'credit_payment_attempt_id'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("one Razorpay payment applies to one attempt: a second attempt with the same payment id is refused")
    void uniquePaymentId() {
        long first = attempt("razorpay_payment_id", "pay_same");
        assertThat(first).isPositive();

        refused(() -> attempt("razorpay_payment_id", "pay_same"));
    }

    @Test
    @DisplayName("one order, one attempt; one idempotency key, one attempt; one open selection, one attempt")
    void uniqueOrderKeyAndSelection() {
        attempt("razorpay_order_id", "order_same");
        refused(() -> attempt("razorpay_order_id", "order_same"));
        attempt("idempotency_key", "key-same");
        refused(() -> attempt("idempotency_key", "key-same"));
        attempt("open_selection_key", "selection-same");
        refused(() -> attempt("open_selection_key", "selection-same"));
    }

    @Test
    @DisplayName("many attempts may have no payment id, no order, and no open selection (NULLs are not duplicates)")
    void nullsAreNotDuplicates() {
        for (int i = 0; i < 3; i++) {
            long id = attempt();
            jdbc.update("update credit_payment_attempt set razorpay_payment_id = null, razorpay_order_id = null, "
                    + "open_selection_key = null where id = ?", id);
        }
        assertThat(jdbc.queryForObject("select count(*) from credit_payment_attempt where outlet_id = ? "
                + "and razorpay_payment_id is null", Long.class, setup.buyer().outletId())).isEqualTo(3L);
    }

    @Test
    @DisplayName("total_charged must equal amount + fee_amount; amount is positive, fee not negative")
    void attemptChecks() {
        refused(() -> attempt("total_charged", 101));
        refused(() -> attempt("amount", 0));
        refused(() -> attempt("fee_amount", -1));
        // A fee with its total is fine.
        long ok = attempt();
        jdbc.update("update credit_payment_attempt set fee_percent = 2, fee_amount = 2, total_charged = 102 where id = ?",
                ok);
        refused(() -> jdbc.update("update credit_payment_attempt set fee_amount = 3 where id = ?", ok));
    }

    @Test
    @DisplayName("method, channel and status take only their values")
    void attemptValueChecks() {
        refused(() -> attempt("method", "NETBANKING"));
        refused(() -> attempt("channel", "CARD"));
        refused(() -> attempt("status", "DONE"));
        for (String status : new String[]{"CREATED", "PAID", "FAILED", "EXPIRED", "REFUNDED"}) {
            attempt("status", status);
        }
        attempt("method", "WALLET");
        attempt("channel", "WALLET");
    }

    @Test
    @DisplayName("a refund is made once for a payment and reason, but a payment can have a refund of each reason")
    void refundUniqueness() {
        long a = attempt();
        refund(a, "pay_refund", "SKIPPED_INVOICES");
        refused(() -> refund(a, "pay_refund", "SKIPPED_INVOICES"));
        refund(a, "pay_refund", "DUPLICATE_CAPTURE");
        refused(() -> jdbc.update("update credit_payment_refund set provider_refund_id = 'rfnd_1' "
                + "where razorpay_payment_id = 'pay_refund'"));
    }

    @Test
    @DisplayName("a Razorpay refund id belongs to one refund row")
    void providerRefundIdUnique() {
        long a = attempt();
        long one = refund(a, "pay_one", "SKIPPED_INVOICES");
        long two = refund(a, "pay_two", "SKIPPED_INVOICES");
        jdbc.update("update credit_payment_refund set provider_refund_id = 'rfnd_same' where id = ?", one);

        refused(() -> jdbc.update("update credit_payment_refund set provider_refund_id = 'rfnd_same' where id = ?", two));
    }

    @Test
    @DisplayName("a refund is of a positive amount, with a known reason, status and destination, and belongs to an attempt")
    void refundChecks() {
        long a = attempt();
        refused(() -> jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount) "
                + "values (?, 'pay_zero', 'SKIPPED_INVOICES', 0)", a));
        refused(() -> jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount) "
                + "values (?, 'pay_reason', 'BECAUSE', 10)", a));
        refused(() -> jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount, "
                + "status) values (?, 'pay_status', 'SKIPPED_INVOICES', 10, 'SENT')", a));
        refused(() -> jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount, "
                + "destination) values (?, 'pay_dest', 'SKIPPED_INVOICES', 10, 'BANK')", a));
        refused(() -> jdbc.update("insert into credit_payment_refund (attempt_id, razorpay_payment_id, reason, amount) "
                + "values (999999999, 'pay_orphan', 'SKIPPED_INVOICES', 10)"));
        assertThat(jdbc.queryForObject("select count(*) from credit_payment_refund where attempt_id = ?", Long.class, a))
                .isZero();
    }

    @Test
    @DisplayName("an invoice appears once in an attempt, with a positive expected amount and a known outcome")
    void attemptInvoiceChecks() {
        long a = attempt();
        var inv = setup.inv(0);
        String insert = "insert into credit_payment_attempt_invoice (attempt_id, credit_invoice_id, credit_agreement_id, "
                + "supplier_store_id, expected_amount, outcome) values (?, ?, ?, ?, ?, ?)";
        jdbc.update(insert, a, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), 1000, "PENDING");

        refused(() -> jdbc.update(insert, a, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), 1000, "PENDING"));
        long b = attempt();
        refused(() -> jdbc.update(insert, b, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), 0, "PENDING"));
        refused(() -> jdbc.update(insert, b, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), 10, "MAYBE"));
        refused(() -> jdbc.update(insert, b, 999999999L, inv.line().agreementId(), inv.line().seller().storeId(), 10, "PENDING"));
        // The same invoice may be in another attempt (overlapping selections are allowed).
        jdbc.update(insert, b, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), 1000, "PENDING");
    }

    @Test
    @DisplayName("one repayment per attempt and agreement; repayments without an attempt (wallet, supplier-recorded) are not limited")
    void repaymentPerAttemptAndAgreement() {
        long a = attempt();
        long agreement = setup.line(0).agreementId();
        repayment(a, agreement);

        refused(() -> repayment(a, agreement));
        // A different attempt may repay the same agreement.
        repayment(attempt(), agreement);
        // No attempt: many allowed.
        repayment(null, agreement);
        repayment(null, agreement);
        refused(() -> repayment(999999999L, agreement));
    }
}
