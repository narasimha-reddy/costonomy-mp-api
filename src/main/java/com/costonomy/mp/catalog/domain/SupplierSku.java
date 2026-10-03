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

    /**
     * Quality / variant grade of the product or brand (e.g. "Grade A", "Grade B",
     * "Grade C", "Premium", "Standard", or loose commodity grade like "Elaichi Grade A").
     */
    @Column(name = "grade", length = 100)
    private String grade;

    @Column(name = "pack_size", nullable = false, precision = 19, scale = 4)
    private BigDecimal packSize;

    @Column(name = "pack_unit", nullable = false, length = 32)
    private String packUnit;

    /**
     * Printed MRP or benchmark market retail price. Optional for loose/unbranded commodities.
     */
    @Column(name = "mrp", precision = 19, scale = 4)
    private BigDecimal mrp;

    /**
     * How much is inside one pack, when the pack unit does not say.
     *
     * <p>"1 PKT" describes a bundle; "1 PKT of 500 GM" describes an amount. Set
     * for the container units and null for the rest — a SKU packed in KG already
     * states its amount, and a second statement of the same quantity is a second
     * chance to disagree with itself. {@code SupplierCatalogService} enforces
     * both halves of that.
     */
    @Column(name = "measure_value", precision = 19, scale = 4)
    private BigDecimal measureValue;

    @Column(name = "measure_unit", length = 16)
    private String measureUnit;

    /**
     * What one pack weighs, in grams. D-091.
     *
     * <p>Nullable, and the delivery quote derives a figure when it is absent — a
     * KG pack size is mass outright, a litre needs an assumed density, and a
     * count carries no weight information at all. A supplier who has not filled
     * this in still has orderable SKUs; the quote records that the weight was
     * derived rather than stated.
     */
    @Column(name = "weight_grams", precision = 19, scale = 4)
    private BigDecimal weightGrams;

    /** The thumbnail. Every list, cart row and order line shows this one. */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /**
     * What it is, in the supplier's own words. D-096.
     *
     * <p>Optional, like everything else added for the detail page: a listing
     * without it behaves exactly as it did, and a supplier filling one in is
     * answering a question a kitchen would otherwise have to guess at.
     */
    @Column(name = "description", length = 2000)
    private String description;

    /**
     * The pack, measured. Centimetres.
     *
     * <p>Structured rather than a line of text, because two sacks are only
     * comparable if the numbers are numbers — and `weightGrams` above is
     * already read by delivery quoting.
     */
    @Column(name = "length_cm", precision = 9, scale = 2)
    private BigDecimal lengthCm;

    @Column(name = "width_cm", precision = 9, scale = 2)
    private BigDecimal widthCm;

    @Column(name = "height_cm", precision = 9, scale = 2)
    private BigDecimal heightCm;

    /** Pasted, never uploaded. Hosting video is a different problem. */
    @Column(name = "youtube_url", length = 500)
    private String youtubeUrl;

    /** ACTIVE or INACTIVE. An inactive SKU is hidden from search but keeps its history. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
