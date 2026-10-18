package com.costonomy.mp.experiment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Append-only log recording when a subject was exposed to an experiment evaluation.
 */
@Entity
@Table(name = "experiment_exposure")
@Getter
@Setter
@NoArgsConstructor
public class ExperimentExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "experiment_key", nullable = false, length = 64)
    private String experimentKey;

    @Column(name = "variant_key", nullable = false, length = 64)
    private String variantKey;

    @Column(name = "subject_id", nullable = false, length = 128)
    private String subjectId;

    @Column(name = "subject_type", nullable = false, length = 32)
    private String subjectType;

    @Column(name = "evaluation_reason", nullable = false, length = 64)
    private String evaluationReason;

    @Column(name = "attributes_snapshot", columnDefinition = "json")
    private String attributesSnapshot;

    @CreationTimestamp
    @Column(name = "exposed_at", nullable = false, updatable = false)
    private Instant exposedAt;

    public ExperimentExposure(String experimentKey, String variantKey, String subjectId,
                              String subjectType, String evaluationReason, String attributesSnapshot) {
        this.experimentKey = experimentKey;
        this.variantKey = variantKey;
        this.subjectId = subjectId;
        this.subjectType = subjectType;
        this.evaluationReason = evaluationReason;
        this.attributesSnapshot = attributesSnapshot;
    }
}
