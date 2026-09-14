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
import java.util.Map;

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
 * auto-capture, and doc 01 §14 forbids it here: the amount to capture is not known
 * until the supplier says what they will accept. Orders are created with
 * {@code payment_capture: 0}.
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
                // Never auto-capture. The capture amount depends on what the
                // supplier accepts, which has not happened yet (doc 01 §14).
                "payment_capture", 0), request.idempotencyKey());

        return new AuthorizationIntent(
                response.get("id").asText(), request.amount(), request.currency(), keyId);
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        return toProviderPayment(get("/v1/payments/" + providerPaymentId));
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        JsonNode response = post("/v1/payments/" + providerPaymentId + "/capture",
                Map.of("amount", toPaise(amount), "currency", "INR"), idempotencyKey);
        return toProviderPayment(response);
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
        return new ProviderPayment(providerPaymentId, ProviderPaymentStatus.RELEASED,
                current.authorizedAmount(), BigDecimal.ZERO, null, null);
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        JsonNode response = post("/v1/payments/" + providerPaymentId + "/refund",
                Map.of("amount", toPaise(amount)), idempotencyKey);

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

    private JsonNode post(String path, Map<String, Object> body, String idempotencyKey) {
        try {
            return client.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    // Razorpay honours this, which is what makes a retry after a
                    // network failure safe. Ours alone would not be enough.
                    .header("X-Razorpay-Idempotency-Key", idempotencyKey)
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
                    .body(JsonNode.class);
        } catch (PaymentProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw PaymentProviderException.unreachable("Could not reach Razorpay", ex);
        }
    }

    private JsonNode get(String path) {
        try {
            return client.get().uri(path).retrieve().body(JsonNode.class);
        } catch (Exception ex) {
            throw PaymentProviderException.unreachable("Could not reach Razorpay", ex);
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
