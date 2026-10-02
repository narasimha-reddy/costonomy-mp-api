package com.costonomy.mp.payment;

import com.costonomy.mp.payment.provider.PaymentProvider.AuthorizationRequest;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;
import com.costonomy.mp.payment.provider.PaymentProviderException;
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
                "rzp_test_key", "rzp_test_secret", WEBHOOK_SECRET);
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
    @DisplayName("a refund sends the idempotency header Razorpay actually reads")
    void refundIsIdempotent() {
        routes.put("/v1/payments/pay_A/refund", new Canned(200, """
                {"id":"rfnd_A","amount":20000,"status":"processed"}"""));

        var refund = razorpay.refund("pay_A", new BigDecimal("200.00"), "mandi-refund-7");

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
                "rzp_test_secret", " "))
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

    private Seen only(String path) {
        var matching = seen.stream().filter(request -> request.path().equals(path)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }
}
