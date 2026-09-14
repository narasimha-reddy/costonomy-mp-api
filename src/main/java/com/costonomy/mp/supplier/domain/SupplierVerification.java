package com.costonomy.mp.supplier.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One verification submission. Doc 09 §8.
 *
 * <p>Append-only: a rejection followed by a resubmission keeps both rows. This is
 * an audit trail, not a current-value field — "when did this supplier become
 * verified, on what evidence, reviewed by whom" has to stay answerable.
 */
@Entity
@Table(name = "supplier_verification")
@Getter
@Setter
@NoArgsConstructor
public class SupplierVerification extends BaseEntity {

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    /** GST initially (doc 01 §6). */
    @Column(name = "verification_type", nullable = false, length = 32)
    private String verificationType = "GST";

    @Column(name = "submitted_by")
    private Long submittedBy;

    /** What the supplier claimed, verbatim, so a later dispute can be settled against it. */
    @Column(name = "submitted_data_json", nullable = false, columnDefinition = "json")
    private String submittedDataJson;

    @Column(name = "result_json", columnDefinition = "json")
    private String resultJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private VerificationStatus status = VerificationStatus.PENDING;

    /** {@code MANUAL} until a GST verification API is integrated. Doc 09 §8. */
    @Column(name = "verification_source", nullable = false, length = 64)
    private String verificationSource = "MANUAL";

    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    @Column(name = "evidence_url", length = 1000)
    private String evidenceUrl;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "verified_at")
    private Instant verifiedAt;
}
