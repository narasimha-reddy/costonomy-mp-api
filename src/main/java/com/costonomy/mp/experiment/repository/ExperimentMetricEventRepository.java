package com.costonomy.mp.experiment.repository;

import com.costonomy.mp.experiment.domain.ExperimentMetricEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface ExperimentMetricEventRepository extends JpaRepository<ExperimentMetricEvent, Long> {

    interface MetricConversionSummary {
        String getVariantKey();
        Long getConversionCount();
        Long getUniqueConvertedSubjects();
        BigDecimal getTotalValue();
        Double getAverageValue();
    }

    @Query(nativeQuery = true, value = """
            WITH attributed_events AS (
                SELECT m.id AS metric_id,
                       m.variant_key AS variant_key,
                       m.subject_id AS subject_id,
                       m.numeric_value AS numeric_value
                FROM experiment_metric_event m
                WHERE m.experiment_key = :experimentKey
                  AND m.metric_name = :metricName
                UNION ALL
                SELECT matched.metric_id,
                       matched.variant_key,
                       matched.subject_id,
                       matched.numeric_value
                FROM (
                    SELECT m.id AS metric_id,
                           exp.variant_key AS variant_key,
                           m.subject_id AS subject_id,
                           m.numeric_value AS numeric_value,
                           ROW_NUMBER() OVER (PARTITION BY m.id ORDER BY exp.exposed_at DESC, exp.id DESC) AS rn
                    FROM experiment_metric_event m
                    JOIN experiment_exposure exp ON m.subject_id = exp.subject_id
                    WHERE exp.experiment_key = :experimentKey
                      AND exp.evaluation_reason IN ('BUCKETED', 'OVERRIDE')
                      AND m.metric_name = :metricName
                      AND m.recorded_at >= exp.exposed_at
                      AND (m.experiment_key IS NULL OR m.experiment_key != :experimentKey)
                ) matched
                WHERE matched.rn = 1
            )
            SELECT variant_key AS variantKey,
                   COUNT(metric_id) AS conversionCount,
                   COUNT(DISTINCT subject_id) AS uniqueConvertedSubjects,
                   COALESCE(SUM(numeric_value), 0) AS totalValue,
                   COALESCE(AVG(numeric_value), 0.0) AS averageValue
            FROM attributed_events
            GROUP BY variant_key
            """)
    List<MetricConversionSummary> getConversionSummary(
            @Param("experimentKey") String experimentKey,
            @Param("metricName") String metricName);

    @Query(nativeQuery = true, value = """
            WITH attributed_events AS (
                SELECT m.id AS metric_id,
                       m.variant_key AS variant_key,
                       m.subject_id AS subject_id,
                       m.numeric_value AS numeric_value
                FROM experiment_metric_event m
                WHERE m.experiment_key = :experimentKey
                  AND m.metric_name = :metricName
                  AND m.recorded_at >= :since
                UNION ALL
                SELECT matched.metric_id,
                       matched.variant_key,
                       matched.subject_id,
                       matched.numeric_value
                FROM (
                    SELECT m.id AS metric_id,
                           exp.variant_key AS variant_key,
                           m.subject_id AS subject_id,
                           m.numeric_value AS numeric_value,
                           ROW_NUMBER() OVER (PARTITION BY m.id ORDER BY exp.exposed_at DESC, exp.id DESC) AS rn
                    FROM experiment_metric_event m
                    JOIN experiment_exposure exp ON m.subject_id = exp.subject_id
                    WHERE exp.experiment_key = :experimentKey
                      AND exp.evaluation_reason IN ('BUCKETED', 'OVERRIDE')
                      AND m.metric_name = :metricName
                      AND m.recorded_at >= exp.exposed_at
                      AND m.recorded_at >= :since
                      AND (m.experiment_key IS NULL OR m.experiment_key != :experimentKey)
                ) matched
                WHERE matched.rn = 1
            )
            SELECT variant_key AS variantKey,
                   COUNT(metric_id) AS conversionCount,
                   COUNT(DISTINCT subject_id) AS uniqueConvertedSubjects,
                   COALESCE(SUM(numeric_value), 0) AS totalValue,
                   COALESCE(AVG(numeric_value), 0.0) AS averageValue
            FROM attributed_events
            GROUP BY variant_key
            """)
    List<MetricConversionSummary> getConversionSummarySince(
            @Param("experimentKey") String experimentKey,
            @Param("metricName") String metricName,
            @Param("since") Instant since);

    default List<MetricConversionSummary> getConversionSummary(String experimentKey, String metricName, Instant since) {
        return since == null
                ? getConversionSummary(experimentKey, metricName)
                : getConversionSummarySince(experimentKey, metricName, since);
    }
}
