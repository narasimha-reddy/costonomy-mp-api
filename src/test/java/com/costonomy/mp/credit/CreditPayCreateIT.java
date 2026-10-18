package com.costonomy.mp.credit;

import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Starting a credit payment (D-199): the picker, the quote and the create call, their validation and error codes,
 * the Razorpay order that is opened, and the idempotency and reopen rules. Nothing here captures a payment.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayCreateIT extends AbstractIntegrationTest {

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

    // ── who may ask ──────────────────────────────────────────────────────

    @Test
    @DisplayName("another restaurant's user gets 404 on every endpoint, never 403, and nothing is written")
    void anotherOutletIsNotFound() throws Exception {
        var setup = p.standard();
        var stranger = p.s.newBuyer();
        long attempt = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        var before = p.snapshot(setup);
        String base = "/api/v1/outlets/" + setup.buyer().outletId() + "/credit";

        var payable = p.e.call("GET", stranger.token(), base + "/payable-invoices", null, null);
        var quote = p.e.call("POST", stranger.token(), base + "/payments/quote", null,
                Map.of("items", p.allItems(setup)));
        var create = p.e.call("POST", stranger.token(), base + "/payments", UUID.randomUUID().toString(),
                Map.of("items", p.allItems(setup), "method", "UPI"));
        var verify = p.e.call("POST", stranger.token(), base + "/payments/" + attempt + "/verify", null,
                Map.of("razorpayPaymentId", "pay_x", "razorpaySignature", CreditPaySupport.SIG));
        var read = p.e.call("GET", stranger.token(), base + "/payments/" + attempt, null, null);

        for (var reply : List.of(payable, quote, create, verify, read)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(404);
            assertThat(reply.code()).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("an attempt of one outlet is 404 under another outlet of the same user")
    void attemptUnderTheWrongOutlet() throws Exception {
        var setup = p.standard();
        long attempt = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        var other = p.s.newBuyer();

        var read = p.status(other, attempt);

        assertThat(read.status()).isEqualTo(404);
        assertThat(read.code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("the supplier's own user cannot pay on the restaurant's behalf")
    void supplierCannotPay() throws Exception {
        var setup = p.single("1000.00");
        String path = "/api/v1/outlets/" + setup.buyer().outletId() + "/credit/payments";

        var reply = p.e.call("POST", setup.line(0).seller().token(), path, UUID.randomUUID().toString(),
                Map.of("items", p.allItems(setup), "method", "UPI"));

        assertThat(reply.status()).isIn(403, 404);
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId())).isZero();
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty selection is 400 with the exact message, on quote and create")
    void emptySelection() throws Exception {
        var setup = p.standard();
        var before = p.snapshot(setup);

        var quote = p.quote(setup.buyer(), List.of());
        var create = p.create(setup.buyer(), List.of(), "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(reply.body().toString()).contains("Choose at least one invoice.");
        }
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("51 invoices are refused, 50 are not a validation problem")
    void tooManyInvoices() throws Exception {
        var setup = p.single("1000.00");
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 51; i++) {
            items.add(CreditPaySupport.item(i, new BigDecimal("10.00")));
        }

        var quote = p.quote(setup.buyer(), items);
        var create = p.create(setup.buyer(), items, "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(reply.body().toString()).contains("Choose at most 50 invoices at a time.");
        }
        // 50 invoices that do not exist pass validation and fail later with 404, not 400.
        var fifty = new ArrayList<>(items.subList(0, 50));
        assertThat(p.quote(setup.buyer(), fifty).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the same invoice twice is 400")
    void duplicateIds() throws Exception {
        var setup = p.single("1000.00");
        var item = p.items(setup.inv(0)).get(0);

        var quote = p.quote(setup.buyer(), List.of(item, item));
        var create = p.create(setup.buyer(), List.of(item, item), "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(reply.body().toString()).contains("Each invoice can be chosen only once.");
        }
    }

    @Test
    @DisplayName("an outstanding of zero, negative or with three decimals is 400; so is a missing or unknown method")
    void badFields() throws Exception {
        var setup = p.single("1000.00");
        long id = setup.inv(0).id();
        var before = p.snapshot(setup);

        for (String bad : List.of("0", "0.00", "-5.00", "1000.001")) {
            var item = Map.<String, Object>of("invoiceId", id, "outstanding", bad);
            assertThat(p.quote(setup.buyer(), List.of(item)).status()).describedAs(bad).isEqualTo(400);
            assertThat(p.create(setup.buyer(), List.of(item), "UPI").status()).describedAs(bad).isEqualTo(400);
        }
        var noOutstanding = Map.<String, Object>of("invoiceId", id);
        assertThat(p.quote(setup.buyer(), List.of(noOutstanding)).status()).isEqualTo(400);
        assertThat(p.create(setup.buyer(), p.items(setup.inv(0)), "NETBANKING").status()).isEqualTo(400);
        var noMethod = p.e.call("POST", setup.buyer().token(),
                "/api/v1/outlets/" + setup.buyer().outletId() + "/credit/payments", UUID.randomUUID().toString(),
                Map.of("items", p.items(setup.inv(0))));
        assertThat(noMethod.status()).isEqualTo(400);
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("the Idempotency-Key must be 1 to 100 characters")
    void idempotencyKeyLength() throws Exception {
        var setup = p.single("1000.00");
        var items = p.allItems(setup);
        var before = p.snapshot(setup);

        var missing = p.create(setup.buyer(), items, "UPI", null);
        var tooLong = p.create(setup.buyer(), items, "UPI", "k".repeat(101));

        // A missing header is the framework's 400 MALFORMED_REQUEST (as on every other keyed credit endpoint);
        // the by-hand length check gives the contract's VALIDATION_ERROR.
        assertThat(missing.status()).describedAs(missing.body().toString()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(tooLong.status()).describedAs(tooLong.body().toString()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(tooLong.body().toString()).contains("The Idempotency-Key must be 1 to 100 characters.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.create(setup.buyer(), items, "UPI", "k".repeat(100)).status()).isEqualTo(201);
    }

    // ── the selection checks ─────────────────────────────────────────────

    @Test
    @DisplayName("an invoice that is not this outlet's is 404 CreditInvoice, on quote and create, and nothing is written")
    void foreignInvoice() throws Exception {
        var mine = p.single("1000.00");
        var theirs = p.single("500.00");
        var before = p.snapshot(mine);

        var items = List.of(p.items(mine.inv(0)).get(0), p.items(theirs.inv(0)).get(0));
        var quote = p.quote(mine.buyer(), items);
        var create = p.create(mine.buyer(), items, "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(404);
            assertThat(reply.code()).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(p.snapshot(mine)).isEqualTo(before);
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                theirs.buyer().outletId())).isZero();
    }

    @Test
    @DisplayName("a wrong outstanding or a settled invoice is 409 CREDIT_PAY_SELECTION_CHANGED naming exactly those invoices")
    void selectionChanged() throws Exception {
        var setup = p.standard();
        var stale = CreditPaySupport.item(setup.inv(1).id(), new BigDecimal("3999.00"));
        var settled = setup.inv(2);
        assertThat(p.supplierReceipt(settled, "1980.00").status()).isEqualTo(200);
        var items = List.of(p.items(setup.inv(0)).get(0), stale,
                CreditPaySupport.item(settled.id(), new BigDecimal("1980.00")));
        var before = p.snapshot(setup);

        var quote = p.quote(setup.buyer(), items);
        var create = p.create(setup.buyer(), items, "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("CREDIT_PAY_SELECTION_CHANGED");
            var ids = new ArrayList<Long>();
            reply.body().at("/error/details/changedInvoiceIds").forEach(x -> ids.add(x.asLong()));
            assertThat(ids).containsExactlyInAnyOrder(setup.inv(1).id(), settled.id());
        }
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("an invoice moving to OVERDUE, a due-date extension or a suspension is not a change")
    void statusMoveIsNotAChange() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        p.s.age(setup.inv(0).id(), 3);
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", setup.inv(0).id());
        p.e.suspend(setup.line(1), "Account under review");

        assertThat(p.quote(setup.buyer(), items).status()).isEqualTo(200);
        assertThat(p.create(setup.buyer(), items, "CARD").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("a store that is no longer tradeable is 422 CREDIT_PAY_SUPPLIER_UNAVAILABLE with its name, nothing written")
    void supplierUnavailable() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        p.deactivateStore(setup.line(1));
        var before = p.snapshot(setup);

        var quote = p.quote(setup.buyer(), items);
        var create = p.create(setup.buyer(), items, "UPI");

        for (var reply : List.of(quote, create)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("CREDIT_PAY_SUPPLIER_UNAVAILABLE");
            assertThat(reply.body().at("/error/details/supplierStoreIds/0").asLong())
                    .isEqualTo(setup.line(1).seller().storeId());
            assertThat(reply.body().at("/error/details/supplierNames/0").asText()).isEqualTo("ABC Foods");
            assertThat(reply.body().at("/error/message").asText())
                    .isEqualTo("Pay ABC Foods directly. Online payment isn't available for them.");
        }
        assertThat(p.snapshot(setup)).isEqualTo(before);
        verify(provider, times(0)).createAuthorization(any());
    }

    // ── the quote ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the quote adds up on the server, and says what each method would do; nothing is written")
    void quoteShape() throws Exception {
        var setup = p.standard();
        var before = p.snapshot(setup);

        var reply = p.quote(setup.buyer(), p.allItems(setup));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.get("invoiceCount").asInt()).isEqualTo(3);
        assertThat(d.get("supplierCount").asInt()).isEqualTo(2);
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(d.at("/methods")).hasSize(3);
        assertThat(d.at("/methods/0/method").asText()).isEqualTo("UPI");
        assertThat(d.at("/methods/1/method").asText()).isEqualTo("CARD");
        assertThat(d.at("/methods/2/method").asText()).isEqualTo("WALLET");
        for (int i = 0; i < 2; i++) {
            assertThat(d.at("/methods/" + i + "/available").asBoolean()).isTrue();
            assertThat(d.at("/methods/" + i + "/feeAmount").decimalValue()).isEqualByComparingTo("0");
            assertThat(d.at("/methods/" + i + "/totalCharged").decimalValue()).isEqualByComparingTo("12480.00");
            assertThat(d.at("/methods/" + i + "/unavailableReason").isNull()).isTrue();
        }
        // No money in the wallet: short by all of it.
        assertThat(d.at("/methods/2/available").asBoolean()).isFalse();
        assertThat(d.at("/methods/2/unavailableReason").asText()).isEqualTo("WALLET_SHORT");
        assertThat(d.at("/methods/2/shortBy").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        verify(provider, times(0)).createAuthorization(any());
    }

    @Test
    @DisplayName("with enough in the wallet the wallet method is available and the quote shows the balance; short names the gap")
    void quoteWallet() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "9000.00");

        var short_ = p.quote(setup.buyer(), p.allItems(setup));
        assertThat(short_.data().get("walletBalance").decimalValue()).isEqualByComparingTo("9000.00");
        assertThat(short_.data().at("/methods/2/shortBy").decimalValue()).isEqualByComparingTo("3480.00");

        p.s.topUp(setup.buyer(), "3480.00");
        var enough = p.quote(setup.buyer(), p.allItems(setup));
        assertThat(enough.data().at("/methods/2/available").asBoolean()).isTrue();
        assertThat(enough.data().at("/methods/2/unavailableReason").isNull()).isTrue();
        assertThat(enough.data().at("/methods/2/shortBy").isNull()).isTrue();
    }

    @Test
    @DisplayName("a wallet on hold is WALLET_ON_HOLD in the quote")
    void quoteWalletOnHold() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        jdbc.update("update wallet set status = 'ON_HOLD' where outlet_id = ?", setup.buyer().outletId());

        var reply = p.quote(setup.buyer(), p.allItems(setup));

        assertThat(reply.data().at("/methods/2/available").asBoolean()).isFalse();
        assertThat(reply.data().at("/methods/2/unavailableReason").asText()).isEqualTo("WALLET_ON_HOLD");
    }

    @Test
    @DisplayName("below Razorpay's minimum of 1.00: UPI and card are BELOW_MINIMUM, the wallet is not; 1.00 itself is fine")
    void belowMinimum() throws Exception {
        var setup = p.single("100.00");
        assertThat(p.supplierReceipt(setup.inv(0), "99.50").status()).isEqualTo(200);
        p.s.topUp(setup.buyer(), "10.00");
        var items = p.items(setup.inv(0));
        var before = p.snapshot(setup);

        var quote = p.quote(setup.buyer(), items);
        var create = p.create(setup.buyer(), items, "UPI");

        assertThat(quote.data().get("amount").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(quote.data().at("/methods/0/available").asBoolean()).isFalse();
        assertThat(quote.data().at("/methods/0/unavailableReason").asText()).isEqualTo("BELOW_MINIMUM");
        assertThat(quote.data().at("/methods/1/unavailableReason").asText()).isEqualTo("BELOW_MINIMUM");
        assertThat(quote.data().at("/methods/2/available").asBoolean()).isTrue();
        assertThat(create.status()).describedAs(create.body().toString()).isEqualTo(400);
        assertThat(create.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(create.body().at("/error/message").asText())
                .isEqualTo("Online payments start at ₹1. Pay this from your wallet.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        verify(provider, times(0)).createAuthorization(any());

        // Exactly 1.00 is allowed.
        var one = p.single("100.00");
        assertThat(p.supplierReceipt(one.inv(0), "99.00").status()).isEqualTo(200);
        assertThat(p.create(one.buyer(), p.items(one.inv(0)), "UPI").status()).isEqualTo(201);
    }

    // ── the picker ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the picker lists only open invoices, with the figures the server computed and the flags it runs on")
    void payableShape() throws Exception {
        var setup = p.standard();
        // Paid in full: not listed. Part paid: listed with what is left.
        assertThat(p.supplierReceipt(setup.inv(2), "1980.00").status()).isEqualTo(200);
        assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200);

        var reply = p.payable(setup.buyer());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.get("outletId").asLong()).isEqualTo(setup.buyer().outletId());
        assertThat(d.get("onlinePayEnabled").asBoolean()).isTrue();
        assertThat(d.get("walletPayEnabled").asBoolean()).isTrue();
        assertThat(d.at("/methods/0").asText()).isEqualTo("UPI");
        assertThat(d.at("/methods/1").asText()).isEqualTo("CARD");
        assertThat(d.at("/methods/2").asText()).isEqualTo("WALLET");
        assertThat(d.get("feePercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.get("minOnlineAmount").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(d.get("maxInvoices").asInt()).isEqualTo(50);
        // The supplier with nothing open is not in the list.
        assertThat(d.at("/suppliers")).hasSize(1);
        var supplier = d.at("/suppliers/0");
        assertThat(supplier.get("agreementId").asLong()).isEqualTo(setup.line(0).agreementId());
        assertThat(supplier.get("supplierStoreId").asLong()).isEqualTo(setup.line(0).seller().storeId());
        assertThat(supplier.get("supplierName").asText()).isEqualTo("ABC Foods");
        assertThat(supplier.get("storeName").asText()).isEqualTo("ABC store");
        assertThat(supplier.get("payable").asBoolean()).isTrue();
        assertThat(supplier.get("notPayableReason").isNull()).isTrue();
        assertThat(supplier.get("due").decimalValue()).isEqualByComparingTo("9500.00");
        assertThat(supplier.get("invoices")).hasSize(2);
        var part = supplier.at("/invoices").findParents("invoiceId").stream()
                .filter(x -> x.get("invoiceId").asLong() == setup.inv(1).id()).findFirst().orElseThrow();
        assertThat(part.get("amount").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(part.get("paidAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(part.get("outstanding").decimalValue()).isEqualByComparingTo("3000.00");
        assertThat(part.get("openClaimsAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(part.get("invoiceNumber").asText()).isNotBlank();
        assertThat(part.get("dueDate").asText()).isNotBlank();
        assertThat(part.get("dueState").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the picker orders suppliers by overdue first (oldest first), then due date; overdue invoices lead within a supplier")
    void payableOrdering() throws Exception {
        var buyer = p.s.newBuyer();
        var calm = p.s.creditLine(buyer, "200000", Map.of());
        var late = p.s.creditLine(buyer, "200000", Map.of());
        var later = p.s.creditLine(buyer, "200000", Map.of());
        var calmInv = new CreditPaySupport.Inv(calm, p.invoiceOf(calm, "500.00"));
        var lateOk = new CreditPaySupport.Inv(late, p.invoiceOf(late, "300.00"));
        var lateOverdue = new CreditPaySupport.Inv(late, p.invoiceOf(late, "700.00"));
        var laterInv = new CreditPaySupport.Inv(later, p.invoiceOf(later, "900.00"));
        p.s.age(lateOverdue.id(), 20);
        p.s.age(laterInv.id(), 5);
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id in (?, ?)", lateOverdue.id(), laterInv.id());

        var reply = p.payable(buyer);

        var suppliers = reply.data().at("/suppliers");
        assertThat(suppliers).hasSize(3);
        assertThat(suppliers.get(0).get("agreementId").asLong()).describedAs("oldest overdue first")
                .isEqualTo(late.agreementId());
        assertThat(suppliers.get(1).get("agreementId").asLong()).isEqualTo(later.agreementId());
        assertThat(suppliers.get(2).get("agreementId").asLong()).describedAs("nothing overdue last")
                .isEqualTo(calm.agreementId());
        assertThat(suppliers.get(0).get("overdue").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(suppliers.get(0).get("due").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(suppliers.get(0).at("/invoices/0/invoiceId").asLong()).describedAs("overdue invoice first")
                .isEqualTo(lateOverdue.id());
        assertThat(suppliers.get(0).at("/invoices/0/dueState").asText()).isEqualTo("OVERDUE");
        assertThat(suppliers.get(0).at("/invoices/0/daysToDue").asInt()).isNegative();
        assertThat(suppliers.get(0).at("/invoices/1/invoiceId").asLong()).isEqualTo(lateOk.id());
        assertThat(calmInv.id()).isPositive();
    }

    @Test
    @DisplayName("a store that cannot be paid is listed read-only with the reason; a suspended line is still payable")
    void payableNotPayable() throws Exception {
        var setup = p.standard();
        p.deactivateStore(setup.line(1));
        p.e.suspend(setup.line(0), "Account under review");

        var reply = p.payable(setup.buyer());

        var suppliers = reply.data().at("/suppliers");
        assertThat(suppliers).hasSize(2);
        for (var supplier : suppliers) {
            if (supplier.get("agreementId").asLong() == setup.line(1).agreementId()) {
                assertThat(supplier.get("payable").asBoolean()).isFalse();
                assertThat(supplier.get("notPayableReason").asText()).isEqualTo("Pay this supplier directly.");
                assertThat(supplier.get("invoices")).hasSize(1);
            } else {
                assertThat(supplier.get("agreementStatus").asText()).isEqualTo("SUSPENDED");
                assertThat(supplier.get("payable").asBoolean()).isTrue();
            }
        }
    }

    // ── create (online) ──────────────────────────────────────────────────

    @Test
    @DisplayName("create opens one auto-capture Razorpay order for the server's total and records the attempt before anything is paid")
    void createOpensOrder() throws Exception {
        var setup = p.standard();
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "UPI", "key-1");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var d = reply.data();
        long id = d.get("attemptId").asLong();
        assertThat(d.get("status").asText()).isEqualTo("CREATED");
        assertThat(d.get("method").asText()).isEqualTo("UPI");
        assertThat(d.get("invoiceCount").asInt()).isEqualTo(3);
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(d.get("feeAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.get("totalCharged").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(d.get("currency").asText()).isEqualTo("INR");
        assertThat(d.get("razorpayOrderId").asText()).startsWith("mock_order_");
        assertThat(d.get("keyId").asText()).isEqualTo("mock_key");
        assertThat(d.get("reopened").asBoolean()).isFalse();
        assertThat(d.get("refundState").asText()).isEqualTo("NONE");
        assertThat(d.get("expiresAt").isNull()).isFalse();
        assertThat(d.get("paidAt").isNull()).isTrue();
        assertThat(d.get("razorpayPaymentId").isNull()).isTrue();
        assertThat(d.at("/invoices")).hasSize(3);
        assertThat(d.at("/invoices/0/outcome").asText()).isEqualTo("PENDING");
        assertThat(d.at("/suppliers")).hasSize(2);

        var row = p.attempt(id);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("method")).isEqualTo("UPI");
        assertThat(row.get("channel")).isEqualTo("ONLINE");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("12480.00");
        assertThat((BigDecimal) row.get("fee_percent")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("total_charged")).isEqualByComparingTo("12480.00");
        assertThat(row.get("razorpay_order_id")).isEqualTo(d.get("razorpayOrderId").asText());
        assertThat(row.get("razorpay_payment_id")).isNull();
        assertThat(row.get("idempotency_key")).isEqualTo("outlet-" + setup.buyer().outletId() + "-key-1");
        assertThat((String) row.get("open_selection_key"))
                .startsWith("outlet-" + setup.buyer().outletId() + "-ONLINE-");
        assertThat(row.get("created_by")).isNotNull();
        var rows = p.attemptInvoices(id);
        assertThat(rows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(rows.get(i).get("outcome")).isEqualTo("PENDING");
            assertThat((BigDecimal) rows.get(i).get("expected_amount"))
                    .isEqualByComparingTo(p.outstanding(((Number) rows.get(i).get("credit_invoice_id")).longValue()));
        }
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_CREATED", id)).isEqualTo(1);

        // Created is not paid: no debt moved, no money, no repayment.
        var after = p.snapshot(setup);
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(p.repayments(id)).isZero();
        verify(provider, times(1)).createAuthorization(org.mockito.ArgumentMatchers.argThat(request ->
                request.autoCapture() && request.amount().compareTo(new BigDecimal("12480.00")) == 0
                        && "INR".equals(request.currency())
                        && ("creditpay-" + id).equals(request.referenceId())
                        && ("creditpay-" + id).equals(request.idempotencyKey())
                        && "Mandi credit payment".equals(request.description())));
    }

    @Test
    @DisplayName("the client never decides the total: only invoice ids and the shown outstanding go in, the order is for the rows' sum")
    void serverTotal() throws Exception {
        var setup = p.standard();
        var items = p.items(setup.inv(0), setup.inv(2));

        var reply = p.create(setup.buyer(), items, "CARD");

        assertThat(reply.data().get("amount").decimalValue()).isEqualByComparingTo("8480.00");
        assertThat(reply.data().get("method").asText()).isEqualTo("CARD");
        verify(provider).createAuthorization(org.mockito.ArgumentMatchers.argThat(request ->
                request.amount().compareTo(new BigDecimal("8480.00")) == 0));
    }

    @Test
    @DisplayName("the same Idempotency-Key returns the same attempt and opens no second Razorpay order")
    void idempotentReplay() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);

        var first = p.create(setup.buyer(), items, "UPI", "same-key");
        var again = p.create(setup.buyer(), items, "UPI", "same-key");

        assertThat(again.status()).describedAs(again.body().toString()).isIn(200, 201);
        assertThat(again.data().get("attemptId").asLong()).isEqualTo(first.data().get("attemptId").asLong());
        assertThat(again.data().get("razorpayOrderId").asText()).isEqualTo(first.data().get("razorpayOrderId").asText());
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId())).isEqualTo(1);
        verify(provider, times(1)).createAuthorization(any());
    }

    @Test
    @DisplayName("after the idempotency record is gone, the row's own key still returns the same attempt")
    void replayAfterIdempotencyRecordGone() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        var first = p.create(setup.buyer(), items, "UPI", "gone-key");
        jdbc.update("delete from idempotency_record where operation = 'credit.pay.create' and idempotency_key = ?",
                "gone-key");

        var again = p.create(setup.buyer(), items, "UPI", "gone-key");

        assertThat(again.status()).describedAs(again.body().toString()).isIn(200, 201);
        assertThat(again.data().get("attemptId").asLong()).isEqualTo(first.data().get("attemptId").asLong());
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId())).isEqualTo(1);
        verify(provider, times(1)).createAuthorization(any());
    }

    @Test
    @DisplayName("the same key with different items is IDEMPOTENCY_KEY_REUSE and nothing new is made")
    void sameKeyDifferentItems() throws Exception {
        var setup = p.standard();
        p.create(setup.buyer(), p.items(setup.inv(0)), "UPI", "reuse");
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.items(setup.inv(0), setup.inv(1)), "UPI", "reuse");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("UPI and card share a channel: the same key with the other method replays; the method only pre-selects checkout")
    void upiCardSameChannel() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        var upi = p.create(setup.buyer(), items, "UPI", "channel-key");

        var card = p.create(setup.buyer(), items, "CARD", "channel-key");

        assertThat(card.status()).describedAs(card.body().toString()).isIn(200, 201);
        assertThat(card.data().get("attemptId").asLong()).isEqualTo(upi.data().get("attemptId").asLong());
        verify(provider, times(1)).createAuthorization(any());
    }

    @Test
    @DisplayName("a new key for the same selection reopens the open attempt: same order, reopened true, key id given again")
    void reopen() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        var first = p.create(setup.buyer(), items, "UPI");

        var second = p.create(setup.buyer(), items, "CARD");

        assertThat(second.status()).describedAs(second.body().toString()).isIn(200, 201);
        assertThat(second.data().get("reopened").asBoolean()).isTrue();
        assertThat(second.data().get("attemptId").asLong()).isEqualTo(first.data().get("attemptId").asLong());
        assertThat(second.data().get("razorpayOrderId").asText()).isEqualTo(first.data().get("razorpayOrderId").asText());
        assertThat(second.data().get("keyId").asText()).isEqualTo("mock_key");
        assertThat(second.data().get("status").asText()).isEqualTo("CREATED");
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId())).isEqualTo(1);
        verify(provider, times(1)).createAuthorization(any());
        // The order of the items does not make it another selection.
        var reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        var third = p.create(setup.buyer(), reversed, "UPI");
        assertThat(third.data().get("attemptId").asLong()).isEqualTo(first.data().get("attemptId").asLong());
    }

    @Test
    @DisplayName("a selection that changed since the open attempt ends it EXPIRED ('Selection changed') and opens a new one")
    void reopenAfterSelectionChanged() throws Exception {
        var setup = p.standard();
        var firstItems = p.items(setup.inv(0), setup.inv(1));
        long old = p.createOk(setup.buyer(), firstItems, "UPI");
        assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200);

        var reply = p.create(setup.buyer(), p.items(setup.inv(0), setup.inv(1)), "UPI");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        long fresh = reply.data().get("attemptId").asLong();
        assertThat(fresh).isNotEqualTo(old);
        assertThat(reply.data().get("reopened").asBoolean()).isFalse();
        assertThat(reply.data().get("amount").decimalValue()).isEqualByComparingTo("9500.00");
        assertThat(p.attemptStatus(old)).isEqualTo("EXPIRED");
        assertThat(p.attempt(old).get("failure_reason")).isEqualTo("Selection changed");
        assertThat(p.attempt(old).get("open_selection_key")).isNull();
        assertThat(p.attempt(old).get("expired_at")).isNotNull();
        assertThat(p.attemptStatus(fresh)).isEqualTo("CREATED");
        assertThat(p.attempt(fresh).get("razorpay_order_id")).isNotEqualTo(p.attempt(old).get("razorpay_order_id"));
        verify(provider, times(2)).createAuthorization(any());
    }

    @Test
    @DisplayName("Razorpay refusing the order: the attempt is FAILED with a reason, 422 PAYMENT_FAILED, nothing charged; a retry works")
    void providerRefuses() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        doThrow(new PaymentProviderException("Razorpay is down", true, "GATEWAY_ERROR"))
                .when(provider).createAuthorization(any());
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), items, "UPI");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("PAYMENT_FAILED");
        assertThat(reply.body().at("/error/message").asText())
                .isEqualTo("We couldn't start the payment. Nothing was charged. Please try again.");
        var after = p.snapshot(setup);
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.attempts()).isEqualTo(1);
        var row = p.jdbc.queryForMap("select * from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId());
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("failure_reason")).startsWith("Could not open a Razorpay order");
        assertThat(row.get("razorpay_order_id")).isNull();
        assertThat(row.get("open_selection_key")).describedAs("a failed attempt does not block the selection").isNull();

        reset(provider);
        var retry = p.create(setup.buyer(), items, "UPI");
        assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(201);
        assertThat(retry.data().get("attemptId").asLong()).isNotEqualTo(((Number) row.get("id")).longValue());
    }

    @Test
    @DisplayName("a failed attempt's key replays the failure, not a new order: the old key answers IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED")
    void failedKeyNeedsANewKey() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);
        doThrow(new PaymentProviderException("Razorpay is down", true, "GATEWAY_ERROR"))
                .when(provider).createAuthorization(any());
        assertThat(p.create(setup.buyer(), items, "UPI", "failing-key").status()).isEqualTo(422);
        reset(provider);

        var again = p.create(setup.buyer(), items, "UPI", "failing-key");

        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(409);
        assertThat(again.code()).isIn("IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED", "IDEMPOTENCY_KEY_REUSE");
        verify(provider, times(0)).createAuthorization(any());
    }

    @Test
    @DisplayName("two taps at the same moment on one selection make one attempt and one Razorpay order")
    void concurrentTaps() throws Exception {
        var setup = p.standard();
        var items = p.allItems(setup);

        var replies = p.concurrently(List.<java.util.concurrent.Callable<CreditWalletSupport.Reply>>of(
                () -> p.create(setup.buyer(), items, "UPI"), () -> p.create(setup.buyer(), items, "CARD")));

        for (var reply : replies) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isIn(200, 201, 409);
            if (reply.status() == 409) {
                assertThat(reply.code()).isEqualTo("CONCURRENT_MODIFICATION");
            }
        }
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?",
                setup.buyer().outletId())).isEqualTo(1);
        verify(provider, times(1)).createAuthorization(any());
    }

    @Test
    @DisplayName("GET on an attempt reads it without asking Razorpay")
    void getAttempt() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");

        var reply = p.status(setup.buyer(), id);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("attemptId").asLong()).isEqualTo(id);
        assertThat(reply.data().get("status").asText()).isEqualTo("CREATED");
        assertThat(reply.data().get("keyId").isNull()).describedAs("the key id is only for create and reopen").isTrue();
        verify(provider, times(0)).fetchPayment(any());
        verify(provider, times(0)).findPaymentForOrder(any());
    }
}
