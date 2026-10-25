package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What Costonomy owes a delivery provider for one booked courier (D-204).
 *
 * <p>A supplier deduction or a direct supplier payment is money coming in; neither proves the courier was paid. This
 * row is the other side: one per booking attempt that reached the provider, with what it cost, who funds it, and
 * every try at paying it.
 *
 * <p>Amounts: {@code amountDue} is what is owed now. A courier that is only booked owes nothing yet
 * ({@code ACCRUED}); one that has the goods owes the final cost; one cancelled owes the cancellation fee, if any.
 * {@code buyerContribution + supplierContribution + platformFunded} is the cost incurred, where the first two are
 * fixed by what each agreed to at checkout (the supplier's never more than the cost) and the platform bears the rest,
 * including any difference between the quote and the final cost, so it can be negative.
 */
@Entity
@Table(name = "courier_payable")
@Getter
@Setter
@NoArgsConstructor
public class CourierPayable extends BaseEntity {

    public static final String SUPPLIER_DELIVERY = "SUPPLIER_DELIVERY";
    public static final String FOOD_DELIVERY = "FOOD_DELIVERY";

    @Column(name = "source_type", nullable = false, length = 24)
    private String sourceType;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "supplier_order_id")
    private Long supplierOrderId;

    @Column(name = "outlet_id")
    private Long outletId;

    @Column(name = "supplier_store_id")
    private Long supplierStoreId;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "provider_delivery_id", length = 200)
    private String providerDeliveryId;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "quoted_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal quotedCost = BigDecimal.ZERO;

    @Column(name = "final_cost", precision = 19, scale = 4)
    private BigDecimal finalCost;

    @Column(name = "final_cost_manual", nullable = false)
    private boolean finalCostManual;

    @Column(name = "cancellation_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal cancellationFee = BigDecimal.ZERO;

    @Column(name = "provider_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal providerCredit = BigDecimal.ZERO;

    @Column(name = "amount_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountDue = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "buyer_contribution", nullable = false, precision = 19, scale = 4)
    private BigDecimal buyerContribution = BigDecimal.ZERO;

    @Column(name = "supplier_contribution", nullable = false, precision = 19, scale = 4)
    private BigDecimal supplierContribution = BigDecimal.ZERO;

    @Column(name = "platform_funded", nullable = false, precision = 19, scale = 4)
    private BigDecimal platformFunded = BigDecimal.ZERO;

    @Column(name = "state", nullable = false, length = 16)
    private String state = CourierPayableState.ACCRUED;

    @Column(name = "due_reason", length = 32)
    private String dueReason;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "payment_rail", length = 32)
    private String paymentRail;

    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "claim_token", length = 64)
    private String claimToken;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "reconciliation_state", nullable = false, length = 16)
    private String reconciliationState = "NONE";

    @Column(name = "reconciliation_note", length = 500)
    private String reconciliationNote;

    /** What is still owed: due less paid, never below zero. */
    public BigDecimal balance() {
        var b = amountDue.subtract(amountPaid);
        return b.signum() < 0 ? BigDecimal.ZERO : b;
    }

    /** Paid more than is now owed (a credit or a lower final cost arrived after payment): the provider owes us. */
    public BigDecimal overpaid() {
        var o = amountPaid.subtract(amountDue);
        return o.signum() < 0 ? BigDecimal.ZERO : o;
    }
}
