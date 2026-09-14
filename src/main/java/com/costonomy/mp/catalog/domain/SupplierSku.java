package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A supplier's commercial SKU. Doc 01 §7.
 *
 * <p>Identity only — their code, their brand, their pack, mapped to a canonical
 * product. Price and availability live on {@link SupplierOffer}, because those
 * change constantly and their history has to survive
 * (doc 02 §4).
 */
@Entity
@Table(name = "supplier_sku")
@Getter
@Setter
@NoArgsConstructor
public class SupplierSku extends BaseEntity {

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    /** The supplier's own code. Optional — many small suppliers have none. */
    @Column(name = "sku_code", length = 120)
    private String skuCode;

    @Column(name = "name", nullable = false, length = 250)
    private String name;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "pack_size", nullable = false, precision = 19, scale = 4)
    private BigDecimal packSize;

    @Column(name = "pack_unit", nullable = false, length = 32)
    private String packUnit;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** ACTIVE or INACTIVE. An inactive SKU is hidden from search but keeps its history. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
