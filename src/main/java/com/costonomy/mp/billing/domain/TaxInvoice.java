package com.costonomy.mp.billing.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Statutory B2B Tax Invoice under Section 31 of CGST Act / Rule 46 of CGST Rules.
 *
 * <p>Contains supplier GSTIN, buyer GSTIN, place of supply, state codes, and
 * itemised CGST/SGST/IGST tax breakdowns for compliant GST reporting and ITC claims.
 */
@Entity
@Table(name = "tax_invoice")
@Getter
@Setter
@NoArgsConstructor
public class TaxInvoice extends BaseEntity {

    @Column(name = "invoice_number", nullable = false, length = 16)
    private String invoiceNumber;

    /** The financial year's starting calendar year (2026 for April 2026 to March 2027). */
    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "sequence_value", nullable = false)
    private Long sequenceValue;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "supplier_name", nullable = false)
    private String supplierName;

    @Column(name = "supplier_gstin", nullable = false, length = 15)
    private String supplierGstin;

    @Column(name = "supplier_address", nullable = false, columnDefinition = "text")
    private String supplierAddress;

    @Column(name = "supplier_state_code", nullable = false, length = 2)
    private String supplierStateCode;

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(name = "buyer_gstin", length = 32)
    private String buyerGstin;

    @Column(name = "buyer_address", nullable = false, columnDefinition = "text")
    private String buyerAddress;

    @Column(name = "buyer_state_code", nullable = false, length = 2)
    private String buyerStateCode;

    @Column(name = "place_of_supply", nullable = false, length = 64)
    private String placeOfSupply;

    @Column(name = "is_inter_state", nullable = false)
    private boolean isInterState = false;

    @Column(name = "taxable_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "delivery_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ISSUED";

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();
}
