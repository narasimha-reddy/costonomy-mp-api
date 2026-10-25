package com.costonomy.mp.payment.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund of a gateway payment that is not an order payment: a food delivery fee, or a supplier's direct payment of
 * their share of a delivery (D-204). Written BEFORE the gateway is asked and reconciled after, so a timeout, a crash or a
 * missing client confirmation can never leave a refund unrecorded or sent twice. The {@code idempotencyKey} is the
 * gateway receipt: it is how an unknown outcome is looked up.
 */
@Entity
@Table(name = "gateway_refund")
@Getter
@Setter
@NoArgsConstructor
public class GatewayRefund extends BaseEntity {

    public static final String FOOD_DELIVERY = "FOOD_DELIVERY";
    public static final String SUPPLIER_DELIVERY_FUNDING = "SUPPLIER_DELIVERY_FUNDING";

    public static final String REQUESTED = "REQUESTED";
    public static final String SENDING = "SENDING";
    /** Accepted by the gateway (its refund id is stored) but not yet completed: reconciled until COMPLETED or FAILED. */
    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String EXHAUSTED = "EXHAUSTED";
    public static final String AMBIGUOUS = "AMBIGUOUS";

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "provider_payment_id", nullable = false, length = 200)
    private String providerPaymentId;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 16)
    private String status = REQUESTED;

    @Column(name = "provider_refund_id", length = 200)
    private String providerRefundId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lookup_count", nullable = false)
    private int lookupCount;

    /**
     * How many sends the gateway has recorded as failed. Round 0 uses the idempotency key as the provider key and
     * receipt; a later round suffixes it, so the retry of a refund the gateway holds as failed is a new refund.
     */
    @Column(name = "send_round", nullable = false)
    private int sendRound;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "claim_token", length = 64)
    private String claimToken;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    /** The key the gateway deduplicates this send on (round 0 is the record's own key). */
    public String providerKey() {
        return sendRound == 0 ? idempotencyKey : idempotencyKey + "-r" + sendRound;
    }

    /** What a later check matches the gateway's own refund list against; the same as {@link #providerKey()}. */
    public String receipt() {
        return providerKey();
    }
}
