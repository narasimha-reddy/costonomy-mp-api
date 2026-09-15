package com.costonomy.mp.trust.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A photograph or document supporting a dispute. Append-only.
 *
 * <p>A reference rather than the bytes: file storage is out of scope for this
 * version, and a blob column would be a storage decision made by accident.
 */
@Entity
@Table(name = "dispute_evidence")
@Getter
@Setter
@NoArgsConstructor
public class DisputeEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    @Column(name = "dispute_message_id")
    private Long disputeMessageId;

    /** IMAGE, DOCUMENT or VIDEO. */
    @Column(name = "evidence_type", nullable = false, length = 32)
    private String evidenceType;

    @Column(name = "reference", nullable = false, length = 1000)
    private String reference;

    @Column(name = "caption", length = 500)
    private String caption;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
