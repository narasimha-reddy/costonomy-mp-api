package com.costonomy.mp.intent.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * What the supplier will supply of one line, and for how much.
 *
 * <p><b>Zero is a real answer and is stored.</b> An omitted line is unanswered;
 * a zero line is declined. The restaurant needs to tell those apart — one means
 * "chase them", the other means "source it elsewhere" — so every line of the
 * intent gets a row here, including the refusals.
 */
@Entity
@Table(name = "intent_acceptance_item")
@Getter
@Setter
@NoArgsConstructor
public class IntentAcceptanceItem extends BaseEntity {

    @Column(name = "intent_acceptance_id", nullable = false)
    private Long intentAcceptanceId;

    @Column(name = "intent_item_id", nullable = false)
    private Long intentItemId;

    @Column(name = "offered_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal offeredQuantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "gst_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "line_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineValue;

    @Column(name = "line_gst", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineGst;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(name = "availability", nullable = false, length = 32)
    private String availability = "AVAILABLE";

    @Column(name = "notes", length = 500)
    private String notes;
}
