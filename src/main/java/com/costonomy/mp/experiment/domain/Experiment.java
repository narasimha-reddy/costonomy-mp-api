package com.costonomy.mp.experiment.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Definition of an A/B test or feature flag experiment.
 */
@Entity
@Table(name = "experiment")
@Getter
@Setter
@NoArgsConstructor
public class Experiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "experiment_key", nullable = false, unique = true, length = 64)
    private String experimentKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ExperimentStatus status = ExperimentStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_type", nullable = false, length = 32)
    private SubjectType subjectType = SubjectType.USER;

    /** 0 to 100 representing percentage of traffic entering this experiment. */
    @Column(name = "traffic_allocation_pct", nullable = false)
    private int trafficAllocationPct = 100;

    /** Unique salt used by MurmurHash3 to ensure uncorrelated bucketing across experiments. */
    @Column(name = "salt", nullable = false, length = 64)
    private String salt;

    /** Mutually exclusive layer group name (optional). */
    @Column(name = "layer", length = 64)
    private String layer;

    /** JSON rules defining targeting conditions on subject attributes. */
    @Column(name = "targeting_rules", columnDefinition = "json")
    private String targetingRules;

    /** JSON map of variant keys to lists of explicitly whitelisted subject IDs (e.g. {"treatment": ["101"]}). */
    @Column(name = "overrides", columnDefinition = "json")
    private String overrides;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @OneToMany(mappedBy = "experiment", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<ExperimentVariant> variants = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void addVariant(ExperimentVariant variant) {
        variants.add(variant);
        variant.setExperiment(this);
    }

    public void removeVariant(ExperimentVariant variant) {
        variants.remove(variant);
        variant.setExperiment(null);
    }

    public Optional<ExperimentVariant> getControlVariant() {
        return variants.stream()
                .filter(ExperimentVariant::isControl)
                .findFirst()
                .or(() -> variants.stream().findFirst());
    }

    public Optional<ExperimentVariant> getVariantByKey(String key) {
        return variants.stream()
                .filter(v -> v.getVariantKey().equalsIgnoreCase(key))
                .findFirst();
    }

    public boolean isCurrentlyRunning(Instant now) {
        if (status != ExperimentStatus.ACTIVE) {
            return false;
        }
        if (startTime != null && now.isBefore(startTime)) {
            return false;
        }
        if (endTime != null && now.isAfter(endTime)) {
            return false;
        }
        return true;
    }
}
