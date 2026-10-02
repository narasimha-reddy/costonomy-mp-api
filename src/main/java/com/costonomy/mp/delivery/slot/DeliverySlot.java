package com.costonomy.mp.delivery.slot;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * A time-bounded delivery window configured by a supplier store.
 * Allows restaurants to choose specific delivery windows (e.g. morning, afternoon)
 * with order cutoff limits and daily capacity limits.
 */
@Entity
@Table(name = "delivery_slot")
@Getter
@Setter
@NoArgsConstructor
public class DeliverySlot extends BaseEntity {

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "slot_name", nullable = false, length = 64)
    private String slotName;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "order_cutoff_time", nullable = false)
    private LocalTime orderCutoffTime;

    @Column(name = "max_orders_per_day", nullable = false)
    private Integer maxOrdersPerDay = 50;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
