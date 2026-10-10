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
 * Line item on a statutory Tax Invoice.
 */
@Entity
@Table(name = "tax_invoice_item")
@Getter
@Setter
@NoArgsConstructor
public class TaxInvoiceItem extends BaseEntity {

    @Column(name = "tax_invoice_id", nullable = false)
    private Long taxInvoiceId;

    @Column(name = "supplier_order_item_id", nullable = false)
    private Long supplierOrderItemId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "hsn_code", nullable = false, length = 16)
    private String hsnCode;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "taxable_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableValue;

    @Column(name = "gst_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;
}
