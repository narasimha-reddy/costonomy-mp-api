package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** What the restaurant asked for, and what the supplier said. Doc 04 §13. */
@Entity
@Table(name = "credit_request")
@Getter
@Setter
@NoArgsConstructor
public class CreditRequest extends BaseEntity {

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "requested_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedLimit;

    @Column(name = "requested_period_days", nullable = false)
    private Integer requestedPeriodDays;

    @Column(name = "purpose", length = 64)
    private String purpose;

    @Column(name = "note", length = 1000)
    private String note;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CreditRequestStatus status = CreditRequestStatus.REQUESTED;

    /** The supplier's own words, shown to the restaurant verbatim. */
    @Column(name = "response_note", length = 1000)
    private String responseNote;

    @Column(name = "responded_by")
    private Long respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;
}
