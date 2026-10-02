package com.costonomy.mp.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Razorpay. Doc 21, doc 09 §5.
 *
 * <p>Two things about this integration are worth knowing before changing it.
 *
 * <p><b>Razorpay works in paise, we work in rupees.</b> Every amount crossing this
 * boundary is converted, and conversion is the single most likely place to lose a
 * factor of a hundred — so it happens in {@link #toPaise} and {@link #toRupees}
 * and nowhere else.
 *
 * <p><b>Authorisation and capture are separate on purpose.</b> Razorpay can
 * auto-capture, and doc 01 §14 forbids it here: money is taken only once the
 * order is confirmed, and only for what was agreed. Orders are created with
 * {@code payment.capture = manual}, the documented form — the older
 * {@code payment_capture: 0} flag is not in the current Orders API, and if it
 * were ignored the account's dashboard default would decide, which may well be
 * automatic.
 *
 * <p><b>Razorpay has no idempotency key on orders or captures</b> — only on
 * refunds, as {@code X-Refund-Idempotency}. Orders are made safe by
 * {@code uk_payment_order} on our side; captures by treating "already captured"
 * as the success it is ({@link #capture}).
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.payment", havingValue = "RAZORPAY")
@Slf4j
public class RazorpayPaymentProvider implements PaymentProvider {

    private final RestClient client;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;

    public RazorpayPaymentProvider(
            @Value("${costonomy.mp.razorpay.base-url:https://api.razorpay.com}") String baseUrl,
            @Value("${costonomy.mp.razorpay.key-id}") String keyId,
            @Value("${costonomy.mp.razorpay.key-secret}") String keySecret,
            @Value("${costonomy.mp.razorpay.webhook-secret}") String webhookSecret) {

        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Bounded, because a hung gateway must not hold a request thread while a
        // restaurant waits on a checkout screen.
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(15).toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(keyId, keySecret))
                .build();
    }

    @Override
    public String name() {
        return "RAZORPAY";
    }

    @Override
    public String createAuthorizationPublicKey() {
        // The key id is publishable by design; the secret never leaves this class.
        return keyId;
    }

    @Override
    public AuthorizationIntent createAuthorization(AuthorizationRequest request) {
        JsonNode response = post("/v1/orders", Map.of(
                "amount", toPaise(request.amount()),
                "currency", request.currency(),
                "receipt", request.referenceId(),
                // Never auto-capture. Capture happens when the order is confirmed,
                // for the agreed amount (doc 01 §14). The expiry is Razorpay's
                // maximum, in minutes: an uncaptured hold lapses back to the
                // customer after five days, which is also how release works.
                "payment", Map.of(
                        "capture", "manual",
                        "capture_options", Map.of(
                                "manual_expiry_period", 7200,
                                "refund_speed", "normal"))), Map.of());

        return new AuthorizationIntent(
                response.get("id").asText(), request.amount(), request.currency(), keyId);
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        return toProviderPayment(get("/v1/payments/" + providerPaymentId));
    }

    @Override
    public Optional<ProviderPayment> findPaymentForOrder(String providerOrderId) {
        JsonNode items = get("/v1/orders/" + providerOrderId + "/payments").path("items");

        // Money taken beats money held beats money returned; a failed or
        // abandoned attempt is not an outcome (see the port). Several attempts
        // per order are normal — Razorpay lets the customer retry in checkout.
        return StreamSupport.stream(items.spliterator(), false)
                .map(this::toProviderPayment)
                .filter(payment -> switch (payment.status()) {
                    case CAPTURED, AUTHORIZED, REFUNDED -> true;
                    default -> false;
                })
                .min(Comparator.comparingInt(payment -> switch (payment.status()) {
                    case CAPTURED -> 0;
                    case AUTHORIZED -> 1;
                    default -> 2;
                }));
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        try {
            JsonNode response = post("/v1/payments/" + providerPaymentId + "/capture",
                    Map.of("amount", toPaise(amount), "currency", "INR"), Map.of());
            return toProviderPayment(response);
        } catch (PaymentProviderException ex) {
            if (ex.isRetryable()) {
                throw ex;
            }
            // Razorpay refuses a second capture with a 400. After a capture whose
            // response we lost, that refusal means it worked — and reading it as
            // a failure would mark a payment FAILED while the money sits captured.
            // So ask what the payment actually is before believing the 400.
            var current = fetchPayment(providerPaymentId);
            if (current.status() == ProviderPaymentStatus.CAPTURED) {
                log.info("Razorpay payment {} was already captured", providerPaymentId);
                return current;
            }
            throw ex;
        }
    }

    @Override
    public ProviderPayment release(String providerPaymentId, String idempotencyKey) {
        // Razorpay has no explicit void: an uncaptured authorisation lapses on its
        // own after the auto-refund window. Fetching records that we decided not to
        // capture, and reconciliation confirms the lapse — asking for a refund of
        // money never taken would be rejected, and would look like a reversal on
        // the customer's statement if it were not.
        log.info("Releasing Razorpay authorization {} by letting it lapse", providerPaymentId);
        var current = fetchPayment(providerPaymentId);
        return new ProviderPayment(providerPaymentId, current.providerOrderId(),
                ProviderPaymentStatus.RELEASED,
                current.authorizedAmount(), BigDecimal.ZERO, null, null);
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        JsonNode response = post("/v1/payments/" + providerPaymentId + "/refund",
                Map.of("amount", toPaise(amount)),
                // The only idempotency header Razorpay documents. It is what stops
                // a retried refund becoming a second refund (doc 10 §2).
                Map.of("X-Refund-Idempotency", idempotencyKey));

        return new ProviderRefund(
                response.get("id").asText(),
                switch (response.path("status").asText("")) {
                    case "processed" -> ProviderRefundStatus.COMPLETED;
                    case "failed" -> ProviderRefundStatus.FAILED;
                    default -> ProviderRefundStatus.PENDING;
                },
                toRupees(response.path("amount").asLong()), null, null);
    }

    @Override
    public boolean verifySignature(String rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));

            // Constant-time. A byte-by-byte comparison leaks how much of a forged
            // signature was correct, which is enough to construct a valid one.
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.error("Could not verify webhook signature", ex);
            return false;
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private ProviderPayment toProviderPayment(JsonNode response) {
        String status = response.path("status").asText("");
        return new ProviderPayment(
                response.path("id").asText(null),
                response.path("order_id").asText(null),
                switch (status) {
                    case "authorized" -> ProviderPaymentStatus.AUTHORIZED;
                    case "captured" -> ProviderPaymentStatus.CAPTURED;
                    case "refunded" -> ProviderPaymentStatus.REFUNDED;
                    case "failed" -> ProviderPaymentStatus.FAILED;
                    default -> ProviderPaymentStatus.CREATED;
                },
                toRupees(response.path("amount").asLong()),
                "captured".equals(status)
                        ? toRupees(response.path("amount").asLong()) : BigDecimal.ZERO,
                response.path("error_code").asText(null),
                response.path("error_description").asText(null));
    }

    private JsonNode post(String path, Map<String, Object> body, Map<String, String> headers) {
        long started = System.nanoTime();
        try {
            var reply = client.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    .headers(h -> headers.forEach(h::set))
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        // The provider refused. Retrying would be refused again.
                        throw new PaymentProviderException(
                                "Razorpay rejected the request: " + response.getStatusCode(),
                                false, String.valueOf(response.getStatusCode().value()));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        // We do not know whether they acted. Retryable — with the
                        // same idempotency key, which is what makes that safe.
                        throw new PaymentProviderException(
                                "Razorpay is unavailable: " + response.getStatusCode(),
                                true, String.valueOf(response.getStatusCode().value()));
                    })
                    .toEntity(JsonNode.class);
            logCall("POST", path, String.valueOf(reply.getStatusCode().value()), started);
            return reply.getBody();
        } catch (PaymentProviderException ex) {
            logCall("POST", path, ex.providerCode(), started);
            throw ex;
        } catch (Exception ex) {
            logCall("POST", path, "unreachable", started);
            throw PaymentProviderException.unreachable("Could not reach Razorpay", ex);
        }
    }

    private JsonNode get(String path) {
        long started = System.nanoTime();
        try {
            var reply = client.get().uri(path).retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        // An id Razorpay does not know. Asking again will not help,
                        // and reporting it as an outage would leave a bogus id
                        // looking like something worth retrying.
                        throw new PaymentProviderException(
                                "Razorpay refused the lookup: " + response.getStatusCode(),
                                false, String.valueOf(response.getStatusCode().value()));
                    })
                    .toEntity(JsonNode.class);
            logCall("GET", path, String.valueOf(reply.getStatusCode().value()), started);
            return reply.getBody();
        } catch (PaymentProviderException ex) {
            logCall("GET", path, ex.providerCode(), started);
            throw ex;
        } catch (Exception ex) {
            logCall("GET", path, "unreachable", started);
            throw PaymentProviderException.unreachable("Could not reach Razorpay", ex);
        }
    }

    /**
     * One line per call to Razorpay: what we asked, what came back, how long it
     * took (D-100). The path names only Razorpay's own ids; the body is never
     * logged, because it can carry a customer's contact details. This is the line
     * to quote to Razorpay support, and the one that shows a slow gateway before
     * the pool does.
     */
    private static void logCall(String method, String path, String status, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        if (status != null && status.startsWith("2")) {
            log.info("Razorpay {} {} → {} in {} ms", method, path, status, millis);
        } else {
            log.warn("Razorpay {} {} → {} in {} ms", method, path, status, millis);
        }
    }

    /** Rupees to paise. The one place this conversion happens. */
    private static long toPaise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();
    }

    /** Paise to rupees. The other one. */
    private static BigDecimal toRupees(long paise) {
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String basicAuth(String keyId, String keySecret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }
}
