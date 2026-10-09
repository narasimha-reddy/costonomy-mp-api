package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditPaySupport.Inv;
import com.costonomy.mp.credit.CreditPaySupport.Setup;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.invoice.LogCapture;
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
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Sending the refunds a credit payment leaves behind (D-196): a skipped invoice's share, or a duplicate capture, goes
 * back to the card or UPI it came from. The refund job is the only thing that talks to Razorpay about them; each
 * refund is one Razorpay refund however often the job runs, and one that cannot be sent is never dropped.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayRefundIT extends AbstractIntegrationTest {

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

    /** Two invoices on one line, a credit note on the second before the capture: a refund of the second's amount. */
    private record Skipped(Setup setup, CreditPaySupport.Paid paid, long refundId) {
    }

    private Skipped skippedShare(String firstUnit, String secondAmount) throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", Map.of());
        // The first at a unit price with one unit, so its paise can be chosen (the mock keys failures on them).
        var one = new Inv(line, p.s.invoice(line, firstUnit, 1));
        var two = new Inv(line, p.invoiceOf(line, secondAmount));
        var setup = new Setup(buyer, List.of(line), List.of(one, two));
        var paid = p.createAndPay(buyer, p.allItems(setup));
        assertThat(p.creditNote(two, "100.00").status()).isEqualTo(201);
        var verified = p.verify(buyer, paid.attemptId(), paid.paymentId());
        assertThat(verified.status()).describedAs(verified.body().toString()).isEqualTo(200);
        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        return new Skipped(setup, paid, ((Number) refunds.get(0).get("id")).longValue());
    }

    private Map<String, Object> refundRow(long refundId) {
        return jdbc.queryForMap("select * from credit_payment_refund where id = ?", refundId);
    }

    /** Makes the last attempt at a refund old enough that the job tries it again. */
    private void letItAge(long refundId) {
        jdbc.update("update credit_payment_refund set last_tried_at = date_sub(last_tried_at, interval 1 hour) "
                + "where id = ?", refundId);
    }

    @Test
    @DisplayName("a refund is sent once: COMPLETED, with Razorpay's id, an audit row, and the key and notes derived from the refund id")
    void refundCompletes() throws Exception {
        var skipped = skippedShare("1000.00", "4000.00");
        long refundId = skipped.refundId();
        long auditsBefore = p.audits("CREDIT_PAY_REFUNDED");

        p.refunds();

        var row = refundRow(refundId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat((String) row.get("provider_refund_id")).startsWith("mock_rfnd_");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("failure_reason")).isNull();
        assertThat(provider.refundedOf(skipped.paid().paymentId())).isEqualByComparingTo("4000.00");
        assertThat(p.audits("CREDIT_PAY_REFUNDED")).isEqualTo(auditsBefore + 1);
        verify(provider).refund(eq(skipped.paid().paymentId()), argThat(a -> a.compareTo(new BigDecimal("4000.00")) == 0),
                eq("mandi-creditpay-refund-" + refundId), argThat(options -> options != null
                        && ("mandi-creditpay-refund-" + refundId).equals(options.receipt())
                        && String.valueOf(skipped.paid().attemptId()).equals(
                                options.notes().get("mandi_credit_payment_attempt_id"))
                        && String.valueOf(refundId).equals(options.notes().get("mandi_credit_payment_refund_id"))));
        var read = p.status(skipped.setup().buyer(), skipped.paid().attemptId()).data();
        assertThat(read.get("refundState").asText()).isEqualTo("COMPLETED");
        assertThat(read.get("refundAmount").decimalValue()).isEqualByComparingTo("4000.00");

        // A second run finds nothing to do.
        p.refunds();
        assertThat(provider.refundedOf(skipped.paid().paymentId())).isEqualByComparingTo("4000.00");
        assertThat(p.audits("CREDIT_PAY_REFUNDED")).isEqualTo(auditsBefore + 1);
    }

    @Test
    @DisplayName("the receipt is mandi-creditpay-refund-{id}, never mandi-refund-: the order refund matching and audit ignore it")
    void receiptIsNotAnOrderRefund() throws Exception {
        var skipped = skippedShare("1000.00", "4000.00");
        long ordersRefundAuditsBefore = p.count("select count(*) from refund");

        p.refunds();

        var entries = provider.listRefunds(skipped.paid().paymentId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).receipt()).isEqualTo("mandi-creditpay-refund-" + skipped.refundId());
        assertThat(entries.get(0).receipt()).doesNotStartWith("mandi-refund-");
        // No order refund row was made or touched for a credit payment.
        assertThat(p.count("select count(*) from refund")).isEqualTo(ordersRefundAuditsBefore);
    }

    @Test
    @DisplayName("a refund Razorpay accepts as pending stays PENDING (not counted as a failure) and completes on a later run")
    void pendingThenCompleted() throws Exception {
        // 1000.00 + 4000.23: the share to return ends in .23, which the mock accepts as pending.
        var skipped = skippedShare("1000.00", "4000.20");
        jdbc.update("update credit_payment_refund set amount = 4000.23 where id = ?", skipped.refundId());
        long refundId = skipped.refundId();

        p.refunds();

        var pending = refundRow(refundId);
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat(pending.get("provider_refund_id")).isNotNull();
        assertThat(((Number) pending.get("attempts")).intValue()).describedAs("pending is not a failure").isZero();
        assertThat(pending.get("completed_at")).isNull();
        var read = p.status(skipped.setup().buyer(), skipped.paid().attemptId()).data();
        assertThat(read.get("refundState").asText()).isEqualTo("PENDING");
        String providerRefundId = (String) pending.get("provider_refund_id");

        letItAge(refundId);
        p.refunds();

        var done = refundRow(refundId);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("provider_refund_id")).describedAs("never overwritten").isEqualTo(providerRefundId);
        assertThat(done.get("completed_at")).isNotNull();
        assertThat(provider.refundedOf(skipped.paid().paymentId())).describedAs("asked once, then only fetched")
                .isEqualByComparingTo("4000.23");
        verify(provider).fetchRefund(providerRefundId);
    }

    @Test
    @DisplayName("a refund that keeps failing is tried five times, then left PENDING with an ERROR for a person and refundState NEEDS_ATTENTION")
    void failsFiveTimes() throws Exception {
        // The mock fails every refund of a payment whose captured amount ends in .19: 6.19 + 1000.00.
        var skipped = skippedShare("6.19", "1000.00");
        long refundId = skipped.refundId();

        try (var logs = new LogCapture()) {
            for (int run = 1; run <= 5; run++) {
                p.refunds();
                var row = refundRow(refundId);
                assertThat(row.get("status")).describedAs("run " + run).isEqualTo("PENDING");
                assertThat(((Number) row.get("attempts")).intValue()).describedAs("run " + run).isEqualTo(run);
                assertThat((String) row.get("failure_reason")).describedAs("run " + run).isNotBlank();
                letItAge(refundId);
            }
            assertThat(logs.lines().stream().filter(l -> l.contains("NEEDS A PERSON")).count())
                    .describedAs("one alert, at the fifth failure").isGreaterThanOrEqualTo(1);

            // Given up: more runs do not send again.
            p.refunds();
            p.refunds();
        }
        var row = refundRow(refundId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(5);
        assertThat(provider.refundedOf(skipped.paid().paymentId())).isEqualByComparingTo("0");
        var read = p.status(skipped.setup().buyer(), skipped.paid().attemptId()).data();
        assertThat(read.get("refundState").asText()).isEqualTo("NEEDS_ATTENTION");
        assertThat(read.get("refundAmount").decimalValue()).isEqualByComparingTo("1000.00");
        // The invoices that were paid stay paid: only the refund is stuck.
        assertThat(p.attemptStatus(skipped.paid().attemptId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a refund that fails and then works is completed on the later try, and the failure is forgotten")
    void failsThenWorks() throws Exception {
        var skipped = skippedShare("1000.00", "4000.00");
        org.mockito.Mockito.doThrow(new com.costonomy.mp.payment.provider.PaymentProviderException(
                "Razorpay is down", true, "GATEWAY_ERROR")).when(provider).refund(any(), any(), any(), any());

        p.refunds();

        var failed = refundRow(skipped.refundId());
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        reset(provider);
        letItAge(skipped.refundId());

        p.refunds();

        assertThat(refundRow(skipped.refundId()).get("status")).isEqualTo("COMPLETED");
        assertThat(provider.refundedOf(skipped.paid().paymentId())).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("the job run twice at the same moment makes one Razorpay refund (the key comes from the refund id)")
    void concurrentJobs() throws Exception {
        var skipped = skippedShare("1000.00", "4000.00");
        List<Callable<Object>> calls = List.of(() -> {
            p.refunds();
            return null;
        }, () -> {
            p.refunds();
            return null;
        }, () -> {
            p.refunds();
            return null;
        });

        p.concurrently(calls);

        assertThat(provider.listRefunds(skipped.paid().paymentId())).hasSize(1);
        assertThat(provider.refundedOf(skipped.paid().paymentId())).isEqualByComparingTo("4000.00");
        assertThat(refundRow(skipped.refundId()).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a refund row cannot be made twice for the same payment and reason")
    void oneRowPerPaymentAndReason() throws Exception {
        var skipped = skippedShare("1000.00", "4000.00");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into credit_payment_refund (attempt_id, razorpay_payment_id, destination, reason, amount, status)
                values (?, ?, 'ORIGINAL', 'SKIPPED_INVOICES', 10, 'PENDING')
                """, skipped.paid().attemptId(), skipped.paid().paymentId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
