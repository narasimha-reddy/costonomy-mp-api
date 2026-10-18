package com.costonomy.mp.experiment.service;

import com.costonomy.mp.experiment.context.ExperimentContext;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.domain.SubjectType;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.dto.ExperimentEvaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * World-class A/B testing and experimentation platform service.
 */
public interface ExperimentService {

    /**
     * Evaluates an experiment with full contextual attributes.
     */
    ExperimentEvaluation evaluate(String experimentKey, ExperimentContext context);

    /**
     * Evaluates an experiment for a subject with attribute map.
     */
    ExperimentEvaluation evaluate(String experimentKey, String subjectId, Map<String, Object> attributes);

    /**
     * Evaluates an experiment for a subject with no extra attributes.
     */
    ExperimentEvaluation evaluate(String experimentKey, String subjectId);

    /**
     * Ambient evaluation using the current subject from {@link com.costonomy.mp.experiment.context.ExperimentContextHolder}.
     */
    ExperimentEvaluation evaluate(String experimentKey);

    /**
     * Evaluates and returns the assigned variant key, or defaultVariant if missing/inactive.
     */
    String getVariant(String experimentKey, String subjectId, String defaultVariant);

    /**
     * Feature-flag style evaluation: returns true if the assigned variant is not control.
     */
    boolean isEnabled(String experimentKey, String subjectId);

    /**
     * Dynamic payload retrieval: fetches and deserializes the configuration payload of the assigned variant.
     */
    <T> T getPayload(String experimentKey, String subjectId, Class<T> clazz, T fallback);

    /**
     * Tracks a quantitative or conversion metric event emitted by a subject.
     */
    void trackMetric(String subjectId, String metricName, BigDecimal value, Map<String, Object> properties);

    /**
     * Tracks a quantitative or conversion metric event emitted by a subject with optional explicit attribution.
     */
    void trackMetric(String subjectId, String metricName, BigDecimal value, Map<String, Object> properties,
                     String experimentKey, String variantKey, Long exposureId);

    /**
     * Batch evaluates multiple experiment keys (or ALL active experiments if experimentKeys is null/empty)
     * for a client (mobile/web) in a single request.
     */
    Map<String, ExperimentEvaluation> evaluateBatch(ExperimentContext context, List<String> experimentKeys);

    /**
     * Explicitly records a client-side impression/exposure when a UI component renders.
     */
    void recordExposure(String experimentKey, String variantKey, String subjectId,
                        SubjectType subjectType, Map<String, Object> attributes);

    /**
     * Tracks a simple conversion event.
     */
    void trackMetric(String subjectId, String metricName);

    // ── Admin & Lifecycle Management ─────────────────────────────────────

    List<ExperimentDtos.ExperimentDto> listExperiments();

    List<ExperimentDtos.ExperimentDto> listExperiments(ExperimentStatus status, SubjectType subjectType, String search);

    List<ExperimentDtos.ExperimentDto> getActiveExperiments();

    List<ExperimentDtos.ClientExperimentDto> getActiveClientExperiments();

    com.costonomy.mp.experiment.dto.LiveExperimentSnapshot getLiveSnapshot();

    com.costonomy.mp.experiment.dto.InspectionResult inspect(String experimentKey, String subjectId,
                                                             SubjectType subjectType, Map<String, Object> attributes);

    List<ExperimentDtos.ExposureRecordDto> getRecentExposures(String experimentKey);

    ExperimentDtos.ExperimentDto getExperiment(String experimentKey);

    ExperimentDtos.ExperimentDto createExperiment(ExperimentDtos.CreateExperimentRequest request);

    ExperimentDtos.ExperimentDto updateExperiment(String experimentKey, ExperimentDtos.UpdateExperimentRequest request);

    ExperimentDtos.ExperimentDto updateStatus(String experimentKey, ExperimentStatus status);

    ExperimentDtos.ExperimentDto addOverride(String experimentKey, String variantKey, List<String> subjectIds);

    void deleteExperiment(String experimentKey);

    ExperimentDtos.ExperimentAnalyticsResponse getAnalytics(String experimentKey, String metricName, Instant since);

    void reloadCache();
}
