package com.costonomy.mp.trust.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** A line a dispute is about. Append-only. */
@Entity
@Table(name = "dispute_item")
@Getter
@Setter
@NoArgsConstructor
public class DisputeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    @Column(name = "supplier_order_item_id", nullable = false)
    private Long supplierOrderItemId;

    @Column(name = "disputed_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal disputedQuantity;

    @Column(name = "reason", length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
