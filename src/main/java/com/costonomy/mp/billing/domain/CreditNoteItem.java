package com.costonomy.mp.billing.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Line item on a statutory Credit Note.
 */
@Entity
@Table(name = "credit_note_item")
@Getter
@Setter
@NoArgsConstructor
public class CreditNoteItem extends BaseEntity {

    @Column(name = "credit_note_id", nullable = false)
    private Long creditNoteId;

    @Column(name = "supplier_order_item_id", nullable = false)
    private Long supplierOrderItemId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "hsn_code", nullable = false, length = 16)
    private String hsnCode;

    @Column(name = "rejected_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal rejectedQuantity;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "taxable_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableRefund;

    @Column(name = "gst_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "cgst_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal cgstRefund = BigDecimal.ZERO;

    @Column(name = "sgst_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal sgstRefund = BigDecimal.ZERO;

    @Column(name = "igst_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal igstRefund = BigDecimal.ZERO;

    @Column(name = "total_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRefund;

    @Column(name = "rejection_reason")
    private String rejectionReason;
}
