package com.costonomy.mp.quickscan.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A restaurant paying a UPI merchant from its wallet. D-106, part one — sandbox
 * only, wallet only; a real payout provider and UPI-direct come later.
 *
 * <p>The wallet is debited for {@link #amount} plus {@link #feeAmount} in the
 * same transaction the row is created in — before a payout provider has been
 * asked to do anything — because the debit is the write that must never race a
 * second click. The payout then catches up: {@link QuickScanStatus#PAYOUT_PENDING}
 * until the provider answers, {@link QuickScanStatus#PAID} once it reaches the
 * shop, {@link QuickScanStatus#FAILED} with the money back in the wallet if it
 * does not, or {@link QuickScanStatus#NEEDS_REVIEW} if retries run out with no
 * answer — the one case the money is deliberately <b>not</b> auto-returned in,
 * because it may already have reached the shop.
 */
@Entity
@Table(name = "quickscan_payment")
@Getter
@Setter
@NoArgsConstructor
public class QuickScanPayment extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "payee_vpa", nullable = false, length = 100)
    private String payeeVpa;

    @Column(name = "payee_name", length = 100)
    private String payeeName;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "method", nullable = false, length = 16)
    private QuickScanMethod method = QuickScanMethod.WALLET;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private QuickScanStatus status = QuickScanStatus.PAYOUT_PENDING;

    @Column(name = "provider_payout_id", length = 100)
    private String providerPayoutId;

    /** Payout sends so far. Stops automatic retries at a limit, like a refund (D-101). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * Uniquely indexed. {@code qs-{outletId}-{clientKey}}, so the same client key
     * reused by a different outlet cannot collide with someone else's payment.
     */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * When the provider was last asked about this payout, set by
     * {@code QuickScanService#settlePending} whenever it gets an answer — PENDING
     * included, and PAID confirmed still PAID — so {@code updatedAt} actually
     * moves and the row backs off rather than being re-fetched on every job run
     * forever (D-106).
     */
    @Column(name = "checked_at")
    private Instant checkedAt;

    /** What the wallet actually lost: {@link #amount} plus {@link #feeAmount}. */
    public BigDecimal total() {
        return amount.add(feeAmount == null ? BigDecimal.ZERO : feeAmount);
    }
}
