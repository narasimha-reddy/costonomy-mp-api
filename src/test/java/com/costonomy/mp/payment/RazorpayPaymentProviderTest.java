package com.costonomy.mp.payment;

import com.costonomy.mp.payment.provider.PaymentProvider.AuthorizationRequest;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;
import com.costonomy.mp.payment.provider.PaymentProvider.RefundOptions;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.provider.RazorpayPaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Razorpay adapter against a real HTTP server standing in for Razorpay.
 *
 * <p>What is asserted is what goes over the wire — the body and the headers —
 * because every defect this class has had was in exactly that: a capture flag the
 * current API does not document, an idempotency header Razorpay does not read.
 * Neither would fail a test that mocked the client.
 */
class RazorpayPaymentProviderTest {

    private static final String WEBHOOK_SECRET = "whsec_test_only";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private RazorpayPaymentProvider razorpay;

    /** Path → canned (status, body). */
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    record Canned(int status, String body) {
    }

    record Seen(String method, String path, Map<String, String> headers, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(), values.get(0)));
            String path = exchange.getRequestURI().getPath();
            seen.add(new Seen(exchange.getRequestMethod(), path, headers, body));

            Canned canned = routes.getOrDefault(path, new Canned(404, "{}"));
            byte[] out = canned.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(canned.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        razorpay = new RazorpayPaymentProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "rzp_test_key", "rzp_test_secret", WEBHOOK_SECRET, 4320);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("an order asks for manual capture in the documented form, in paise")
    void orderIsManualCapture() throws IOException {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_A","amount":123450,"currency":"INR","status":"created"}"""));

        var intent = razorpay.createAuthorization(new AuthorizationRequest(
                "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42"));

        assertThat(intent.providerOrderId()).isEqualTo("order_A");
        assertThat(intent.publicKey()).isEqualTo("rzp_test_key");

        JsonNode sent = only("/v1/orders").json();
        assertThat(sent.get("amount").asLong()).isEqualTo(123450);
        assertThat(sent.at("/payment/capture").asText()).isEqualTo("manual");
        // Not the undocumented flag: if Razorpay ignored it the dashboard default
        // would decide, and that may be automatic.
        assertThat(sent.has("payment_capture")).isFalse();
    }

    @Test
    @DisplayName("a payment carries the order it completes")
    void paymentCarriesItsOrder() {
        routes.put("/v1/payments/pay_A", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"authorized","amount":50000}"""));

        var payment = razorpay.fetchPayment("pay_A");

        assertThat(payment.providerOrderId()).isEqualTo("order_A");
        assertThat(payment.status()).isEqualTo(ProviderPaymentStatus.AUTHORIZED);
        assertThat(payment.authorizedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("an id Razorpay does not know is a refusal, not an outage")
    void unknownPaymentIsNotRetryable() {
        routes.put("/v1/payments/pay_forged", new Canned(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist"}}"""));

        assertThatThrownBy(() -> razorpay.fetchPayment("pay_forged"))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        ex -> assertThat(ex.isRetryable()).isFalse());
    }

    @Test
    @DisplayName("N1: Razorpay's real answer for an unknown id, HTTP 400 \"The id provided does not exist\", is NOT_FOUND")
    void unknownPaymentIdAnsweredWith400IsNotFound() {
        // Razorpay does not answer 404 for an id it does not know: it answers 400, BAD_REQUEST_ERROR.
        routes.put("/v1/payments/pay_unknown", new Canned(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist",\
                "source":"business","step":"payment_initiation","reason":"input_validation_failed","metadata":{}}}"""));

        for (Runnable lookup : List.<Runnable>of(() -> razorpay.inspect("pay_unknown"),
                () -> razorpay.fetchPayment("pay_unknown"))) {
            assertThatThrownBy(lookup::run).isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                assertThat(ex.isNotFound()).isTrue();
                assertThat(ex.isRetryable()).isFalse();
                assertThat(ex.isRateLimited()).isFalse();
                assertThat(ex.isCredentialsRefused()).isFalse();
            });
        }
    }

    @Test
    @DisplayName("N1: a real 404 is still NOT_FOUND")
    void unknownPaymentAnsweredWith404IsNotFound() {
        routes.put("/v1/payments/pay_404", new Canned(404, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist"}}"""));

        assertThatThrownBy(() -> razorpay.inspect("pay_404"))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> assertThat(ex.isNotFound()).isTrue());
    }

    @Test
    @DisplayName("N1: every other 400 stays a plain refusal, so a payment is never stopped for a person on it")
    void otherBadRequestsAreNotNotFound() {
        Map<String, String> bodies = Map.of(
                // About the call, not the payment.
                "amount", """
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"The amount must be atleast INR 1.00"}}""",
                // Razorpay's answer for a mistyped path is also a 400 "not found": our bug, not an unknown payment.
                "path", """
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"The requested URL was not found on the server"}}""",
                // The right words under another error code.
                "code", """
                        {"error":{"code":"GATEWAY_ERROR","description":"The id provided does not exist"}}""",
                "already", """
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"This payment has already been captured"}}""",
                "empty", "{}",
                "notjson", "<html>Bad Request</html>",
                // Extra text around the sentence is not the sentence.
                "longer", """
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"The id provided does not exist for this merchant account, contact support"}}""");
        bodies.forEach((name, body) -> {
            routes.put("/v1/payments/pay_" + name, new Canned(400, body));

            assertThatThrownBy(() -> razorpay.inspect("pay_" + name)).describedAs(name)
                    .isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                        assertThat(ex.isNotFound()).describedAs(name).isFalse();
                        assertThat(ex.providerCode()).describedAs(name).isEqualTo("400");
                        assertThat(ex.isRetryable()).describedAs(name).isFalse();
                    });
        });
    }

    @Test
    @DisplayName("N1: a 429, 401 and 403 keep their own codes on a lookup")
    void otherStatusesKeepTheirCodes() {
        routes.put("/v1/payments/pay_429", new Canned(429, "{}"));
        routes.put("/v1/payments/pay_401", new Canned(401, "{}"));
        routes.put("/v1/payments/pay_403", new Canned(403, "{}"));

        assertThatThrownBy(() -> razorpay.inspect("pay_429")).isInstanceOfSatisfying(
                PaymentProviderException.class, ex -> assertThat(ex.isRateLimited()).isTrue());
        assertThatThrownBy(() -> razorpay.inspect("pay_401")).isInstanceOfSatisfying(
                PaymentProviderException.class, ex -> assertThat(ex.isCredentialsRefused()).isTrue());
        assertThatThrownBy(() -> razorpay.inspect("pay_403")).isInstanceOfSatisfying(
                PaymentProviderException.class, ex -> assertThat(ex.isCredentialsRefused()).isTrue());
    }

    @Test
    @DisplayName("a refund sends the idempotency header Razorpay actually reads")
    void refundIsIdempotent() {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, """
                {"id":"rfnd_A","amount":20000,"status":"processed"}"""));

        var refund = razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7",
                RefundOptions.none());

        assertThat(refund.status()).isEqualTo(ProviderRefundStatus.COMPLETED);
        var headers = only("/v1/payments/pay_A/refund").headers();
        assertThat(headers).containsEntry("x-refund-idempotency", "mandi-refund-7");
        assertThat(headers).doesNotContainKey("x-razorpay-idempotency-key");
    }

    @Test
    @DisplayName("a capture records what we asked to capture, not the payment's full amount")
    void partialCaptureRecordsTheCapture() {
        routes.put("/v1/payments/pay_A/capture", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"captured","amount":50000}"""));

        var captured = razorpay.capture("pay_A", new BigDecimal("300.00"), "capture-payment-1");

        // The payment's amount is the whole authorisation; reading it as captured
        // left no remainder to release (D-101).
        assertThat(captured.capturedAmount()).isEqualByComparingTo("300.00");
        assertThat(captured.authorizedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a refund Razorpay reports pending is pending, and processed when it says so")
    void refundStatuses() {
        routes.put("/v1/refunds/rfnd_P", new Canned(200, """
                {"id":"rfnd_P","amount":1000,"status":"pending"}"""));
        routes.put("/v1/refunds/rfnd_D", new Canned(200, """
                {"id":"rfnd_D","amount":1000,"status":"processed"}"""));

        assertThat(razorpay.fetchRefund("rfnd_P").status()).isEqualTo(ProviderRefundStatus.PENDING);
        assertThat(razorpay.fetchRefund("rfnd_D").status()).isEqualTo(ProviderRefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("a blank secret stops the adapter being built at all")
    void blankSecretRefused() {
        assertThatThrownBy(() -> new RazorpayPaymentProvider("http://localhost:1", "rzp_test_key",
                "rzp_test_secret", " ", 4320))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-secret");
    }

    @Test
    @DisplayName("a capture Razorpay refuses as already done is read as the success it is")
    void secondCaptureIsSuccess() {
        // The first capture worked and its response was lost; the retry is refused.
        routes.put("/v1/payments/pay_A/capture", new Canned(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"This payment has already been captured"}}"""));
        routes.put("/v1/payments/pay_A", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"captured","amount":50000}"""));

        var captured = razorpay.capture("pay_A", new BigDecimal("500.00"), "capture-payment-1");

        assertThat(captured.status()).isEqualTo(ProviderPaymentStatus.CAPTURED);
        assertThat(captured.capturedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a capture refused for any other reason stays refused")
    void realCaptureRefusalPropagates() {
        routes.put("/v1/payments/pay_A/capture", new Canned(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"Capture amount exceeds authorised amount"}}"""));
        routes.put("/v1/payments/pay_A", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"authorized","amount":50000}"""));

        assertThatThrownBy(() -> razorpay.capture("pay_A", new BigDecimal("900.00"), "capture-payment-1"))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        ex -> assertThat(ex.isRetryable()).isFalse());
    }

    @Test
    @DisplayName("finding an order's payment prefers money held over a declined attempt")
    void findByOrderSkipsDeclines() {
        routes.put("/v1/orders/order_A/payments", new Canned(200, """
                {"entity":"collection","count":2,"items":[
                  {"id":"pay_declined","order_id":"order_A","status":"failed","amount":50000},
                  {"id":"pay_ok","order_id":"order_A","status":"authorized","amount":50000}]}"""));

        var found = razorpay.findPaymentForOrder("order_A");

        assertThat(found).hasValueSatisfying(payment -> {
            assertThat(payment.providerPaymentId()).isEqualTo("pay_ok");
            assertThat(payment.providerOrderId()).isEqualTo("order_A");
        });
    }

    @Test
    @DisplayName("an order with only declined attempts has no payment yet")
    void onlyDeclinesIsEmpty() {
        // The customer can still retry against the same order; a decline is not
        // the outcome, and treating it as one would abandon an order they then pay.
        routes.put("/v1/orders/order_A/payments", new Canned(200, """
                {"entity":"collection","count":1,"items":[
                  {"id":"pay_declined","order_id":"order_A","status":"failed","amount":50000}]}"""));

        assertThat(razorpay.findPaymentForOrder("order_A")).isEmpty();
    }

    @Test
    @DisplayName("a webhook signature is HMAC-SHA256 of the raw body with the webhook secret")
    void signature() throws Exception {
        String body = "{\"event\":\"payment.authorized\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String valid = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(razorpay.verifySignature(body, valid)).isTrue();
        assertThat(razorpay.verifySignature(body + " ", valid)).isFalse();
        assertThat(razorpay.verifySignature(body, null)).isFalse();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("every call to Razorpay logs one line — method, path, status, time — and never the body")
    void callsAreLogged(CapturedOutput output) {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_A","amount":123450,"currency":"INR","status":"created"}"""));
        routes.put("/v1/payments/pay_forged", new Canned(400, "{}"));

        razorpay.createAuthorization(new AuthorizationRequest(
                "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42"));
        assertThatThrownBy(() -> razorpay.fetchPayment("pay_forged"));

        assertThat(output.getOut()).containsPattern("Razorpay POST /v1/orders → 200 in \\d+ ms");
        assertThat(output.getOut()).containsPattern("WARN.*Razorpay GET /v1/payments/pay_forged → 400 in \\d+ ms");
        // The request body carries the receipt and amount; neither belongs in a log.
        assertThat(output.getOut()).doesNotContain("order-42").doesNotContain("123450");
    }

    @Test
    @DisplayName("a payment carries how it was paid: a card's last four, a wallet's name, nothing longer")
    void paymentCarriesItsMethod() {
        routes.put("/v1/payments/pay_card", new Canned(200, """
                {"id":"pay_card","order_id":"order_A","status":"authorized","amount":50000,
                 "method":"card","card":{"last4":"1007","network":"Visa"}}"""));
        routes.put("/v1/payments/pay_upi", new Canned(200, """
                {"id":"pay_upi","order_id":"order_A","status":"authorized","amount":50000,
                 "method":"upi","vpa":"someone@okbank"}"""));
        routes.put("/v1/payments/pay_wallet", new Canned(200, """
                {"id":"pay_wallet","order_id":"order_A","status":"authorized","amount":50000,
                 "method":"wallet","wallet":"paytm"}"""));
        routes.put("/v1/payments/pay_odd", new Canned(200, """
                {"id":"pay_odd","order_id":"order_A","status":"authorized","amount":50000,
                 "method":"card","card":{"last4":"4111111111111111"}}"""));

        var card = razorpay.fetchPayment("pay_card");
        var upi = razorpay.fetchPayment("pay_upi");

        assertThat(card.method()).isEqualTo("card");
        assertThat(card.methodDetail()).isEqualTo("1007");
        assertThat(upi.method()).isEqualTo("upi");
        // A UPI address is not needed and not ours to keep.
        assertThat(upi.methodDetail()).isNull();
        assertThat(razorpay.fetchPayment("pay_wallet").methodDetail()).isEqualTo("paytm");
        assertThat(razorpay.fetchPayment("pay_odd").methodDetail())
                .describedAs("only the shape of four digits is ever kept").isNull();
    }

    @Test
    @DisplayName("inspect reads the method, the fee (which includes tax), what was refunded, and whether it was captured")
    void inspectReadsMethodFeeAndAmountRefunded() {
        routes.put("/v1/payments/pay_A", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"refunded","captured":true,"amount":50000,
                 "amount_refunded":50000,"refund_status":"full","method":"upi","fee":1180,"tax":180,
                 "created_at":1790000000}"""));

        var facts = razorpay.inspect("pay_A");

        assertThat(facts.providerOrderId()).isEqualTo("order_A");
        assertThat(facts.status()).isEqualTo(ProviderPaymentStatus.REFUNDED);
        assertThat(facts.captured()).isTrue();
        assertThat(facts.amount()).isEqualByComparingTo("500.00");
        assertThat(facts.amountRefunded()).isEqualByComparingTo("500.00");
        assertThat(facts.method()).isEqualTo("upi");
        // Razorpay's `fee` already includes the GST in `tax` (1000 + 180 of GST = 1180). Adding
        // tax again reported 13.60, and overstated what a cancelled order cost by the GST.
        assertThat(facts.fee()).describedAs("Razorpay's fee alone: it includes the tax").isEqualByComparingTo("11.80");
        assertThat(facts.createdAt()).isEqualTo(java.time.Instant.ofEpochSecond(1790000000L));
    }

    @Test
    @DisplayName("inspect: the fee is Razorpay's `fee` alone; a payment with only a tax figure has no fee to report")
    void feeIsNotDoubledByTax() {
        routes.put("/v1/payments/pay_fee", new Canned(200, """
                {"id":"pay_fee","order_id":"order_A","status":"captured","captured":true,"amount":100000,
                 "amount_refunded":0,"method":"upi","fee":236,"tax":36}"""));
        routes.put("/v1/payments/pay_notax", new Canned(200, """
                {"id":"pay_notax","order_id":"order_A","status":"captured","captured":true,"amount":100000,
                 "amount_refunded":0,"method":"upi","fee":200}"""));
        routes.put("/v1/payments/pay_taxonly", new Canned(200, """
                {"id":"pay_taxonly","order_id":"order_A","status":"captured","captured":true,"amount":100000,
                 "amount_refunded":0,"method":"upi","tax":36}"""));

        assertThat(razorpay.inspect("pay_fee").fee()).isEqualByComparingTo("2.36");
        assertThat(razorpay.inspect("pay_notax").fee()).isEqualByComparingTo("2.00");
        assertThat(razorpay.inspect("pay_taxonly").fee()).isNull();
    }

    @Test
    @DisplayName("inspect: a payment read without amount_refunded (or amount) is unreadable, never 'nothing was refunded': it would pass that half of the proof in silence")
    void inspectWithoutWhatWasRefundedIsUnreadable() {
        routes.put("/v1/payments/pay_nofield", new Canned(200, """
                {"id":"pay_nofield","order_id":"order_A","status":"captured","captured":true,"amount":50000,"method":"upi"}"""));
        routes.put("/v1/payments/pay_null", new Canned(200, """
                {"id":"pay_null","order_id":"order_A","status":"captured","captured":true,"amount":50000,"amount_refunded":null}"""));
        routes.put("/v1/payments/pay_text", new Canned(200, """
                {"id":"pay_text","order_id":"order_A","status":"captured","captured":true,"amount":50000,"amount_refunded":"lots"}"""));
        routes.put("/v1/payments/pay_noamount", new Canned(200, """
                {"id":"pay_noamount","order_id":"order_A","status":"captured","captured":true,"amount_refunded":0}"""));

        for (String name : List.of("nofield", "null", "text", "noamount")) {
            assertThatThrownBy(() -> razorpay.inspect("pay_" + name)).describedAs(name)
                    .isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                        assertThat(ex.kind()).isEqualTo(com.costonomy.mp.payment.provider.ProviderFailureKind.AMBIGUOUS);
                        assertThat(ex.isNotFound()).isFalse();
                    });
        }
    }

    @Test
    @DisplayName("inspect: a hold nobody captured has no fee, and a missing 'captured' is read as taken, never as a hold")
    void inspectIsConservativeAboutCapture() {
        routes.put("/v1/payments/pay_hold", new Canned(200, """
                {"id":"pay_hold","order_id":"order_A","status":"authorized","captured":false,"amount":50000,
                 "amount_refunded":0,"method":"upi"}"""));
        routes.put("/v1/payments/pay_unsure", new Canned(200, """
                {"id":"pay_unsure","order_id":"order_A","status":"refunded","amount":50000,"amount_refunded":50000}"""));

        var hold = razorpay.inspect("pay_hold");
        assertThat(hold.status()).isEqualTo(ProviderPaymentStatus.AUTHORIZED);
        assertThat(hold.captured()).isFalse();
        assertThat(hold.fee()).isNull();
        assertThat(razorpay.inspect("pay_unsure").captured()).isTrue();
    }

    @Test
    @DisplayName("Razorpay 'refunded' is a returned hold only when it says captured=false; otherwise it is a refunded payment")
    void refundedIsTwoThings() {
        routes.put("/v1/payments/pay_expired", new Canned(200, """
                {"id":"pay_expired","order_id":"order_A","status":"refunded","captured":false,"amount":50000,"method":"upi"}"""));
        routes.put("/v1/payments/pay_taken", new Canned(200, """
                {"id":"pay_taken","order_id":"order_A","status":"refunded","captured":true,"amount":50000,"method":"upi"}"""));
        routes.put("/v1/payments/pay_silent", new Canned(200, """
                {"id":"pay_silent","order_id":"order_A","status":"refunded","amount":50000}"""));

        assertThat(razorpay.fetchPayment("pay_expired").status()).isEqualTo(ProviderPaymentStatus.RELEASED);
        assertThat(razorpay.fetchPayment("pay_taken").status()).isEqualTo(ProviderPaymentStatus.REFUNDED);
        // Silence is read as taken: nothing is called released that may have been debited.
        assertThat(razorpay.fetchPayment("pay_silent").status()).isEqualTo(ProviderPaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("a refund sends our receipt, notes and speed, and still sends the idempotency header")
    void refundSendsReceiptNotesAndSpeed() throws IOException {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, """
                {"id":"rfnd_A","amount":20000,"status":"pending"}"""));

        razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7",
                new RefundOptions("mandi-refund-7",
                        Map.of("mandi_refund_id", "7", "mandi_payment_id", "3", "purpose", "cancellation"),
                        "normal"));

        var request = only("/v1/payments/pay_A/refund");
        JsonNode body = request.json();
        assertThat(body.get("amount").asLong()).isEqualTo(20000);
        assertThat(body.get("receipt").asText()).isEqualTo("mandi-refund-7");
        assertThat(body.at("/notes/mandi_refund_id").asText()).isEqualTo("7");
        assertThat(body.at("/notes/mandi_payment_id").asText()).isEqualTo("3");
        assertThat(body.at("/notes/purpose").asText()).isEqualTo("cancellation");
        assertThat(body.get("speed").asText()).isEqualTo("normal");
        assertThat(request.headers()).containsEntry("x-refund-idempotency", "mandi-refund-7");
    }

    @Test
    @DisplayName("instant refund is only asked for when asked for; without options nothing extra is sent")
    void refundSpeedIsOptIn() throws IOException {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, """
                {"id":"rfnd_A","amount":20000,"status":"processed"}"""));

        razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none());

        JsonNode body = only("/v1/payments/pay_A/refund").json();
        assertThat(body.has("speed")).isFalse();
        assertThat(body.has("receipt")).isFalse();
        assertThat(body.has("notes")).isFalse();
    }

    @Test
    @DisplayName("an order's hold expires when the configured limit says, not at a hard-coded five days")
    void orderCarriesTheConfiguredHoldLimit() throws IOException {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_A","amount":123450,"currency":"INR","status":"created"}"""));

        razorpay.createAuthorization(new AuthorizationRequest(
                "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42"));

        assertThat(only("/v1/orders").json().at("/payment/capture_options/manual_expiry_period").asInt())
                .isEqualTo(4320);
    }

    @Test
    @DisplayName("F2: a lookup Razorpay answers 404, 429, 401 or 403 is told apart; an outage is a retryable wait")
    void inspectTellsRefusalsApart() {
        routes.put("/v1/payments/pay_404", new Canned(404, "{}"));
        routes.put("/v1/payments/pay_429", new Canned(429, "{}"));
        routes.put("/v1/payments/pay_401", new Canned(401, "{}"));
        routes.put("/v1/payments/pay_403", new Canned(403, "{}"));
        routes.put("/v1/payments/pay_503", new Canned(503, "{}"));

        assertThatThrownBy(() -> razorpay.inspect("pay_404")).isInstanceOfSatisfying(PaymentProviderException.class,
                ex -> assertThat(ex.isNotFound()).isTrue());
        assertThatThrownBy(() -> razorpay.inspect("pay_429")).isInstanceOfSatisfying(PaymentProviderException.class,
                ex -> {
                    assertThat(ex.isRateLimited()).isTrue();
                    assertThat(ex.isNotFound()).isFalse();
                });
        assertThatThrownBy(() -> razorpay.inspect("pay_401")).isInstanceOfSatisfying(PaymentProviderException.class,
                ex -> assertThat(ex.isCredentialsRefused()).isTrue());
        assertThatThrownBy(() -> razorpay.inspect("pay_403")).isInstanceOfSatisfying(PaymentProviderException.class,
                ex -> assertThat(ex.isCredentialsRefused()).isTrue());
        assertThatThrownBy(() -> razorpay.inspect("pay_503")).isInstanceOfSatisfying(PaymentProviderException.class,
                ex -> {
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.isNotFound()).isFalse();
                });
    }

    @Test
    @DisplayName("F3: the hold the caller chose is what is sent and what is reported back, to be stored with the payment")
    void orderCarriesTheHoldTheCallerChose() throws IOException {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_A","amount":123450,"currency":"INR","status":"created"}"""));

        var intent = razorpay.createAuthorization(new AuthorizationRequest(
                "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42", 1440));

        assertThat(only("/v1/orders").json().at("/payment/capture_options/manual_expiry_period").asInt())
                .isEqualTo(1440);
        assertThat(intent.holdMinutes()).isEqualTo(1440);
    }

    @Test
    @DisplayName("F3: with no hold asked for, the adapter's own setting is sent and reported back")
    void orderReportsTheDefaultHold() {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_A","amount":123450,"currency":"INR","status":"created"}"""));

        var intent = razorpay.createAuthorization(new AuthorizationRequest(
                "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42"));

        assertThat(intent.holdMinutes()).isEqualTo(4320);
    }

    @Test
    @DisplayName("F3: a hold outside Razorpay's range is refused before any call is made")
    void holdOutsideTheRangeIsRefusedBeforeTheCall() {
        for (int minutes : new int[] {0, 11, 7201}) {
            assertThatThrownBy(() -> razorpay.createAuthorization(new AuthorizationRequest(
                    "order-42", new BigDecimal("1234.50"), "INR", "Mandi order 42", "auth-order-42", minutes)))
                    .isInstanceOf(PaymentProviderException.class)
                    .hasMessageContaining("outside Razorpay's range");
        }
        assertThat(seen).describedAs("nothing was sent").isEmpty();
    }

    @Test
    @DisplayName("a hold limit outside Razorpay's range stops the adapter being built")
    void holdLimitOutsideRazorpaysRangeRefused() {
        for (int minutes : new int[] {0, 11, 7201}) {
            assertThatThrownBy(() -> new RazorpayPaymentProvider("http://localhost:1", "rzp_test_key",
                    "rzp_test_secret", WEBHOOK_SECRET, minutes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manual-expiry-minutes");
        }
        assertThat(new RazorpayPaymentProvider("http://localhost:1", "rzp_test_key",
                "rzp_test_secret", WEBHOOK_SECRET, 12)).isNotNull();
        assertThat(new RazorpayPaymentProvider("http://localhost:1", "rzp_test_key",
                "rzp_test_secret", WEBHOOK_SECRET, 7200)).isNotNull();
    }

    @Test
    @DisplayName("a capture of an expired payment is a refusal, not a capture: the fetch says it was returned")
    void captureOfExpiredPaymentIsNotCapture() {
        routes.put("/v1/payments/pay_A/capture", new Canned(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"payment status should be authorized"}}"""));
        routes.put("/v1/payments/pay_A", new Canned(200, """
                {"id":"pay_A","order_id":"order_A","status":"refunded","captured":false,"amount":50000}"""));

        assertThatThrownBy(() -> razorpay.capture("pay_A", new BigDecimal("500.00"), "cancel-capture-1"))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        ex -> assertThat(ex.isRetryable()).isFalse());
    }

    @Test
    @DisplayName("finding an order's payment finds one Razorpay returned unused, so it is not mistaken for none")
    void findByOrderIncludesAReturnedHold() {
        routes.put("/v1/orders/order_A/payments", new Canned(200, """
                {"entity":"collection","count":1,"items":[
                  {"id":"pay_expired","order_id":"order_A","status":"refunded","captured":false,"amount":50000}]}"""));

        assertThat(razorpay.findPaymentForOrder("order_A")).hasValueSatisfying(
                payment -> assertThat(payment.status()).isEqualTo(ProviderPaymentStatus.RELEASED));
    }

    // ── Refund refusals and the refund list (D-110) ──────────────────────

    private static String refusal(String description) {
        return "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"" + description + "\"}}";
    }

    private ProviderFailureKind refundRefusedAs(int status, String body) {
        routes.put("/v1/payments/pay_A/refund", new Canned(status, body));
        try {
            razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none());
        } catch (PaymentProviderException ex) {
            return ex.kind();
        }
        throw new AssertionError("the refund should have been refused");
    }

    @Test
    @DisplayName("D-110: a refusal of a refund is classified by what it says about whether money moved")
    void refundRefusalIsClassified() {
        assertThat(refundRefusedAs(404, "{}")).isEqualTo(ProviderFailureKind.PAYMENT_UNKNOWN);
        assertThat(refundRefusedAs(400, refusal("The id provided does not exist"))).isEqualTo(ProviderFailureKind.PAYMENT_UNKNOWN);
        assertThat(refundRefusedAs(400, refusal("The payment has been fully refunded already"))).isEqualTo(ProviderFailureKind.ALREADY_REFUNDED);
        assertThat(refundRefusedAs(400, refusal("The payment status should be captured for action to be taken"))).isEqualTo(ProviderFailureKind.NOT_CAPTURED);
        assertThat(refundRefusedAs(400, refusal("The refund amount provided is greater than the refundable amount"))).isEqualTo(ProviderFailureKind.OVER_REFUND);
        assertThat(refundRefusedAs(400, refusal("Your account does not have enough balance to carry out the refund operation"))).isEqualTo(ProviderFailureKind.INSUFFICIENT_BALANCE);
        assertThat(refundRefusedAs(400, refusal("Refund is not allowed after the refund window"))).isEqualTo(ProviderFailureKind.WINDOW_PASSED);
        assertThat(refundRefusedAs(400, refusal("Something we have never seen"))).isEqualTo(ProviderFailureKind.REJECTED_OTHER);
        assertThat(refundRefusedAs(400, "not json at all")).isEqualTo(ProviderFailureKind.REJECTED_OTHER);
        // The key was used with a different body: a refund with our key may exist. Not a definite refusal.
        assertThat(refundRefusedAs(400, refusal("Idempotency key already used with different request"))).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        assertThat(refundRefusedAs(401, "{}")).isEqualTo(ProviderFailureKind.CONFIG);
        assertThat(refundRefusedAs(403, "{}")).isEqualTo(ProviderFailureKind.CONFIG);
        assertThat(refundRefusedAs(429, "{}")).isEqualTo(ProviderFailureKind.THROTTLED);
        assertThat(refundRefusedAs(500, "{}")).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        assertThat(refundRefusedAs(503, "{}")).isEqualTo(ProviderFailureKind.AMBIGUOUS);
    }

    @Test
    @DisplayName("D-110: an over-refund is never read as our account's balance, and a 408 or 409 is ambiguous")
    void overRefundIsNotInsufficientBalance() {
        // Both contain "balance"; read as our account's balance they would trip the withdrawal breaker and be
        // filed as ours to fix, when they say the payment cannot take this much.
        assertThat(refundRefusedAs(400, refusal("Refund amount exceeds the refundable balance"))).isEqualTo(ProviderFailureKind.OVER_REFUND);
        assertThat(refundRefusedAs(400, refusal("The refund amount is greater than the unrefunded balance of the payment"))).isEqualTo(ProviderFailureKind.OVER_REFUND);
        assertThat(refundRefusedAs(400, refusal("Your account does not have enough balance to carry out the refund operation"))).isEqualTo(ProviderFailureKind.INSUFFICIENT_BALANCE);
        assertThat(refundRefusedAs(400, refusal("Insufficient funds"))).isEqualTo(ProviderFailureKind.INSUFFICIENT_BALANCE);
        // A timeout or a conflict with a request still being handled: the provider may well have acted.
        assertThat(refundRefusedAs(408, "{}")).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        assertThat(refundRefusedAs(409, "{}")).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        // Any other 4xx it answered is still a definite refusal, which is then checked against its list.
        assertThat(refundRefusedAs(422, "{}")).isEqualTo(ProviderFailureKind.REJECTED_OTHER);
    }

    @Test
    @DisplayName("D-110: a server that never answers is ambiguous, never a definite refusal")
    void refundTimeoutIsAmbiguous() throws Exception {
        // A port with nothing listening: connection refused, which is as unknown as a timeout.
        var dead = new RazorpayPaymentProvider("http://127.0.0.1:1", "rzp_test_key", "rzp_test_secret", WEBHOOK_SECRET, 4320);
        assertThatThrownBy(() -> dead.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none()))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(ProviderFailureKind.AMBIGUOUS);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("D-110: a 200 that carries no refund id is ambiguous: it says nothing about whether a refund was made")
    void refundWithoutAnIdIsAmbiguous() {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, "{}"));
        assertThatThrownBy(() -> razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none()))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(ProviderFailureKind.AMBIGUOUS);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("D-110: a refund Razorpay made and reports failed carries its id and a failure code")
    void refundReportedFailedKeepsItsId() {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, """
                {"id":"rfnd_F","payment_id":"pay_A","amount":20000,"status":"failed"}"""));
        var refund = razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none());
        assertThat(refund.status()).isEqualTo(ProviderRefundStatus.FAILED);
        assertThat(refund.providerRefundId()).isEqualTo("rfnd_F");
        assertThat(refund.failureCode()).isEqualTo("PROVIDER_FAILED");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("D-110: the provider's wording is kept to 120 printable characters, and the body is never logged")
    void refusalTextIsShortAndNotLogged(CapturedOutput output) {
        String long1 = "x".repeat(300);
        routes.put("/v1/payments/pay_A/refund", new Canned(400,
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"" + long1 + "\\u0000\",\"contact\":\"+919876500000\","
                        + "\"email\":\"buyer@example.com\"}}"));

        assertThatThrownBy(() -> razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7", RefundOptions.none()))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> {
                    assertThat(ex.description()).hasSize(120).matches("[ -~]+");
                    assertThat(ex.getMessage()).doesNotContain("xxxx").doesNotContain("919876500000");
                });
        assertThat(output.getAll()).doesNotContain("919876500000").doesNotContain("buyer@example.com")
                .doesNotContain("x".repeat(121));
    }

    @Test
    @DisplayName("D-110: listing a payment's refunds reads receipt, notes, status and amount, and asks for the full page")
    void listRefundsMatchesReceipt() {
        routes.put("/v1/payments/pay_A/refunds", new Canned(200, """
                {"entity":"collection","count":3,"items":[
                  {"id":"rfnd_1","amount":20000,"status":"processed","receipt":"mandi-refund-7","notes":{"mandi_refund_id":"7"},"created_at":1780000000},
                  {"id":"rfnd_2","amount":5000,"status":"pending","receipt":null,"notes":[]},
                  {"id":"rfnd_3","amount":100,"status":"failed","notes":{"mandi_refund_id":"9"}}]}"""));

        var listed = razorpay.listRefunds("pay_A");

        assertThat(listed).hasSize(3);
        assertThat(listed.get(0).receipt()).isEqualTo("mandi-refund-7");
        assertThat(listed.get(0).mandiRefundId()).isEqualTo("7");
        assertThat(listed.get(0).amount()).isEqualByComparingTo("200.00");
        assertThat(listed.get(0).status()).isEqualTo(ProviderRefundStatus.COMPLETED);
        assertThat(listed.get(0).createdAt()).isNotNull();
        assertThat(listed.get(1).receipt()).isNull();
        assertThat(listed.get(1).status()).isEqualTo(ProviderRefundStatus.PENDING);
        assertThat(listed.get(2).mandiRefundId()).isEqualTo("9");
        assertThat(listed.get(2).status()).isEqualTo(ProviderRefundStatus.FAILED);
        var request = seen.stream().filter(r -> r.path().equals("/v1/payments/pay_A/refunds")).findFirst().orElseThrow();
        assertThat(request.method()).isEqualTo("GET");
    }

    @Test
    @DisplayName("D-110: a list that cannot be read, or a payment Razorpay does not know, is never read as an empty list")
    void listRefundsNeverPretendsToBeEmpty() {
        routes.put("/v1/payments/pay_bad/refunds", new Canned(200, "{}"));
        assertThatThrownBy(() -> razorpay.listRefunds("pay_bad"))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> assertThat(ex.isRetryable()).isTrue());
        routes.put("/v1/payments/pay_500/refunds", new Canned(500, "{}"));
        assertThatThrownBy(() -> razorpay.listRefunds("pay_500"))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> assertThat(ex.isRetryable()).isTrue());
        routes.put("/v1/payments/pay_none/refunds", new Canned(400, refusal("The id provided does not exist")));
        assertThatThrownBy(() -> razorpay.listRefunds("pay_none"))
                .isInstanceOfSatisfying(PaymentProviderException.class, ex -> assertThat(ex.isNotFound()).isTrue());
    }

    @DisplayName("a top-up asks for automatic capture, never manual, and sends paise exactly")
    void topUpOrderIsAutomaticCapture() throws IOException {
        routes.put("/v1/orders", new Canned(200, """
                {"id":"order_T","amount":1010,"currency":"INR","status":"created"}"""));

        var intent = razorpay.createAuthorization(new AuthorizationRequest(
                "topup-7", new BigDecimal("10.10"), "INR", "Mandi wallet top-up", "topup-7", true));

        assertThat(intent.providerOrderId()).isEqualTo("order_T");
        JsonNode sent = only("/v1/orders").json();
        assertThat(sent.get("amount").asLong()).isEqualTo(1010);
        assertThat(sent.at("/payment/capture").asText()).isEqualTo("automatic");
        assertThat(sent.at("/payment/capture_options/manual_expiry_period").isMissingNode()).isTrue();
        assertThat(sent.get("receipt").asText()).isEqualTo("topup-7");
    }

    @Test
    @DisplayName("amounts a top-up can hold convert to paise without a float in sight")
    void paiseAreExact() throws IOException {
        routes.put("/v1/orders", new Canned(200, "{\"id\":\"order_P\"}"));

        for (String rupees : List.of("0.29", "10.10", "1234.57", "99999.99", "100000.00")) {
            seen.clear();
            razorpay.createAuthorization(new AuthorizationRequest(
                    "topup-1", new BigDecimal(rupees), "INR", "t", "k", true));
            assertThat(only("/v1/orders").json().get("amount").asLong())
                    .describedAs(rupees).isEqualTo(new BigDecimal(rupees).movePointRight(2).longValueExact());
        }
    }

    @Test
    @DisplayName("the checkout signature is HMAC-SHA256 of order|payment under the API secret, and only that")
    void checkoutSignature() throws Exception {
        String valid = hmac("rzp_test_secret", "order_A|pay_A");

        assertThat(razorpay.verifyCheckoutSignature("order_A", "pay_A", valid)).isTrue();
        assertThat(razorpay.verifyCheckoutSignature("order_A", "pay_B", valid)).isFalse();
        assertThat(razorpay.verifyCheckoutSignature("order_B", "pay_A", valid)).isFalse();
        assertThat(razorpay.verifyCheckoutSignature("order_A", "pay_A", valid + "0")).isFalse();
        assertThat(razorpay.verifyCheckoutSignature("order_A", "pay_A", null)).isFalse();
        assertThat(razorpay.verifyCheckoutSignature(null, "pay_A", valid)).isFalse();
        // The webhook secret signs webhooks, not checkouts: a signature made with it must not pass.
        assertThat(razorpay.verifyCheckoutSignature("order_A", "pay_A",
                hmac(WEBHOOK_SECRET, "order_A|pay_A"))).isFalse();
    }

    private static String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private Seen only(String path) {
        var matching = seen.stream().filter(request -> request.path().equals(path)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }
}
