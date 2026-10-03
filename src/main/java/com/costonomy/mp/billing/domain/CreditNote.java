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
 * Statutory B2B Credit Note under Section 34 of CGST Act / Rule 53 of CGST Rules.
 *
 * <p>Issued by the supplier to the restaurant when goods are rejected at the doorstep,
 * short-delivered, or subject to catch-weight variances, reversing GST liability
 * and ITC accordingly.
 */
@Entity
@Table(name = "credit_note")
@Getter
@Setter
@NoArgsConstructor
public class CreditNote extends BaseEntity {

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 64)
    private String creditNoteNumber;

    @Column(name = "tax_invoice_id")
    private Long taxInvoiceId;

    @Column(name = "tax_invoice_number", length = 64)
    private String taxInvoiceNumber;

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

    @Column(name = "supplier_gstin", length = 32)
    private String supplierGstin;

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(name = "buyer_gstin", length = 32)
    private String buyerGstin;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode = "DOORSTEP_REJECTION";

    @Column(name = "is_inter_state", nullable = false)
    private boolean isInterState = false;

    @Column(name = "taxable_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableRefundAmount = BigDecimal.ZERO;

    @Column(name = "cgst_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal cgstRefundAmount = BigDecimal.ZERO;

    @Column(name = "sgst_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal sgstRefundAmount = BigDecimal.ZERO;

    @Column(name = "igst_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal igstRefundAmount = BigDecimal.ZERO;

    @Column(name = "total_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRefundAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ISSUED";

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();
}
