package com.costonomy.mp.intent.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * What a restaurant intends to buy from one supplier. Non-financial.
 *
 * <p><b>One supplier, because it holds that supplier's SKUs.</b> Sourcing the
 * same products from three suppliers is three intents, which is what keeps the
 * intent → order relationship one-to-one. Comparing suppliers happens on the
 * product screen, where the offers are.
 *
 * <p><b>Nothing about money lives here.</b> No payment, no credit reservation, no
 * commission, no settlement. An intent nobody orders from costs nobody anything
 * and appears in no GMV figure. The financial boundary is order creation.
 */
@Entity
@Table(name = "intent")
@Getter
@Setter
@NoArgsConstructor
public class Intent extends BaseEntity {

    /** Human-quotable, e.g. {@code INT-1024}. What support asks for on the phone. */
    @Column(name = "reference", nullable = false, length = 32)
    private String reference;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private IntentStatus status = IntentStatus.DRAFT;

    /** MANUAL or CLONED — where the restaurant got the idea, worth measuring. */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "MANUAL";

    /**
     * The intent this was copied from.
     *
     * <p>Provenance only. A clone inherits no acceptance, no order, no payment
     * and no quantities beyond its starting values — it is a separate entity with
     * its own lifecycle, and this column must never be read as a parent link.
     */
    @Column(name = "cloned_from_id")
    private Long clonedFromId;

    @Column(name = "requested_delivery_time")
    private Instant requestedDeliveryTime;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** When the supplier answered. The authoritative start of the order window. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /**
     * The window as it stood when the supplier accepted.
     *
     * <p>Snapshotted rather than read from configuration at order time, so
     * changing the platform setting cannot shorten a window a restaurant is
     * already inside — or extend one they have already lost.
     */
    @Column(name = "accepted_order_creation_window_seconds")
    private Integer acceptedOrderCreationWindowSeconds;

    @Column(name = "order_creation_deadline")
    private Instant orderCreationDeadline;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    /** Whether an order may still be created, by the clock alone. */
    public boolean withinOrderWindow(Instant now) {
        return orderCreationDeadline != null && !now.isAfter(orderCreationDeadline);
    }
}
