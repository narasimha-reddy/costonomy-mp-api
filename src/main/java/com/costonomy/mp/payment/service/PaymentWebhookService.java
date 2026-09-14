package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.domain.PaymentWebhookEvent;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider webhooks. Doc 04 §12, doc 09 §5, doc 46.
 *
 * <p>Four requirements meet here, and each one is a way this can go wrong.
 *
 * <p><b>Verify first.</b> Doc 09 §5. An unverified body is not evidence of
 * anything, and acting on one would let anyone mark any payment captured by
 * posting JSON at us. Verification uses the <em>raw</em> bytes, because that is
 * what the signature covers.
 *
 * <p><b>Persist before acting.</b> The event is stored, then processed. If
 * processing fails we still have the provider's statement and can replay it; the
 * reverse order loses it.
 *
 * <p><b>Deduplicate on the provider's event id.</b> Providers retry, so the same
 * event arrives repeatedly. A unique constraint decides it — a check-then-act
 * would race with the very retry it is meant to catch.
 *
 * <p><b>Handle out-of-order delivery.</b> Doc 46: a capture event can arrive before
 * the authorisation it follows. {@code PaymentService.applyProviderState} refuses
 * transitions that would move a payment backwards, so a late event is recorded and
 * ignored rather than applied.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private final PaymentWebhookStore store;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final OrderReleaseService orderRelease;
    private final PaymentProvider provider;
    private final ObjectMapper json;

    /**
     * Take a webhook.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> Each step commits on its
     * own: the event is stored, then the payment is reconciled, then the outcome
     * is written back. Wrapping the three in one transaction deadlocked against
     * itself — the store's {@code REQUIRES_NEW} write of the event's outcome waited
     * on the {@code payment} row the enclosing transaction had just locked and not
     * yet committed, and the provider got a 500 fifty seconds later. This is the
     * same shape as {@code PaymentJobs.reconcileStale}, which reaches the same
     * state by the same calls with no enclosing transaction either.
     *
     * @param rawBody exactly as received — the signature is over these bytes
     * @return true if this call processed it; false if it was a duplicate
     */
    public boolean handle(String rawBody, String signatureHeader) {
        if (!provider.verifySignature(rawBody, signatureHeader)) {
            // Logged without the body: an unverified payload is attacker-controlled
            // and should not be written into our logs verbatim.
            log.warn("Rejected a payment webhook with an invalid signature");
            throw new BusinessException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        JsonNode payload;
        try {
            payload = json.readTree(rawBody);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }

        String eventId = text(payload, "id", "event_id");
        String eventType = text(payload, "event", "type", "event_type");
        if (eventId == null || eventType == null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }

        var event = new PaymentWebhookEvent();
        event.setProvider(provider.name());
        event.setProviderEventId(eventId);
        event.setEventType(eventType);
        event.setPayload(rawBody);
        event.setSignatureVerified(true);
        event.setOccurredAt(occurredAt(payload));
        event.setStatus("RECEIVED");

        try {
            event = store.record(event);
        } catch (DataIntegrityViolationException ex) {
            // uk_webhook_provider_event: a retry of an event we already have.
            // Reporting success is correct — the provider's question is "did you
            // receive this", and we did. A non-200 makes them retry it forever.
            log.debug("Duplicate webhook {} ignored", eventId);
            return false;
        }

        try {
            process(event, payload);
            event.setStatus("PROCESSED");
            event.setProcessedAt(Instant.now());
        } catch (RuntimeException ex) {
            // The event stays stored with its error. Replaying it later is possible
            // precisely because it was persisted before being acted on.
            log.error("Could not process webhook {}", eventId, ex);
            event.setStatus("FAILED");
            event.setProcessingError(truncate(ex.getMessage()));
        }

        store.finish(event);
        return true;
    }

    private void process(PaymentWebhookEvent event, JsonNode payload) {
        String providerPaymentId = extractPaymentId(payload);
        if (providerPaymentId == null) {
            event.setStatus("IGNORED");
            return;
        }
        event.setProviderPaymentId(providerPaymentId);

        var payment = payments.findByProviderPaymentId(providerPaymentId)
                .or(() -> {
                    String orderId = extractOrderId(payload);
                    return orderId == null ? java.util.Optional.empty()
                            : payments.findByProviderOrderId(orderId);
                })
                .orElse(null);

        if (payment == null) {
            // An event about something we have no record of. Kept rather than
            // discarded: it is either a provider-side problem or ours, and either
            // way the record is what makes it diagnosable.
            log.warn("Webhook {} refers to unknown payment {}",
                    event.getProviderEventId(), providerPaymentId);
            event.setStatus("IGNORED");
            return;
        }

        event.setPaymentId(payment.getId());

        // Asking the provider rather than trusting the payload's amounts. The
        // signature proves the message came from them, not that it is still
        // current — and an out-of-order event describes a past state.
        var providerPayment = provider.fetchPayment(providerPaymentId);
        var updated = paymentService.applyProviderState(payment, providerPayment, "WEBHOOK");

        // Authorisation is what lets the order reach its supplier. Doing it here
        // means a customer who closes the app mid-checkout still gets their order
        // placed, because the webhook arrives regardless (doc 46).
        if (updated.getStatus().fundsSecured()) {
            orderRelease.releaseIfFunded(updated.getSupplierOrderId());
        } else if (updated.getStatus() == com.costonomy.mp.payment.domain.PaymentStatus.FAILED) {
            orderRelease.abandonUnfunded(updated.getSupplierOrderId(),
                    "Payment failed: " + String.valueOf(updated.getFailureCode()));
        }
    }

    // ── payload helpers ──────────────────────────────────────────────────

    /**
     * Find the payment id in a provider payload.
     *
     * <p>Tolerant of shape because providers nest differently and change over time;
     * a webhook we cannot parse is recorded as IGNORED rather than failing the
     * request, which would make the provider retry something we will never
     * understand.
     */
    private String extractPaymentId(JsonNode payload) {
        var direct = text(payload, "payment_id", "providerPaymentId");
        if (direct != null) {
            return direct;
        }
        var nested = payload.at("/payload/payment/entity/id");
        return nested.isMissingNode() || nested.isNull() ? null : nested.asText();
    }

    private String extractOrderId(JsonNode payload) {
        var direct = text(payload, "order_id", "providerOrderId");
        if (direct != null) {
            return direct;
        }
        var nested = payload.at("/payload/payment/entity/order_id");
        return nested.isMissingNode() || nested.isNull() ? null : nested.asText();
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field) && !node.get(field).asText().isBlank()) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    /**
     * When the provider says it happened.
     *
     * <p>Kept separate from when we received it: doc 46's out-of-order case is
     * exactly the situation where those two orderings disagree, and only the
     * provider's is meaningful.
     */
    private static Instant occurredAt(JsonNode payload) {
        if (payload.hasNonNull("created_at")) {
            try {
                return Instant.ofEpochSecond(payload.get("created_at").asLong());
            } catch (RuntimeException ex) {
                return Instant.now();
            }
        }
        return Instant.now();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** Only ever used to compare amounts; never to set one. */
    static BigDecimal amountOf(JsonNode node, String field) {
        return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null;
    }
}
