package com.costonomy.mp.trust.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line, checked in. Append-only.
 *
 * <p>The three quantities partition what was accepted:
 * {@code received + damaged + missing = accepted}. {@code received} is usable
 * stock, {@code damaged} arrived unusable, {@code missing} never arrived — and
 * keeping them apart is what lets a dispute say which problem it is about.
 */
@Entity
@Table(name = "receiving_item")
@Getter
@Setter
@NoArgsConstructor
public class ReceivingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "receiving_id", nullable = false)
    private Long receivingId;

    @Column(name = "supplier_order_item_id", nullable = false)
    private Long supplierOrderItemId;

    /** Snapshotted, because the line may be re-read years later in a dispute. */
    @Column(name = "accepted_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal acceptedQuantity;

    @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "damaged_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;

    @Column(name = "missing_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal missingQuantity = BigDecimal.ZERO;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "note", length = 500)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Whether this line arrived as committed. */
    public boolean isShort() {
        return damagedQuantity.signum() > 0 || missingQuantity.signum() > 0;
    }
}
