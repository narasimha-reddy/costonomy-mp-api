package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One picture in a SKU's gallery. D-096.
 *
 * <p>The thumbnail is not one of these — it stays on the SKU, because every
 * list, cart row and order line reads it and none of them wants a join.
 */
@Entity
@Table(name = "supplier_sku_image")
@Getter
@Setter
@NoArgsConstructor
public class SupplierSkuImage extends BaseEntity {

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /** Order in the gallery. A number, so the database sorts it. */
    @Column(name = "position", nullable = false)
    private int position;
}
