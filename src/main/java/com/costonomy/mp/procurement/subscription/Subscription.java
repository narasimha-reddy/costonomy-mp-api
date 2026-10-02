package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "subscription")
@Getter
@Setter
@NoArgsConstructor
public class Subscription extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "canonical_product_id")
    private Long canonicalProductId;

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "frequency", nullable = false, length = 32)
    private SubscriptionFrequency frequency = SubscriptionFrequency.DAILY;

    @Column(name = "preferred_slot_id")
    private Long preferredSlotId;

    @Column(name = "delivery_mode", nullable = false, length = 32)
    private String deliveryMode = "SUPPLIER_DELIVERY";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "next_delivery_date")
    private LocalDate nextDeliveryDate;

    @Column(name = "notes", length = 500)
    private String notes;
}
