package com.costonomy.mp.payment.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A provider webhook, stored before it is acted on. Doc 04 §12, doc 09 §5. */
@Entity
@Table(name = "payment_webhook_event")
@Getter
@Setter
@NoArgsConstructor
public class PaymentWebhookEvent extends BaseEntity {

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    /** Unique per provider. The deduplication key — providers retry. */
    @Column(name = "provider_event_id", nullable = false, length = 200)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "provider_payment_id", length = 200)
    private String providerPaymentId;

    @Column(name = "payment_id")
    private Long paymentId;

    /**
     * Verbatim. Never re-serialised — a signature is over exact bytes, and a
     * round-trip through a parser makes it unverifiable afterwards.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "signature_verified", nullable = false)
    private Boolean signatureVerified = false;

    /** RECEIVED, PROCESSED, IGNORED or FAILED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "RECEIVED";

    @Column(name = "processing_error", length = 1000)
    private String processingError;

    /** When the provider says it happened, which may be well before we saw it. */
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
