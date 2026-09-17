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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * "This is what I can supply, at this price." One per intent.
 *
 * <p><b>The figures here are a quotation, not a debt.</b> Nothing is owed until
 * an order exists, so these columns are what the restaurant is deciding against
 * rather than what anyone will be charged — the order recomputes from current
 * prices at creation and refuses if they have moved (§17).
 *
 * <p>Its own table rather than columns on the intent, because it is a separate
 * party's statement with its own expiry, and because freezing it is the whole
 * point: an intent may not be edited after this exists.
 */
@Entity
@Table(name = "intent_acceptance")
@Getter
@Setter
@NoArgsConstructor
public class IntentAcceptance extends BaseEntity {

    @Column(name = "intent_id", nullable = false)
    private Long intentId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "responded_by")
    private Long respondedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private IntentAcceptanceStatus status = IntentAcceptanceStatus.DRAFT;

    @Column(name = "offered_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal offeredValue = BigDecimal.ZERO;

    @Column(name = "offered_gst", nullable = false, precision = 19, scale = 4)
    private BigDecimal offeredGst = BigDecimal.ZERO;

    @Column(name = "offered_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal offeredTotal = BigDecimal.ZERO;

    /**
     * Quoted separately and not folded into the total.
     *
     * <p>Guardrail: delivery is not known until a courier is chosen after Ready
     * for Pickup. A supplier quoting their own delivery is quoting their own
     * delivery; it does not become the platform's figure.
     */
    @Column(name = "delivery_fee", precision = 19, scale = 4)
    private BigDecimal deliveryFee;

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "delivery_mode", length = 32)
    private String deliveryMode;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
