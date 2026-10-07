package com.costonomy.mp.credit;

import com.costonomy.mp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-151: the repayment tables and the CREDIT_REPAY permission, before any code uses them.
 *
 * <p>The constraint tests insert rows with foreign key checks switched off on one connection, because
 * what is under test is the CHECK and UNIQUE rules, not the parents. Every row is removed afterwards.
 */
class CreditRepaymentSchemaIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from credit_payment where idempotency_key like 'crs-%'");
        jdbc.update("delete from credit_repayment where idempotency_key like 'crs-%'");
    }

    /** Inserts on one connection with foreign keys off: the parents are not what is under test. */
    private void insertRepayment(String amount, String key, Long walletTxId) {
        jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try (var ps = c.prepareStatement("insert into credit_repayment (credit_agreement_id, outlet_id, "
                        + "supplier_store_id, amount, source, wallet_transaction_id, idempotency_key) "
                        + "values (1, 1, 1, ?, 'WALLET', ?, ?)")) {
                    ps.setBigDecimal(1, new java.math.BigDecimal(amount));
                    if (walletTxId == null) {
                        ps.setNull(2, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(2, walletTxId);
                    }
                    ps.setString(3, key);
                    ps.executeUpdate();
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    private void insertPayment(Long repaymentId, long invoiceId, String key) {
        jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try (var ps = c.prepareStatement("insert into credit_payment (credit_invoice_id, "
                        + "credit_agreement_id, amount, method, paid_at, idempotency_key, source, credit_repayment_id) "
                        + "values (?, 1, 10, 'WALLET', NOW(6), ?, 'WALLET', ?)")) {
                    ps.setLong(1, invoiceId);
                    ps.setString(2, key);
                    if (repaymentId == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, repaymentId);
                    }
                    ps.executeUpdate();
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    private String key() {
        return "crs-" + UUID.randomUUID();
    }

    // ── permission ───────────────────────────────────────────────────────

    @Test
    @DisplayName("CREDIT_REPAY is a restaurant permission held by exactly the four roles that hold QUICKSCAN_PAY")
    void creditRepayGrants() {
        assertThat(jdbc.queryForObject("select scope from permission where code = 'CREDIT_REPAY'", String.class))
                .isEqualTo("RESTAURANT");
        List<String> holders = jdbc.queryForList("select r.code from role r "
                + "join role_permission rp on rp.role_id = r.id join permission p on p.id = rp.permission_id "
                + "where p.code = 'CREDIT_REPAY' order by r.code", String.class);
        assertThat(holders).containsExactly("REST_ADMIN", "REST_FINANCE_STAFF", "REST_OWNER",
                "REST_PURCHASE_MANAGER");
        // The rule behind the list: no supplier or internal role can repay on a restaurant's behalf.
        assertThat(holders).noneMatch(c -> c.startsWith("SUP_") || c.startsWith("OPS_") || c.startsWith("ADMIN"));
    }

    @Test
    @DisplayName("CREDIT_REPAY is granted to the same roles as QUICKSCAN_PAY")
    void sameRolesAsQuickScan() {
        String q = "select r.code from role r join role_permission rp on rp.role_id = r.id "
                + "join permission p on p.id = rp.permission_id where p.code = ? order by r.code";
        assertThat(jdbc.queryForList(q, String.class, "CREDIT_REPAY"))
                .isEqualTo(jdbc.queryForList(q, String.class, "QUICKSCAN_PAY"));
    }

    // ── credit_repayment ─────────────────────────────────────────────────

    @Test
    @DisplayName("a repayment of zero or less is refused by the database")
    void amountMustBePositive() {
        assertThatThrownBy(() -> insertRepayment("0", key(), null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_repayment_amount");
        assertThatThrownBy(() -> insertRepayment("-5", key(), null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_repayment_amount");
        insertRepayment("0.0001", key(), null);
    }

    @Test
    @DisplayName("an idempotency key can be used once")
    void idempotencyKeyIsUnique() {
        String k = key();
        insertRepayment("10", k, null);
        assertThatThrownBy(() -> insertRepayment("10", k, null)).isInstanceOf(DataAccessException.class).hasMessageContaining("Duplicate entry");
    }

    @Test
    @DisplayName("one wallet transaction can back only one repayment, but repayments without one are unlimited")
    void walletTransactionIsUnique() {
        insertRepayment("10", key(), 987654321L);
        assertThatThrownBy(() -> insertRepayment("10", key(), 987654321L))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Duplicate entry");
        insertRepayment("10", key(), null);
        insertRepayment("10", key(), null);
    }

    // ── credit_payment ───────────────────────────────────────────────────

    @Test
    @DisplayName("a repayment pays an invoice once, while payments with no repayment are unlimited")
    void repaymentInvoicePairIsUnique() {
        insertRepayment("20", key(), null);
        long repaymentId = jdbc.queryForObject(
                "select max(id) from credit_repayment where idempotency_key like 'crs-%'", Long.class);
        insertPayment(repaymentId, 424242L, key());
        assertThatThrownBy(() -> insertPayment(repaymentId, 424242L, key()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Duplicate entry");
        insertPayment(repaymentId, 424243L, key());
        insertPayment(null, 424242L, key());
        insertPayment(null, 424242L, key());
    }
}
