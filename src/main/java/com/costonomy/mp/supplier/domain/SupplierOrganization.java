package com.costonomy.mp.supplier.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The legal/commercial supplier entity. Doc 01 §6, doc 02 §4. */
@Entity
@Table(name = "supplier_organization")
@Getter
@Setter
@NoArgsConstructor
public class SupplierOrganization extends BaseEntity {

    @Column(name = "legal_name", nullable = false, length = 250)
    private String legalName;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "gstin", length = 32)
    private String gstin;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "lifecycle_status", nullable = false, length = 32)
    private SupplierLifecycleStatus lifecycleStatus = SupplierLifecycleStatus.REGISTERED;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.NOT_SUBMITTED;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Whether this supplier may receive orders.
     *
     * <p>Only {@code ACTIVE}. Doc 03 §2 is explicit that a failed verification
     * must not silently activate a supplier, and doc 03 §15 requires an ACTIVE
     * store before a supplier order can be created at all.
     */
    public boolean canTradeNow() {
        return lifecycleStatus == SupplierLifecycleStatus.ACTIVE;
    }
}
