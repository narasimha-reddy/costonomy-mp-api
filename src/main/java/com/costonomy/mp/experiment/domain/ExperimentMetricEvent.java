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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only log recording conversion events or quantitative metrics emitted by subjects.
 * Enables automatic calculation of conversion rates, average order values, dispatch latencies,
 * and business KPIs grouped by experiment variants.
 */
@Entity
@Table(name = "experiment_metric_event")
@Getter
@Setter
@NoArgsConstructor
public class ExperimentMetricEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "subject_id", nullable = false, length = 128)
    private String subjectId;

    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName;

    @Column(name = "numeric_value", precision = 19, scale = 4)
    private BigDecimal numericValue;

    @Column(name = "properties", columnDefinition = "json")
    private String properties;

    @Column(name = "experiment_key", length = 64)
    private String experimentKey;

    @Column(name = "variant_key", length = 64)
    private String variantKey;

    @Column(name = "exposure_id")
    private Long exposureId;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    public ExperimentMetricEvent(String subjectId, String metricName, BigDecimal numericValue, String properties) {
        this(subjectId, metricName, numericValue, properties, null, null, null);
    }

    public ExperimentMetricEvent(String subjectId, String metricName, BigDecimal numericValue, String properties,
                                 String experimentKey, String variantKey, Long exposureId) {
        this.subjectId = subjectId;
        this.metricName = metricName;
        this.numericValue = numericValue;
        this.properties = properties;
        this.experimentKey = experimentKey;
        this.variantKey = variantKey;
        this.exposureId = exposureId;
    }
}
