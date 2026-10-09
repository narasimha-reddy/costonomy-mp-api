package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditPaySupport.Inv;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * A fee on online payments, set above zero ({@code fee-percent=2}), the "decide later" switch (D-196, open question 1):
 * a surcharge added on top of what Razorpay collects, snapshotted on the row, never paid out to a supplier, refunded in
 * full only when nothing was paid, and kept when only some invoices were skipped. The wallet stays free.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true", "costonomy.mp.credit.online-pay.fee-percent=2"})
class CreditPayFeeIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    /** One line with invoices of 100.25 and 1000.00: 1100.25 owed, and 2% of it is 22.005, which rounds up to 22.01. */
    private Setup rounding() throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", java.util.Map.of());
        var odd = new Inv(line, p.s.invoice(line, "100.25", 1));
        var round = new Inv(line, p.invoiceOf(line, "1000.00"));
        return new Setup(buyer, List.of(line), List.of(odd, round));
    }

    @Test
    @DisplayName("the picker and the quote show the fee: 2% of the invoices, rounded half up, on UPI and card only")
    void feeIsShown() throws Exception {
        var setup = rounding();
        p.s.topUp(setup.buyer(), "5000.00");

        var payable = p.payable(setup.buyer());
        var quote = p.quote(setup.buyer(), p.allItems(setup));

        assertThat(payable.data().get("feePercent").decimalValue()).isEqualByComparingTo("2");
        assertThat(quote.data().get("amount").decimalValue()).isEqualByComparingTo("1100.25");
        for (int i = 0; i < 2; i++) {
            assertThat(quote.data().at("/methods/" + i + "/feeAmount").decimalValue()).isEqualByComparingTo("22.01");
            assertThat(quote.data().at("/methods/" + i + "/totalCharged").decimalValue()).isEqualByComparingTo("1122.26");
        }
        assertThat(quote.data().at("/methods/2/method").asText()).isEqualTo("WALLET");
        assertThat(quote.data().at("/methods/2/feeAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(quote.data().at("/methods/2/totalCharged").decimalValue()).isEqualByComparingTo("1100.25");
    }

    @Test
    @DisplayName("create snapshots the fee on the row and asks Razorpay to collect the total with the fee")
    void createChargesFee() throws Exception {
        var setup = rounding();

        var reply = p.create(setup.buyer(), p.allItems(setup), "CARD");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(reply.data().get("amount").decimalValue()).isEqualByComparingTo("1100.25");
        assertThat(reply.data().get("feeAmount").decimalValue()).isEqualByComparingTo("22.01");
        assertThat(reply.data().get("totalCharged").decimalValue()).isEqualByComparingTo("1122.26");
        var row = p.attempt(reply.data().get("attemptId").asLong());
        assertThat((BigDecimal) row.get("fee_percent")).isEqualByComparingTo("2");
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("22.01");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1100.25");
        assertThat((BigDecimal) row.get("total_charged")).isEqualByComparingTo("1122.26");
        verify(provider).createAuthorization(org.mockito.ArgumentMatchers.argThat(request ->
                request.amount().compareTo(new BigDecimal("1122.26")) == 0 && request.autoCapture()));
    }

    @Test
    @DisplayName("paid: the invoices take their own amounts, the payout excludes the fee, and the capture of the total with fee is accepted")
    void payoutExcludesFee() throws Exception {
        var setup = rounding();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));

        var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("status").asText()).isEqualTo("PAID");
        assertThat(reply.data().get("totalCharged").decimalValue()).isEqualByComparingTo("1122.26");
        assertThat(p.jdbc.queryForObject("select amount from credit_repayment where credit_payment_attempt_id = ?",
                BigDecimal.class, paid.attemptId())).isEqualByComparingTo("1100.25");
        assertThat(p.jdbc.queryForObject("select o.amount from credit_repayment_payout o join credit_repayment r "
                + "on r.id = o.credit_repayment_id where r.credit_payment_attempt_id = ?", BigDecimal.class,
                paid.attemptId())).describedAs("never the fee").isEqualByComparingTo("1100.25");
        assertThat(p.jdbc.queryForObject("select sum(pay.amount) from credit_payment pay join credit_repayment r "
                + "on r.id = pay.credit_repayment_id where r.credit_payment_attempt_id = ?", BigDecimal.class,
                paid.attemptId())).isEqualByComparingTo("1100.25");
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).isEqualTo("PAID");
        }
        assertThat(p.refundRows(paid.attemptId())).describedAs("the fee is kept when something was paid").isEmpty();
        p.n.assertConsistent(setup.line(0));
    }

    @Test
    @DisplayName("nothing paid (every invoice changed): the whole total_charged goes back, fee included")
    void fullRefundIncludesFee() throws Exception {
        var setup = rounding();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.supplierReceipt(setup.inv(0), "100.25").status()).isEqualTo(200);
        assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200);

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("REFUNDED");
        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo("1122.26");
        p.refunds();
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("1122.26");
    }

    @Test
    @DisplayName("only some invoices skipped: just their shares go back and the fee is kept")
    void partialSkipKeepsFee() throws Exception {
        var setup = rounding();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200);

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo("1000.00");
        assertThat(p.jdbc.queryForObject("select amount from credit_repayment where credit_payment_attempt_id = ?",
                BigDecimal.class, paid.attemptId())).isEqualByComparingTo("100.25");
        p.refunds();
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("the wallet method carries no fee: the wallet is debited the invoices' total only")
    void walletIsFree() throws Exception {
        var setup = rounding();
        p.s.topUp(setup.buyer(), "2000.00");

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(reply.data().get("feeAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(reply.data().get("totalCharged").decimalValue()).isEqualByComparingTo("1100.25");
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("899.75");
        assertThat((BigDecimal) p.attempt(reply.data().get("attemptId").asLong()).get("fee_amount"))
                .isEqualByComparingTo("0");
    }
}
