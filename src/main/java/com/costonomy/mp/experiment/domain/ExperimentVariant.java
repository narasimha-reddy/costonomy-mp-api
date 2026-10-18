package com.costonomy.mp.experiment.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A treatment variant within an experiment (e.g. "control", "treatment_a", "treatment_b").
 */
@Entity
@Table(name = "experiment_variant")
@Getter
@Setter
@NoArgsConstructor
public class ExperimentVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id", nullable = false)
    @JsonBackReference
    private Experiment experiment;

    @Column(name = "variant_key", nullable = false, length = 64)
    private String variantKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /** Relative weight determining the traffic split among variants (e.g. 50/50, or 34/33/33). */
    @Column(name = "weight", nullable = false)
    private int weight = 50;

    @Column(name = "is_control", nullable = false)
    private boolean isControl = false;

    /** Dynamic configuration JSON parameters served with this variant. */
    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ExperimentVariant(String variantKey, String name, int weight, boolean isControl, String payloadJson) {
        this.variantKey = variantKey;
        this.name = name;
        this.weight = weight;
        this.isControl = isControl;
        this.payloadJson = payloadJson;
    }
}
