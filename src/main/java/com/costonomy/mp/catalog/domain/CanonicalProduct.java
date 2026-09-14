package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Platform-owned product identity. Doc 01 §7, doc 02 §4.
 *
 * <p>The unit of comparison. Amul paneer and Britannia paneer are different
 * supplier SKUs mapping to this one product, which is what makes "compare
 * offers" mean anything at all.
 *
 * <p>Suppliers cannot create these. If they could, two suppliers would each
 * invent their own "Paneer 1kg" and their offers would never appear side by side
 * — which is the marketplace failing at its one job.
 */
@Entity
@Table(name = "canonical_product")
@Getter
@Setter
@NoArgsConstructor
public class CanonicalProduct extends BaseEntity {

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 250)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 250)
    private String normalizedName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** The canonical shape. A supplier's actual pack may differ and lives on their SKU. */
    @Column(name = "base_unit", length = 32)
    private String baseUnit;

    @Column(name = "base_pack_size", precision = 19, scale = 4)
    private BigDecimal basePackSize;

    /** Platform-owned imagery (doc 01 §7), so comparison is not a parade of mismatched photos. */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;
}
