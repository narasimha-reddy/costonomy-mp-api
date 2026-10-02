package com.costonomy.mp.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final int holdMinutes;

    public RazorpayPaymentProvider(
            @Value("${costonomy.mp.razorpay.base-url:https://api.razorpay.com}") String baseUrl,
            @Value("${costonomy.mp.razorpay.key-id}") String keyId,
            @Value("${costonomy.mp.razorpay.key-secret}") String keySecret,
            @Value("${costonomy.mp.razorpay.webhook-secret}") String webhookSecret,
            // How long an authorised payment is held for capture before Razorpay
            // returns it. The same property sets how long we will hand goods over
            // against a hold (PaymentHoldPolicy): the two must never disagree, which
            // is how goods left against a hold that had already lapsed (D-109).
            @Value("${costonomy.mp.razorpay.manual-expiry-minutes:4320}") int holdMinutes) {

        // Refuse to start half-configured (D-101). A blank webhook secret made every
        // webhook fail closed with a stack trace per request; a blank key fails
        // every payment. Neither should wait for the first customer to find out.
        requireSet("costonomy.mp.razorpay.key-id", keyId);
        requireSet("costonomy.mp.razorpay.key-secret", keySecret);
        requireSet("costonomy.mp.razorpay.webhook-secret", webhookSecret);
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        if (holdMinutes < MIN_HOLD_MINUTES || holdMinutes > MAX_HOLD_MINUTES) {
            throw new IllegalStateException("costonomy.mp.razorpay.manual-expiry-minutes must be between "
                    + MIN_HOLD_MINUTES + " and " + MAX_HOLD_MINUTES + " (Razorpay's limits), not " + holdMinutes);
        }
        this.holdMinutes = holdMinutes;

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
        // The hold the caller chose, so what is stored on the payment is what was sent;
        // this provider's own setting only where none was asked for.
        int hold = request.holdMinutes() != null ? request.holdMinutes() : holdMinutes;
        if (hold < MIN_HOLD_MINUTES || hold > MAX_HOLD_MINUTES) {
            throw new PaymentProviderException("A hold of " + hold + " minutes is outside Razorpay's range of "
                    + MIN_HOLD_MINUTES + " to " + MAX_HOLD_MINUTES, false, "INVALID_HOLD");
        }
        JsonNode response = post("/v1/orders", Map.of(
                "amount", toPaise(request.amount()),
                "currency", request.currency(),
                "receipt", request.referenceId(),
                // Never auto-capture. Capture happens when the order is confirmed,
                // for the agreed amount (doc 01 §14). The expiry, in minutes, is
                // configuration (D-109): an uncaptured hold lapses back to the
                // customer when it runs out, which is also how a card release
                // works. Razorpay's own documents disagree on whether the longest
                // is three days or five, so the default is the shorter and the
                // same number bounds when we let goods leave.
                "payment", Map.of(
                        "capture", "manual",
                        "capture_options", Map.of(
                                "manual_expiry_period", hold,
                                "refund_speed", "normal"))), Map.of());

        return new AuthorizationIntent(
                response.get("id").asText(), request.amount(), request.currency(), keyId, hold);
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
                    case CAPTURED, AUTHORIZED, REFUNDED, RELEASED -> true;
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
            return capturedAs(toProviderPayment(response), amount);
        } catch (PaymentProviderException ex) {
            // A rate limit or refused keys mean the call was not looked at, so there is nothing to
            // second-guess and the lookup below would only meet the same answer. The caller
            // retries these (PaymentService.performCapture), and a retry that finds the capture
            // already taken is settled by the 400 path below.
            if (ex.isRetryable() || ex.isRateLimited() || ex.isCredentialsRefused()) {
                throw ex;
            }
            // Razorpay refuses a second capture with a 400. After a capture whose
            // response we lost, that refusal means it worked — and reading it as
            // a failure would mark a payment FAILED while the money sits captured.
            // So ask what the payment actually is before believing the 400.
            var current = fetchPayment(providerPaymentId);
            if (current.status() == ProviderPaymentStatus.CAPTURED) {
                log.info("Razorpay payment {} was already captured", providerPaymentId);
                return capturedAs(current, amount);
            }
            throw ex;
        }
    }

    /**
     * What we captured is what we asked to capture (D-101). Razorpay's payment
     * carries its full {@code amount} whatever was captured, and reading that as
     * the captured figure overwrote a partial capture with the whole
     * authorisation — no release of the remainder, and refunds allowed up to
     * money never taken.
     */
    private static ProviderPayment capturedAs(ProviderPayment payment, BigDecimal requested) {
        return new ProviderPayment(payment.providerPaymentId(), payment.providerOrderId(),
                payment.status(), payment.authorizedAmount(), requested,
                payment.failureCode(), payment.failureReason(),
                payment.method(), payment.methodDetail());
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
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey,
                                 RefundOptions options) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("amount", toPaise(amount));
        // Our reference, so a refund whose answer we lost can be found again at
        // Razorpay by it rather than by guessing on amount and time (D-109).
        if (options != null && options.receipt() != null) {
            body.put("receipt", options.receipt());
        }
        if (options != null && options.notes() != null && !options.notes().isEmpty()) {
            body.put("notes", options.notes());
        }
        // Sent as the caller chose, "normal" included: Razorpay's default is normal, so
        // saying it changes nothing, and it makes the request read the same on their
        // dashboard whichever way the setting points. "optimum" is the decision that
        // costs a fee (D-109), so it is only ever sent because the owner asked for it.
        if (options != null && options.speed() != null) {
            body.put("speed", options.speed());
        }
        JsonNode response = post("/v1/payments/" + providerPaymentId + "/refund", body,
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
    public ProviderPaymentFacts inspect(String providerPaymentId) {
        JsonNode response = get("/v1/payments/" + providerPaymentId);
        String status = response.path("status").asText("");
        // Whether the money was ever taken. Razorpay always says; where it does not,
        // "refunded" is assumed to have been captured, because the alternative reads
        // a taken-and-returned payment as a hold it gave back and stops looking.
        boolean captured = response.has("captured")
                ? response.path("captured").asBoolean()
                : "captured".equals(status) || "refunded".equals(status);
        return new ProviderPaymentFacts(
                response.path("id").asText(null),
                response.path("order_id").asText(null),
                statusOf(status),
                captured,
                toRupees(response.path("amount").asLong()),
                toRupees(response.path("amount_refunded").asLong()),
                methodOf(response),
                methodDetailOf(response),
                feeOf(response),
                response.hasNonNull("created_at")
                        ? java.time.Instant.ofEpochSecond(response.path("created_at").asLong()) : null);
    }

    @Override
    public ProviderRefund fetchRefund(String providerRefundId) {
        JsonNode response = get("/v1/refunds/" + providerRefundId);
        return new ProviderRefund(
                response.path("id").asText(null),
                refundStatus(response.path("status").asText("")),
                toRupees(response.path("amount").asLong()), null, null);
    }

    private static ProviderRefundStatus refundStatus(String status) {
        return switch (status) {
            case "processed" -> ProviderRefundStatus.COMPLETED;
            case "failed" -> ProviderRefundStatus.FAILED;
            default -> ProviderRefundStatus.PENDING;
        };
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
        var normalised = statusOf(status);
        // "Refunded" is two different things. Money that was taken and given back is
        // REFUNDED; an authorisation Razorpay returned itself because it was never
        // captured is RELEASED, which is how the rest of the system already speaks of
        // a hold that dropped. Only an explicit captured=false says the second: silence
        // is read as taken, so nothing is ever called released that may have been debited.
        if (normalised == ProviderPaymentStatus.REFUNDED
                && response.has("captured") && !response.path("captured").asBoolean()) {
            normalised = ProviderPaymentStatus.RELEASED;
        }
        return new ProviderPayment(
                response.path("id").asText(null),
                response.path("order_id").asText(null),
                normalised,
                toRupees(response.path("amount").asLong()),
                "captured".equals(status)
                        ? toRupees(response.path("amount").asLong()) : BigDecimal.ZERO,
                response.path("error_code").asText(null),
                response.path("error_description").asText(null),
                methodOf(response),
                methodDetailOf(response));
    }

    private static ProviderPaymentStatus statusOf(String status) {
        return switch (status) {
            case "authorized" -> ProviderPaymentStatus.AUTHORIZED;
            case "captured" -> ProviderPaymentStatus.CAPTURED;
            case "refunded" -> ProviderPaymentStatus.REFUNDED;
            case "failed" -> ProviderPaymentStatus.FAILED;
            default -> ProviderPaymentStatus.CREATED;
        };
    }

    /**
     * What Razorpay keeps on a capture, in rupees. Null until it says.
     *
     * <p>Its {@code fee} alone: Razorpay defines {@code fee} as the fee including GST, and
     * {@code tax} as the GST inside it (its own example is a fee of 1180 with tax of 180,
     * for a charge of 1000 and 180 of GST). Adding the two counted the GST twice.
     */
    private static BigDecimal feeOf(JsonNode response) {
        if (!response.hasNonNull("fee")) {
            return null;
        }
        return toRupees(response.path("fee").asLong());
    }

    /** Razorpay's {@code method}, lower-cased and limited to what fits and reads: a label, not free text. */
    private static String methodOf(JsonNode response) {
        String method = response.path("method").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        return method.matches("[a-z_]{1,32}") ? method : null;
    }

    /**
     * The only detail kept about how a payment was made: a card's last four digits, or a
     * provider wallet's name. The check is on the shape, so nothing longer than four digits
     * (a full number, should a response ever carry one) can be stored by mistake.
     */
    private static String methodDetailOf(JsonNode response) {
        String method = response.path("method").asText("");
        if ("card".equals(method)) {
            String last4 = response.path("card").path("last4").asText("");
            return last4.matches("\\d{4}") ? last4 : null;
        }
        if ("wallet".equals(method)) {
            String wallet = response.path("wallet").asText("");
            return wallet.matches("[A-Za-z0-9 _-]{1,32}") ? wallet : null;
        }
        return null;
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

    private static final ObjectMapper ERROR_BODY = new ObjectMapper();

    /**
     * Whether an HTTP 400 body is Razorpay's "that id does not exist": {@code error.code} is
     * BAD_REQUEST_ERROR and {@code error.description} says an id (or the payment, order or
     * refund) "does not exist" or "not found". Deliberately narrow. A 400 about the call
     * itself (an amount, a missing field, "The requested URL was not found on the server" for a
     * mistyped path) must not read as "the payment is unknown", because that decision sends a
     * customer's money to a person. A body that cannot be read is not that answer.
     */
    static boolean saysIdDoesNotExist(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] raw = response.getBody().readNBytes(8192);
            JsonNode error = ERROR_BODY.readTree(raw).path("error");
            return saysIdDoesNotExist(error.path("code").asText(""), error.path("description").asText(""));
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean saysIdDoesNotExist(String code, String description) {
        return "BAD_REQUEST_ERROR".equals(code)
                && description.toLowerCase(java.util.Locale.ROOT).matches(
                        "\\s*the\\s+(id|payment|order|refund)\\b[^.]{0,40}\\b(does not exist|not found)\\s*\\.?\\s*");
    }

    private JsonNode get(String path) {
        long started = System.nanoTime();
        try {
            var reply = client.get().uri(path).retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        // Asking again will not help, and reporting it as an outage would leave a
                        // refused request looking like something worth retrying.
                        int status = response.getStatusCode().value();
                        // Razorpay does not answer an id it does not know with 404: it answers HTTP
                        // 400, BAD_REQUEST_ERROR, "The id provided does not exist". That is the one
                        // answer that says something about the payment rather than the call, and a
                        // caller that only knew 404 would wait on it for ever (N1). Mapped to
                        // NOT_FOUND only when the body says exactly that; any other 400 keeps its code.
                        if (status == 400 && saysIdDoesNotExist(response)) {
                            throw new PaymentProviderException(
                                    "Razorpay does not know that id (HTTP 400: the id provided does not exist)",
                                    false, "NOT_FOUND");
                        }
                        throw new PaymentProviderException(
                                "Razorpay refused the lookup: " + response.getStatusCode(),
                                false, String.valueOf(status));
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
    private static void logCall(String method, String rawPath, String status, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        // The path can hold a payment id a client sent. Keep it to one token on
        // one line, as TraceScope does, so it cannot forge a line (D-101).
        String path = rawPath.replaceAll("[^A-Za-z0-9_./:\\-]", "_");
        if (path.length() > 120) {
            path = path.substring(0, 120) + "…";
        }
        if (status != null && status.startsWith("2")) {
            log.info("Razorpay {} {} → {} in {} ms", method, path, status, millis);
        } else {
            log.warn("Razorpay {} {} → {} in {} ms", method, path, status, millis);
        }
    }

    private static void requireSet(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set when payments run on Razorpay");
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
