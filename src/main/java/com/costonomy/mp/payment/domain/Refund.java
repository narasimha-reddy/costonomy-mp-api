package com.costonomy.mp.payment.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Money returned after capture. Doc 03 §7, doc 22. */
@Entity
@Table(name = "refund")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", nullable = false, length = 64)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "destination", nullable = false, length = 16)
    private RefundDestination destination = RefundDestination.ORIGINAL;

    @Column(name = "note", length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "provider_refund_id", length = 200)
    private String providerRefundId;

    /** Sends to the provider so far. Stops automatic retries at a limit (D-101). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Uniquely indexed. Doc 22: refunds must be idempotent, and a duplicate is
     * money leaving twice — the constraint decides it rather than a check that
     * would race with the retry it is meant to catch.
     */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Why the last send failed, in our words (D-110). Cleared when the refund completes. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_kind", length = 32)
    private com.costonomy.mp.payment.provider.ProviderFailureKind failureKind;

    /**
     * When the refund was first claimed for sending. From then on the provider may hold a refund of ours,
     * so every later send is preceded by a look for it (D-110).
     */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * The last time it was claimed for sending. The age of an ambiguous refund is counted from here: unlike
     * {@code updated_at}, an approval, a verification or a note does not move it.
     */
    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    /**
     * Why the system sent it to a person, where the provider's refusal no longer says so (D-110):
     * {@code REFUNDED_ANOTHER_WAY}, {@code CONTRADICTED_REFUSAL}, {@code PAYMENT_GONE} or {@code NOT_A_WITHDRAWAL}.
     * Two values are written by people, not by the system: {@code FOREIGN_NOT_THIS_PART} (two people recorded that
     * the provider refunds in {@link #reviewRef} are not this part's) and {@code COMPLETED_BY_OTHER_REFUND} (a person
     * closed the part against the refund in {@link #reviewRef}, which covers it and maybe more).
     */
    @Column(name = "review_cause", length = 32)
    private String reviewCause;

    /**
     * The provider's refund that made it {@code REFUNDED_ANOTHER_WAY}, when the list shows one; the comma-separated
     * ids recorded as not this part's ({@code FOREIGN_NOT_THIS_PART}); or the refund that closed it
     * ({@code COMPLETED_BY_OTHER_REFUND}).
     */
    @Column(name = "review_ref", length = 200)
    private String reviewRef;

    /** Read and left undecided by the rejected-refund run: sorts it behind refunds not read as recently (D-110). */
    @Column(name = "settle_held_at")
    private Instant settleHeldAt;

    /** The last time the provider's refunds of this payment were read for this refund, and what they showed. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** {@code NONE_OF_OURS} or {@code OURS}: what the read at {@link #verifiedAt} showed. */
    @Column(name = "verified_result", length = 16)
    private String verifiedResult;

    /** The provider first said this refund failed; asked again after an hour before it is believed. */
    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** The operator who put the money back; null when the system did. */
    @Column(name = "reversed_by")
    private Long reversedBy;

    /** A money-moving operator action ({@code RECREDIT}, {@code TO_WALLET}) waiting for a second person. */
    @Column(name = "ops_action", length = 16)
    private String opsAction;

    @Column(name = "ops_action_by")
    private Long opsActionBy;

    @Column(name = "ops_action_at")
    private Instant opsActionAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * This refund was put back in a wallet and then turned up at the provider: the restaurant was credited
     * twice (D-110). While it is set and {@link #lateSuccessResolvedAt} is not, the outlet's withdrawals are paused.
     */
    @Column(name = "late_success_at")
    private Instant lateSuccessAt;

    @Column(name = "late_success_resolved_at")
    private Instant lateSuccessResolvedAt;

    /**
     * Any change of status ends a first approver's pending request: it was made about the refund as it was,
     * and a second person must not complete it on a refund that has since been retried, adopted or failed again.
     */
    public void setStatus(RefundStatus status) {
        if (this.status != status) {
            this.opsAction = null;
            this.opsActionBy = null;
            this.opsActionAt = null;
        }
        this.status = status;
    }
}
