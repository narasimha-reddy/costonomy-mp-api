package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** One supplier's share of a procurement. Doc 02 §4, doc 03 §5. */
@Entity
@Table(name = "supplier_order")
@Getter
@Setter
@NoArgsConstructor
public class SupplierOrder extends BaseEntity {

    /**
     * The cart this came from, or null when it came from an intent.
     *
     * <p>Nullable since V23. An order created from an accepted intent has no
     * procurement behind it — the intent is the basket — and the link to its
     * origin lives in {@code intent_order_link} instead.
     */
    @Column(name = "procurement_id")
    private Long procurementId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    /** e.g. {@code MP-260914-001284}. What people quote to each other. */
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 40)
    private SupplierOrderStatus status = SupplierOrderStatus.DRAFT;

    /**
     * When this order stops being acceptable.
     *
     * <p>Snapshotted at creation from the store's SLA and never recomputed. Doc 13:
     * a supplier changing their SLA must not move a live order's deadline. This is
     * the single authority the countdown and the timeout job both read.
     */
    @Column(name = "acceptance_deadline")
    private Instant acceptanceDeadline;

    /** The window this order was given, so the client can show a proportional countdown. */
    @Column(name = "response_sla_seconds", nullable = false)
    private Integer responseSlaSeconds = 60;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    @Column(name = "delivery_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * What the supplier actually committed to.
     *
     * <p>Zero until they respond, and below {@code totalAmount} after a partial
     * acceptance. Doc 01 §14 and §16: only this value is captured from the
     * restaurant, and only this value attracts commission.
     */
    @Column(name = "accepted_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal acceptedAmount = BigDecimal.ZERO;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod = "PREPAID";

    @Column(name = "payment_status", nullable = false, length = 32)
    private String paymentStatus = "PENDING";

    /**
     * How the goods travel. D-091.
     *
     * <p>Chosen by the restaurant at order creation, because the restaurant pays
     * the fee — and because the fee is part of what is charged, so it has to be
     * settled before the payment intent exists. It is also an argument to
     * {@link SupplierOrderStatus#allowedTransitions(DeliveryMode)}: where this
     * order goes after {@code READY_FOR_PICKUP} depends on who is carrying it.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "delivery_mode", nullable = false, length = 32)
    private DeliveryMode deliveryMode = DeliveryMode.PICKUP;

    /**
     * Who cancelled, when this order was. Null otherwise.
     *
     * <p>An attribute rather than a status per actor — see {@link CancelledBy}.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cancelled_by", length = 32)
    private CancelledBy cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Whether the deadline has passed, against a supplied clock.
     *
     * <p>The clock is a parameter rather than {@code Instant.now()} so the timeout
     * job and the tests agree on what "now" means. This answers a question about
     * time; it does not change state — expiry is a transition the job performs
     * under optimistic locking, racing acceptance, with exactly one winner.
     */
    public boolean isPastDeadline(Instant now) {
        return acceptanceDeadline != null && acceptanceDeadline.isBefore(now);
    }
}
