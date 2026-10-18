package com.costonomy.mp.credit;

import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * A captured online payment applied through the restaurant's own confirm call (source CONFIRM), end to end: invoices
 * paid, one repayment and one payout per supplier with the commission snapshot (D-156), credit payments, ledger,
 * exposure, notifications, suspension lifts (D-160), and the refusals that must leave everything alone.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayApplyIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @Autowired private CreditJobs creditJobs;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    @Test
    @DisplayName("UPI over 3 invoices at 2 suppliers: all PAID, one repayment and one payout per supplier, credit payments, ledger, exposure")
    void upiAcrossTwoSuppliers() throws Exception {
        var setup = p.standard();
        p.setStoreRate(setup.line(0), "2.5");
        p.setStoreRate(setup.line(1), "4");
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));

        var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.get("status").asText()).isEqualTo("PAID");
        assertThat(d.get("paidWith").asText()).isEqualTo("UPI");
        assertThat(d.get("razorpayPaymentId").asText()).isEqualTo(paid.paymentId());
        assertThat(d.get("paidAt").isNull()).isFalse();
        assertThat(d.get("refundState").asText()).isEqualTo("NONE");
        assertThat(d.get("keyId").isNull()).isTrue();
        assertThat(d.at("/invoices")).hasSize(3);
        for (var inv : d.at("/invoices")) {
            assertThat(inv.get("outcome").asText()).isEqualTo("PAID");
            assertThat(inv.get("skipReason").isNull()).isTrue();
        }
        assertThat(d.at("/suppliers")).hasSize(2);
        assertThat(d.at("/suppliers/0/paidAmount").decimalValue()).isEqualByComparingTo("10500.00");
        assertThat(d.at("/suppliers/0/invoiceCount").asInt()).isEqualTo(2);
        assertThat(d.at("/suppliers/0/agreement/due").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/suppliers/1/paidAmount").decimalValue()).isEqualByComparingTo("1980.00");

        // The invoices.
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).isEqualTo("PAID");
            assertThat(p.n.outstanding(inv.id())).isEqualByComparingTo("0");
        }
        assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).isEqualByComparingTo("6500.00");
        assertThat(p.utilized(setup.line(0))).isEqualByComparingTo("0");
        assertThat(p.utilized(setup.line(1))).isEqualByComparingTo("0");

        // The attempt.
        var attempt = p.attempt(paid.attemptId());
        assertThat(attempt.get("status")).isEqualTo("PAID");
        assertThat(attempt.get("razorpay_payment_id")).isEqualTo(paid.paymentId());
        assertThat(attempt.get("payment_method")).isEqualTo("upi");
        assertThat(attempt.get("paid_at")).isNotNull();
        assertThat(attempt.get("open_selection_key")).isNull();
        for (var row : p.attemptInvoices(paid.attemptId())) {
            assertThat(row.get("outcome")).isEqualTo("PAID");
            assertThat(row.get("credit_repayment_id")).isNotNull();
            assertThat(row.get("credit_payment_id")).isNotNull();
            assertThat(row.get("credit_payment_refund_id")).isNull();
        }

        // One repayment per supplier line, source ONLINE, pointing back at the attempt, no provider payment id.
        var repayments = p.jdbc.queryForList("select * from credit_repayment where credit_payment_attempt_id = ? "
                + "order by credit_agreement_id", paid.attemptId());
        assertThat(repayments).hasSize(2);
        assertThat(repayments.get(0).get("source")).isEqualTo("ONLINE");
        assertThat(repayments.get(0).get("status")).isEqualTo("COMPLETED");
        assertThat(repayments.get(0).get("provider_payment_id")).isNull();
        assertThat(repayments.get(0).get("wallet_transaction_id")).isNull();
        assertThat(((Number) repayments.get(0).get("credit_agreement_id")).longValue())
                .isEqualTo(setup.line(0).agreementId());
        assertThat((BigDecimal) repayments.get(0).get("amount")).isEqualByComparingTo("10500.00");
        assertThat((BigDecimal) repayments.get(1).get("amount")).isEqualByComparingTo("1980.00");
        assertThat(((Number) repayments.get(1).get("supplier_store_id")).longValue())
                .isEqualTo(setup.line(1).seller().storeId());

        // One payout per repayment, with the commission snapshotted at that supplier's rate.
        long first = ((Number) repayments.get(0).get("id")).longValue();
        long second = ((Number) repayments.get(1).get("id")).longValue();
        var payout1 = p.jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?", first);
        var payout2 = p.jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?", second);
        assertThat(payout1.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) payout1.get("amount")).isEqualByComparingTo("10500.00");
        assertThat((BigDecimal) payout1.get("commission_rate_percent")).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) payout1.get("commission_amount")).isEqualByComparingTo("262.50");
        assertThat((BigDecimal) payout2.get("amount")).isEqualByComparingTo("1980.00");
        assertThat((BigDecimal) payout2.get("commission_rate_percent")).isEqualByComparingTo("4");
        assertThat((BigDecimal) payout2.get("commission_amount")).isEqualByComparingTo("79.20");
        // A later change of the rate does not touch what was snapshotted.
        p.setStoreRate(setup.line(0), "9");
        assertThat((BigDecimal) p.jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?",
                first).get("commission_amount")).isEqualByComparingTo("262.50");

        // One credit payment per invoice: source ONLINE, method UPI, reference the Razorpay payment id.
        var payments = p.jdbc.queryForList("select pay.* from credit_payment pay join credit_repayment r "
                + "on r.id = pay.credit_repayment_id where r.credit_payment_attempt_id = ? order by pay.credit_invoice_id",
                paid.attemptId());
        assertThat(payments).hasSize(3);
        for (var pay : payments) {
            assertThat(pay.get("source")).isEqualTo("ONLINE");
            assertThat(pay.get("method")).isEqualTo("UPI");
            assertThat(pay.get("reference")).isEqualTo(paid.paymentId());
        }

        // The ledger saw the repayment: the rows sum to what was paid.
        assertThat(p.jdbc.queryForObject("select sum(amount) from credit_transaction where credit_agreement_id = ? "
                + "and transaction_type = 'REPAYMENT'", BigDecimal.class, setup.line(0).agreementId()))
                .isEqualByComparingTo("10500.00");
        assertThat(p.jdbc.queryForObject("select sum(amount) from credit_transaction where credit_agreement_id = ? "
                + "and transaction_type = 'REPAYMENT'", BigDecimal.class, setup.line(1).agreementId()))
                .isEqualByComparingTo("1980.00");

        // No wallet money moved.
        assertThat(p.s.snapshot(setup.line(0)).walletRows()).isZero();
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);

        // Audit chain.
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_PAID", paid.attemptId())).isEqualTo(1);
        assertThat(p.auditsFor("CREDIT_REPAYMENT_ONLINE", first)).isEqualTo(1);
        assertThat(p.auditsFor("CREDIT_REPAYMENT_ONLINE", second)).isEqualTo(1);
    }

    @Test
    @DisplayName("the supplier is told once per supplier, with the invoice count and method, and the restaurant is not told on the confirm path")
    void notifications() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.events("CreditRepaymentReceived", paid.attemptId())).isEqualTo(2);
        var received = p.jdbc.queryForList("select payload from outbox_event where event_type = 'CreditRepaymentReceived' "
                + "and json_extract(payload, '$.attemptId') = ? order by aggregate_id", paid.attemptId());
        var counts = new java.util.ArrayList<Integer>();
        for (var row : received) {
            var payload = json.readTree(String.valueOf(row.get("payload")));
            counts.add(payload.get("invoiceCount").asInt());
            assertThat(payload.get("methodLabel").asText()).isEqualTo("UPI");
            assertThat(payload.get("outletId").asLong()).isEqualTo(setup.buyer().outletId());
            assertThat(payload.hasNonNull("repaymentId")).isTrue();
            assertThat(payload.hasNonNull("amount")).isTrue();
        }
        assertThat(counts).containsExactlyInAnyOrder(2, 1);
        assertThat(p.events("CreditPaymentCompleted", paid.attemptId()))
                .describedAs("the restaurant saw it on screen").isZero();
        assertThat(p.events("CreditPaymentRefund", paid.attemptId())).isZero();

        p.relayEvents("CreditRepaymentReceived", paid.attemptId());
        var told = p.jdbc.queryForList("select title, body, audience from notification "
                + "where event_type = 'CreditRepaymentReceived' and target_id in (?, ?) order by target_id",
                setup.line(0).agreementId(), setup.line(1).agreementId());
        assertThat(told).hasSize(2);
        assertThat(told.get(0).get("audience")).isEqualTo("SUPPLIER_STORE");
        assertThat((String) told.get(0).get("title")).isEqualTo("Payment received");
        assertThat((String) told.get(0).get("body")).contains("Paradise").contains("10,500.00")
                .contains("(2 invoices)").contains("by UPI");
        assertThat((String) told.get(1).get("body")).contains("1,980.00").contains("(1 invoice)").contains("by UPI");
    }

    @Test
    @DisplayName("a payment of a CARD: method CARD, the last four digits kept, and the supplier is told 'by card'")
    void cardPath() throws Exception {
        var setup = p.single("2500.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "CARD");
        String paymentId = p.pay(id, "card");

        var reply = p.verify(setup.buyer(), id, paymentId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("method").asText()).isEqualTo("CARD");
        assertThat(reply.data().get("paidWith").asText()).isEqualTo("CARD");
        assertThat(reply.data().get("paymentDetail").asText()).isEqualTo("1111");
        assertThat(p.attempt(id).get("payment_detail")).isEqualTo("1111");
        assertThat(p.jdbc.queryForObject("select pay.method from credit_payment pay join credit_repayment r "
                + "on r.id = pay.credit_repayment_id where r.credit_payment_attempt_id = ?", String.class, id))
                .isEqualTo("CARD");
        p.relayEvents("CreditRepaymentReceived", id);
        assertThat(p.jdbc.queryForObject("select body from notification where event_type = 'CreditRepaymentReceived' "
                + "and target_id = ?", String.class, setup.line(0).agreementId())).contains("by card");
    }

    @Test
    @DisplayName("what the customer paid with is what is recorded, whatever UPI or card was chosen: net banking is NETBANKING")
    void paidWithAnotherMethod() throws Exception {
        var setup = p.single("2500.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String paymentId = p.pay(id, "netbanking");

        var reply = p.verify(setup.buyer(), id, paymentId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("method").asText()).isEqualTo("UPI");
        assertThat(reply.data().get("paidWith").asText()).isEqualTo("NETBANKING");
        assertThat(p.jdbc.queryForObject("select pay.method from credit_payment pay join credit_repayment r "
                + "on r.id = pay.credit_repayment_id where r.credit_payment_attempt_id = ?", String.class, id))
                .isEqualTo("NETBANKING");
    }

    @Test
    @DisplayName("a partly paid invoice is paid for what is left, and the supplier's earlier receipt stays")
    void partPaidInvoice() throws Exception {
        var setup = p.single("4000.00");
        assertThat(p.supplierReceipt(setup.inv(0), "1000.00").status()).isEqualTo(200);
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.invoiceStatus(setup.inv(0))).isEqualTo("PAID");
        assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).isEqualByComparingTo("4000.00");
        assertThat(p.jdbc.queryForObject("select amount from credit_repayment where credit_payment_attempt_id = ?",
                BigDecimal.class, paid.attemptId())).isEqualByComparingTo("3000.00");
        assertThat(p.utilized(setup.line(0))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an overdue invoice paid by the restaurant clears the SYSTEM suspension (D-160) and says so once")
    void systemSuspensionLifted() throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", Map.of("maxOverdueAmount", "1000"));
        var inv = new CreditPaySupport.Inv(line, p.invoiceOf(line, "4000.00"));
        var setup = new CreditPaySupport.Setup(buyer, List.of(line), List.of(inv));
        p.s.age(inv.id(), 10);
        creditJobs.sweepOverdue();
        assertThat(p.e.agreementRow(line.agreementId()).get("status")).isEqualTo("SUSPENDED");
        var paid = p.createAndPay(buyer, p.allItems(setup));

        assertThat(p.verify(buyer, paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.e.agreementRow(line.agreementId()).get("status")).isEqualTo("ACTIVE");
        assertThat(p.e.agreementRow(line.agreementId()).get("suspension_source")).isNull();
        assertThat(p.agreementEvents("CreditReinstated", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier's own suspension is never lifted by an online payment")
    void supplierSuspensionNotLifted() throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", Map.of("maxOverdueAmount", "1000"));
        var inv = new CreditPaySupport.Inv(line, p.invoiceOf(line, "4000.00"));
        var setup = new CreditPaySupport.Setup(buyer, List.of(line), List.of(inv));
        p.s.age(inv.id(), 10);
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", inv.id());
        p.e.suspend(line, "Account under review");
        var paid = p.createAndPay(buyer, p.allItems(setup));

        assertThat(p.verify(buyer, paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.invoiceStatus(inv)).isEqualTo("PAID");
        assertThat(p.e.agreementRow(line.agreementId()).get("status")).isEqualTo("SUSPENDED");
        assertThat(p.agreementEvents("CreditReinstated", line.agreementId())).isZero();
    }

    @Test
    @DisplayName("waiting 'paid direct' claims on a paid invoice are closed as superseded")
    void claimsSuperseded() throws Exception {
        var setup = p.single("4000.00");
        var claim = p.e.claim(setup.buyer().token(), setup.inv(0).id(), "4000.00");
        assertThat(claim.status()).describedAs(claim.body().toString()).isIn(200, 201);
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(p.jdbc.queryForObject("select status from credit_payment_claim where credit_invoice_id = ?",
                String.class, setup.inv(0).id())).isEqualTo("SUPERSEDED");
    }

    @Test
    @DisplayName("a bad checkout signature is 400, nothing moves, and a good one afterwards still pays")
    void badSignature() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        var before = p.snapshot(setup);

        var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId(), "forged-signature");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(reply.body().at("/error/message").asText())
                .isEqualTo("We couldn't verify that payment. Nothing was paid.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("CREATED");
        assertThat(p.auditsFor("CREDIT_PAY_VERIFY_REJECTED", paid.attemptId())).isEqualTo(1);

        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);
        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a declined payment is 422 PAYMENT_FAILED, the attempt stays CREATED, and a second checkout on the same order pays")
    void declineThenPay() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        var declined = provider.declineAttempt(order);
        var before = p.snapshot(setup);

        var reply = p.verify(setup.buyer(), id, declined.providerPaymentId());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("PAYMENT_FAILED");
        assertThat(reply.body().at("/error/message").asText())
                .isEqualTo("That payment didn't go through. Nothing was charged.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.attemptStatus(id)).isEqualTo("CREATED");

        String second = provider.completeCheckout(order, "upi").providerPaymentId();
        var ok = p.verify(setup.buyer(), id, second);
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().get("status").asText()).isEqualTo("PAID");
        p.assertAppliedOnce(setup, id, 2, 3);
    }

    @Test
    @DisplayName("a payment not captured yet, or Razorpay unreachable, is 409 CREDIT_PAY_PROCESSING: nothing moves, the attempt stays open")
    void notCapturedYet() throws Exception {
        var setup = p.single("1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        String paymentId = p.pay(id, "upi");
        var before = p.snapshot(setup);

        // Authorised, not captured.
        doReturn(new PaymentProvider.ProviderPayment(paymentId, order, PaymentProvider.ProviderPaymentStatus.AUTHORIZED,
                new BigDecimal("1000.00"), BigDecimal.ZERO, null, null, "upi", null))
                .when(provider).fetchPayment(paymentId);
        var authorised = p.verify(setup.buyer(), id, paymentId);
        assertThat(authorised.status()).describedAs(authorised.body().toString()).isEqualTo(409);
        assertThat(authorised.code()).isEqualTo("CREDIT_PAY_PROCESSING");

        // Razorpay unreachable.
        doThrow(new PaymentProviderException("Razorpay is down", true, "GATEWAY_ERROR"))
                .when(provider).fetchPayment(paymentId);
        var down = p.verify(setup.buyer(), id, paymentId);
        assertThat(down.status()).describedAs(down.body().toString()).isEqualTo(409);
        assertThat(down.code()).isEqualTo("CREDIT_PAY_PROCESSING");

        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.attemptStatus(id)).isEqualTo("CREATED");
        reset(provider);
        assertThat(p.verify(setup.buyer(), id, paymentId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a payment of another order is 400 'doesn't match', logged, nothing applied")
    void paymentOfAnotherOrder() throws Exception {
        var setup = p.standard();
        var mine = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        var otherSetup = p.single("777.00");
        var theirs = p.createAndPay(otherSetup.buyer(), p.allItems(otherSetup));
        var before = p.snapshot(setup);

        var reply = p.verify(setup.buyer(), mine, theirs.paymentId());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(reply.body().at("/error/message").asText())
                .isEqualTo("That payment doesn't match this payment request. Nothing was paid.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.attemptStatus(mine)).isEqualTo("CREATED");
        assertThat(p.repayments(mine)).isZero();
    }

    @Test
    @DisplayName("verify twice: 200 both times, one apply, the second changes nothing")
    void verifyTwice() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);
        var before = p.snapshot(setup);

        var again = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());

        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data().get("status").asText()).isEqualTo("PAID");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_PAID", paid.attemptId())).isEqualTo(1);
    }

    @Test
    @DisplayName("an attempt that was never opened (no order) cannot be verified: 409 'never opened'")
    void neverOpened() throws Exception {
        var setup = p.single("1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        jdbc.update("update credit_payment_attempt set razorpay_order_id = null where id = ?", id);

        var reply = p.verify(setup.buyer(), id, "mock_pay_x");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("That payment was never opened.");
    }

    @Test
    @DisplayName("a FAILED attempt has ended: 409 'That payment has ended. Start a new one.'")
    void failedAttemptHasEnded() throws Exception {
        var setup = p.single("1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        jdbc.update("update credit_payment_attempt set status = 'FAILED', open_selection_key = null where id = ?", id);
        String paymentId = p.pay(id, "upi");

        var reply = p.verify(setup.buyer(), id, paymentId);

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("That payment has ended. Start a new one.");
        assertThat(p.repayments(id)).isZero();
    }

    @Test
    @DisplayName("a payment id or signature that is not letters, digits and underscores is 400 and never reaches the provider")
    void verifyValidation() throws Exception {
        var setup = p.single("1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");

        assertThat(p.verify(setup.buyer(), id, "pay/../x").status()).isEqualTo(400);
        assertThat(p.verify(setup.buyer(), id, "").status()).isEqualTo(400);
        assertThat(p.verify(setup.buyer(), id, "pay_x", "").status()).isEqualTo(400);
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).fetchPayment(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the restaurant's own status read tracks the attempt: CREATED, then PAID with the refund state NONE")
    void statusReadFollows() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.status(setup.buyer(), paid.attemptId()).data().get("status").asText()).isEqualTo("CREATED");

        p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());

        var read = p.status(setup.buyer(), paid.attemptId());
        assertThat(read.data().get("status").asText()).isEqualTo("PAID");
        assertThat(read.data().get("razorpayPaymentId").asText()).isEqualTo(paid.paymentId());
        assertThat(read.data().get("refundAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(read.data().get("refundState").asText()).isEqualTo("NONE");
        assertThat(read.data().at("/suppliers/0/repaymentId").isNull()).isFalse();
    }
}
