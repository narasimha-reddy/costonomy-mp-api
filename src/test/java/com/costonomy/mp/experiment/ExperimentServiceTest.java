package com.costonomy.mp.experiment;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.experiment.algorithm.BucketingEngine;
import com.costonomy.mp.experiment.algorithm.TargetingRuleEvaluator;
import com.costonomy.mp.experiment.context.ExperimentContext;
import com.costonomy.mp.experiment.context.ExperimentContextHolder;
import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.costonomy.mp.experiment.domain.Experiment;
import com.costonomy.mp.experiment.domain.ExperimentExposure;
import com.costonomy.mp.experiment.domain.ExperimentMetricEvent;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.domain.ExperimentVariant;
import com.costonomy.mp.experiment.domain.SubjectType;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.dto.ExperimentEvaluation;
import com.costonomy.mp.experiment.repository.ExperimentExposureRepository;
import com.costonomy.mp.experiment.repository.ExperimentMetricEventRepository;
import com.costonomy.mp.experiment.repository.ExperimentRepository;
import com.costonomy.mp.experiment.service.ExperimentService;
import com.costonomy.mp.experiment.service.ExperimentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    @Mock private ExperimentRepository experimentRepository;
    @Mock private ExperimentExposureRepository exposureRepository;
    @Mock private ExperimentMetricEventRepository metricEventRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TargetingRuleEvaluator ruleEvaluator;
    private BucketingEngine bucketingEngine;
    private ExperimentService experimentService;

    public record CourierConfigPayload(String strategy, int timeoutMs, boolean allowFallback) {}

    @BeforeEach
    void setUp() {
        ruleEvaluator = new TargetingRuleEvaluator(objectMapper);
        bucketingEngine = new BucketingEngine(objectMapper);
        experimentService = new ExperimentServiceImpl(
                experimentRepository,
                exposureRepository,
                metricEventRepository,
                bucketingEngine,
                ruleEvaluator,
                eventPublisher,
                objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        ExperimentContextHolder.clear();
    }

    private Experiment createBenchmarkExperiment() {
        Experiment exp = new Experiment();
        exp.setId(1L);
        exp.setExperimentKey("courier_selection_strategy");
        exp.setName("Courier Selection Strategy");
        exp.setStatus(ExperimentStatus.ACTIVE);
        exp.setSubjectType(SubjectType.RESTAURANT);
        exp.setTrafficAllocationPct(100);
        exp.setSalt(UUID.randomUUID().toString());
        exp.setTargetingRules("{\"conditions\":[{\"attribute\":\"city\",\"operator\":\"IN\",\"values\":[\"Bangalore\"]}]}");
        exp.setOverrides("{\"treatment\":[\"test_outlet_99\"]}");

        ExperimentVariant control = new ExperimentVariant(
                "control", "Cheapest First", 50, true,
                "{\"strategy\":\"CHEAPEST_FIRST\",\"timeoutMs\":3000,\"allowFallback\":true}"
        );
        ExperimentVariant treatment = new ExperimentVariant(
                "treatment", "Reliability Weighted", 50, false,
                "{\"strategy\":\"RELIABILITY_WEIGHTED\",\"timeoutMs\":2500,\"allowFallback\":true}"
        );
        exp.addVariant(control);
        exp.addVariant(treatment);
        return exp;
    }

    @Test
    @DisplayName("Active experiment evaluates variant and deserializes dynamic typed payload")
    void evaluateActiveExperimentWithPayload() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        ExperimentEvaluation eval = experimentService.evaluate(
                "courier_selection_strategy",
                "outlet_501",
                Map.of("city", "Bangalore")
        );

        assertThat(eval.getExperimentKey()).isEqualTo("courier_selection_strategy");
        assertThat(eval.isInExperiment()).isTrue();
        assertThat(eval.getReason()).isEqualTo(EvaluationReason.BUCKETED);
        assertThat(eval.getVariantKey()).isIn("control", "treatment");

        CourierConfigPayload payload = eval.getPayloadAs(CourierConfigPayload.class);
        assertThat(payload).isNotNull();
        assertThat(payload.strategy()).isIn("CHEAPEST_FIRST", "RELIABILITY_WEIGHTED");
    }

    @Test
    @DisplayName("Whitelisted subject override receives target variant immediately")
    void evaluateOverride() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        ExperimentEvaluation eval = experimentService.evaluate(
                "courier_selection_strategy",
                "test_outlet_99", // defined in overrides as treatment
                Map.of("city", "Bangalore")
        );

        assertThat(eval.getVariantKey()).isEqualTo("treatment");
        assertThat(eval.getReason()).isEqualTo(EvaluationReason.OVERRIDE);
        assertThat(eval.isInExperiment()).isTrue();
    }

    @Test
    @DisplayName("Subject failing targeting rules falls back to control variant")
    void evaluateTargetingMismatch() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        ExperimentEvaluation eval = experimentService.evaluate(
                "courier_selection_strategy",
                "outlet_mumbai",
                Map.of("city", "Mumbai") // Target rule requires Bangalore
        );

        assertThat(eval.getReason()).isEqualTo(EvaluationReason.FALLBACK_NOT_TARGETED);
        assertThat(eval.getVariantKey()).isEqualTo("control");
        assertThat(eval.isControl()).isTrue();
        assertThat(eval.isInExperiment()).isFalse();
    }

    @Test
    @DisplayName("Paused experiment falls back to control variant with FALLBACK_NOT_ACTIVE")
    void evaluatePausedExperiment() {
        Experiment experiment = createBenchmarkExperiment();
        experiment.setStatus(ExperimentStatus.PAUSED);
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        ExperimentEvaluation eval = experimentService.evaluate(
                "courier_selection_strategy",
                "outlet_501",
                Map.of("city", "Bangalore")
        );

        assertThat(eval.getReason()).isEqualTo(EvaluationReason.FALLBACK_NOT_ACTIVE);
        assertThat(eval.getVariantKey()).isEqualTo("control");
        assertThat(eval.isControl()).isTrue();
    }

    @Test
    @DisplayName("Unknown experiment key returns FALLBACK_MISSING_EXPERIMENT")
    void evaluateUnknownExperiment() {
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of());
        when(experimentRepository.findByExperimentKeyWithVariants("non_existent")).thenReturn(Optional.empty());
        experimentService.reloadCache();

        ExperimentEvaluation eval = experimentService.evaluate("non_existent", "user_1");
        assertThat(eval.getReason()).isEqualTo(EvaluationReason.FALLBACK_MISSING_EXPERIMENT);
        assertThat(eval.getVariantKey()).isEqualTo("control");
    }

    @Test
    @DisplayName("Ambient evaluation reads subject from ExperimentContextHolder")
    void ambientContextEvaluation() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        ExperimentContext context = ExperimentContext.builder()
                .subjectId("test_outlet_99")
                .subjectType(SubjectType.RESTAURANT)
                .attribute("city", "Bangalore")
                .build();
        ExperimentContextHolder.set(context);

        ExperimentEvaluation eval = experimentService.evaluate("courier_selection_strategy");
        assertThat(eval.getVariantKey()).isEqualTo("treatment");
        assertThat(eval.getReason()).isEqualTo(EvaluationReason.OVERRIDE);
    }

    @Test
    @DisplayName("trackMetric saves conversion event to repository")
    void trackMetricRecording() {
        experimentService.trackMetric("outlet_501", "delivery_completed", new BigDecimal("185.50"), Map.of("provider", "PIDGE"));

        ArgumentCaptor<ExperimentMetricEvent> captor = ArgumentCaptor.forClass(ExperimentMetricEvent.class);
        verify(metricEventRepository).save(captor.capture());

        ExperimentMetricEvent saved = captor.getValue();
        assertThat(saved.getSubjectId()).isEqualTo("outlet_501");
        assertThat(saved.getMetricName()).isEqualTo("delivery_completed");
        assertThat(saved.getNumericValue()).isEqualTo(new BigDecimal("185.50"));
        assertThat(saved.getProperties()).contains("PIDGE");
    }

    @Test
    @DisplayName("getAnalytics computes conversion rate and values grouped by variant")
    void analyticsSummary() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findByExperimentKeyWithVariants("courier_selection_strategy"))
                .thenReturn(Optional.of(experiment));

        ExperimentExposureRepository.VariantExposureCount expControl = new ExperimentExposureRepository.VariantExposureCount() {
            @Override public String getVariantKey() { return "control"; }
            @Override public Long getExposures() { return 1000L; }
            @Override public Long getUniqueSubjects() { return 500L; }
        };
        ExperimentExposureRepository.VariantExposureCount expTreatment = new ExperimentExposureRepository.VariantExposureCount() {
            @Override public String getVariantKey() { return "treatment"; }
            @Override public Long getExposures() { return 1000L; }
            @Override public Long getUniqueSubjects() { return 500L; }
        };
        when(exposureRepository.getExposureSummary(eq("courier_selection_strategy"), any()))
                .thenReturn(List.of(expControl, expTreatment));

        ExperimentMetricEventRepository.MetricConversionSummary convControl = new ExperimentMetricEventRepository.MetricConversionSummary() {
            @Override public String getVariantKey() { return "control"; }
            @Override public Long getConversionCount() { return 50L; }
            @Override public Long getUniqueConvertedSubjects() { return 50L; }
            @Override public BigDecimal getTotalValue() { return new BigDecimal("5000.00"); }
            @Override public Double getAverageValue() { return 100.0; }
        };
        ExperimentMetricEventRepository.MetricConversionSummary convTreatment = new ExperimentMetricEventRepository.MetricConversionSummary() {
            @Override public String getVariantKey() { return "treatment"; }
            @Override public Long getConversionCount() { return 75L; }
            @Override public Long getUniqueConvertedSubjects() { return 75L; }
            @Override public BigDecimal getTotalValue() { return new BigDecimal("9000.00"); }
            @Override public Double getAverageValue() { return 120.0; }
        };
        when(metricEventRepository.getConversionSummary(eq("courier_selection_strategy"), eq("order_placed"), any()))
                .thenReturn(List.of(convControl, convTreatment));

        ExperimentDtos.ExperimentAnalyticsResponse analytics =
                experimentService.getAnalytics("courier_selection_strategy", "order_placed", Instant.now().minusSeconds(86400));

        assertThat(analytics.variants()).hasSize(2);

        ExperimentDtos.VariantAnalyticsDto controlDto = analytics.variants().stream()
                .filter(v -> "control".equals(v.variantKey())).findFirst().orElseThrow();
        assertThat(controlDto.conversionRatePct()).isEqualTo(10.0); // 50 / 500 = 10%
        assertThat(controlDto.uniqueConvertedSubjects()).isEqualTo(50L);

        ExperimentDtos.VariantAnalyticsDto treatmentDto = analytics.variants().stream()
                .filter(v -> "treatment".equals(v.variantKey())).findFirst().orElseThrow();
        assertThat(treatmentDto.conversionRatePct()).isEqualTo(15.0); // 75 / 500 = 15%
        assertThat(treatmentDto.uniqueConvertedSubjects()).isEqualTo(75L);
    }

    @Test
    @DisplayName("inspect provides complete transparent breakdown of bucketing without recording exposure")
    void inspectExperimentSimulation() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        com.costonomy.mp.experiment.dto.InspectionResult inspection =
                experimentService.inspect("courier_selection_strategy", "outlet_501", SubjectType.RESTAURANT, Map.of("city", "Bangalore"));

        assertThat(inspection.getExperimentKey()).isEqualTo("courier_selection_strategy");
        assertThat(inspection.getSubjectId()).isEqualTo("outlet_501");
        assertThat(inspection.isTargetingMatched()).isTrue();
        assertThat(inspection.getTrafficBucket()).isNotNull().isBetween(0, 9999);
        assertThat(inspection.getTrafficThreshold()).isEqualTo(10000);
        assertThat(inspection.isInTrafficRollout()).isTrue();
        assertThat(inspection.getVariantBucket()).isNotNull().isBetween(0, 99);
        assertThat(inspection.getAssignedVariantKey()).isIn("control", "treatment");
    }

    @Test
    @DisplayName("getLiveSnapshot returns real-time status of cache and running experiments")
    void getLiveSnapshotDetails() {
        Experiment experiment = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(experiment));
        experimentService.reloadCache();

        com.costonomy.mp.experiment.dto.LiveExperimentSnapshot snapshot = experimentService.getLiveSnapshot();
        assertThat(snapshot.getTotalExperimentsCached()).isEqualTo(1);
        assertThat(snapshot.getActiveExperimentsCount()).isEqualTo(1);
        assertThat(snapshot.getActiveExperiments()).hasSize(1);
        assertThat(snapshot.getEngineMetadata()).containsKey("hashingAlgorithm");
    }

    @Test
    @DisplayName("listExperiments filters by status, subjectType, and search keywords")
    void listExperimentsWithFiltering() {
        Experiment exp1 = createBenchmarkExperiment(); // ACTIVE, RESTAURANT, courier_selection_strategy

        Experiment exp2 = new Experiment();
        exp2.setId(2L);
        exp2.setExperimentKey("pricing_surge_v1");
        exp2.setName("Pricing Surge Test");
        exp2.setStatus(ExperimentStatus.DRAFT);
        exp2.setSubjectType(SubjectType.USER);
        exp2.setSalt("salt_2");
        exp2.addVariant(new ExperimentVariant("control", "Control", 50, true, null));

        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp1, exp2));

        assertThat(experimentService.listExperiments(ExperimentStatus.ACTIVE, null, null)).hasSize(1);
        assertThat(experimentService.listExperiments(ExperimentStatus.DRAFT, null, null)).hasSize(1);
        assertThat(experimentService.listExperiments(null, SubjectType.USER, null)).hasSize(1);
        assertThat(experimentService.listExperiments(null, null, "surge")).hasSize(1);
        assertThat(experimentService.listExperiments(null, null, "courier")).hasSize(1);
        assertThat(experimentService.listExperiments(null, null, "nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("evaluateBatch evaluates all active experiments when keys list is null or empty (UI bootstrap)")
    void evaluateBatchAllActive() {
        Experiment exp = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp));
        experimentService.reloadCache();

        ExperimentContext context = ExperimentContext.builder()
                .subjectId("outlet_501")
                .subjectType(SubjectType.RESTAURANT)
                .attribute("city", "Bangalore")
                .build();

        Map<String, ExperimentEvaluation> batch = experimentService.evaluateBatch(context, null);
        assertThat(batch).containsKey("courier_selection_strategy");
        assertThat(batch.get("courier_selection_strategy").isInExperiment()).isTrue();
    }

    @Test
    @DisplayName("recordExposure emits exposure event for client UI component renders")
    void recordClientExposure() {
        experimentService.recordExposure(
                "cart_redesign", "variant_a", "user_101", SubjectType.USER, Map.of("screen", "checkout")
        );

        verify(eventPublisher).publishEvent(any(com.costonomy.mp.experiment.service.ExperimentExposureEvent.class));
    }

    // ── Configuration Validation Tests ───────────────────────────────────

    @Test
    @DisplayName("Activation validation rejects negative variant weights")
    void validateNegativeWeights() {
        Experiment exp = createBenchmarkExperiment();
        exp.setStatus(ExperimentStatus.DRAFT);
        exp.getVariants().get(0).setWeight(-5);
        when(experimentRepository.findByExperimentKeyWithVariants("courier_selection_strategy")).thenReturn(Optional.of(exp));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("courier_selection_strategy", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects experiments without exactly one control variant")
    void validateControlVariantCount() {
        Experiment expNoControl = createBenchmarkExperiment();
        expNoControl.setStatus(ExperimentStatus.DRAFT);
        expNoControl.getVariants().forEach(v -> v.setControl(false));
        when(experimentRepository.findByExperimentKeyWithVariants("exp_no_control")).thenReturn(Optional.of(expNoControl));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_no_control", ExperimentStatus.ACTIVE)
        );

        Experiment expTwoControls = createBenchmarkExperiment();
        expTwoControls.setStatus(ExperimentStatus.DRAFT);
        expTwoControls.getVariants().forEach(v -> v.setControl(true));
        when(experimentRepository.findByExperimentKeyWithVariants("exp_two_controls")).thenReturn(Optional.of(expTwoControls));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_two_controls", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects experiments with zero or negative total variant weight")
    void validateZeroTotalWeight() {
        Experiment exp = createBenchmarkExperiment();
        exp.setStatus(ExperimentStatus.DRAFT);
        exp.getVariants().forEach(v -> v.setWeight(0));
        when(experimentRepository.findByExperimentKeyWithVariants("exp_zero_weight")).thenReturn(Optional.of(exp));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_zero_weight", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects invalid variant JSON payload")
    void validateInvalidVariantPayload() {
        Experiment exp = createBenchmarkExperiment();
        exp.setStatus(ExperimentStatus.DRAFT);
        exp.getVariants().get(0).setPayloadJson("{unclosed_json:true");
        when(experimentRepository.findByExperimentKeyWithVariants("exp_bad_payload")).thenReturn(Optional.of(exp));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_bad_payload", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects invalid targeting rules JSON")
    void validateInvalidTargetingRules() {
        Experiment exp = createBenchmarkExperiment();
        exp.setStatus(ExperimentStatus.DRAFT);
        exp.setTargetingRules("not-valid-json");
        when(experimentRepository.findByExperimentKeyWithVariants("exp_bad_rules")).thenReturn(Optional.of(exp));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_bad_rules", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects invalid overrides JSON")
    void validateInvalidOverrides() {
        Experiment exp = createBenchmarkExperiment();
        exp.setStatus(ExperimentStatus.DRAFT);
        exp.setOverrides("not-valid-json");
        when(experimentRepository.findByExperimentKeyWithVariants("exp_bad_overrides")).thenReturn(Optional.of(exp));

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_bad_overrides", ExperimentStatus.ACTIVE)
        );
    }

    @Test
    @DisplayName("Activation validation rejects layer traffic exceeding 100% across active experiments")
    void validateLayerTrafficOverflow() {
        Experiment exp1 = createBenchmarkExperiment();
        exp1.setExperimentKey("exp_checkout_1");
        exp1.setLayer("checkout_flow");
        exp1.setTrafficAllocationPct(60);
        exp1.setStatus(ExperimentStatus.ACTIVE);

        Experiment exp2 = createBenchmarkExperiment();
        exp2.setExperimentKey("exp_checkout_2");
        exp2.setLayer("checkout_flow");
        exp2.setTrafficAllocationPct(50);
        exp2.setStatus(ExperimentStatus.DRAFT);

        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp1));
        when(experimentRepository.findByExperimentKeyWithVariants("exp_checkout_2")).thenReturn(Optional.of(exp2));

        // 60% + 50% = 110% > 100% -> must be rejected
        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> experimentService.updateStatus("exp_checkout_2", ExperimentStatus.ACTIVE)
        );
    }

    // ── Layer Mutual Exclusion Tests ─────────────────────────────────────

    @Test
    @DisplayName("Deterministic layer bucketing guarantees mutual exclusion between active experiments in same layer")
    void layerMutualExclusion() {
        Experiment exp1 = createBenchmarkExperiment();
        exp1.setExperimentKey("checkout_exp_a");
        exp1.setLayer("checkout_layer");
        exp1.setTrafficAllocationPct(50);
        exp1.setStatus(ExperimentStatus.ACTIVE);

        Experiment exp2 = createBenchmarkExperiment();
        exp2.setExperimentKey("checkout_exp_b");
        exp2.setLayer("checkout_layer");
        exp2.setTrafficAllocationPct(50);
        exp2.setStatus(ExperimentStatus.ACTIVE);

        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp1, exp2));
        experimentService.reloadCache();

        // Test across 50 distinct subjects: each subject must enter AT MOST one experiment
        for (int i = 0; i < 50; i++) {
            String subjectId = "subject_" + i;
            ExperimentEvaluation evalA = experimentService.evaluate("checkout_exp_a", subjectId, Map.of("city", "Bangalore"));
            ExperimentEvaluation evalB = experimentService.evaluate("checkout_exp_b", subjectId, Map.of("city", "Bangalore"));

            boolean inA = evalA.isInExperiment();
            boolean inB = evalB.isInExperiment();

            // Mutual exclusion: both cannot be true!
            assertThat(inA && inB)
                    .withFailMessage("Subject %s entered both experiments in layer!", subjectId)
                    .isFalse();

            if (!inA) {
                assertThat(evalA.getReason()).isIn(EvaluationReason.FALLBACK_LAYER_EXCLUDED, EvaluationReason.FALLBACK_OUT_OF_TRAFFIC);
            }
            if (!inB) {
                assertThat(evalB.getReason()).isIn(EvaluationReason.FALLBACK_LAYER_EXCLUDED, EvaluationReason.FALLBACK_OUT_OF_TRAFFIC);
            }
        }
    }

    // ── Cache Consistency & Deletion Tests ───────────────────────────────

    @Test
    @DisplayName("Cache sync reloads fresh experiments and deleteExperiment tombstones stale cache state")
    void cacheSyncAndTombstoneOnDelete() {
        Experiment exp = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp));
        when(experimentRepository.findByExperimentKey("courier_selection_strategy")).thenReturn(Optional.of(exp));

        experimentService.reloadCache();
        assertThat(experimentService.getLiveSnapshot().getTotalExperimentsCached()).isEqualTo(1);

        // Delete experiment
        experimentService.deleteExperiment("courier_selection_strategy");
        verify(experimentRepository).delete(exp);
        assertThat(experimentService.getLiveSnapshot().getTotalExperimentsCached()).isEqualTo(0);

        // Evaluating deleted experiment returns fallback missing
        ExperimentEvaluation eval = experimentService.evaluate("courier_selection_strategy", "outlet_100");
        assertThat(eval.getReason()).isEqualTo(EvaluationReason.FALLBACK_MISSING_EXPERIMENT);
        assertThat(eval.isInExperiment()).isFalse();
    }

    // ── Explicit Attribution & Sanitization Tests ────────────────────────

    @Test
    @DisplayName("trackMetric saves explicit experimentKey, variantKey, and exposureId")
    void trackMetricExplicitAttribution() {
        experimentService.trackMetric(
                "user_88", "checkout_complete", new BigDecimal("150.00"), Map.of("orderId", 123),
                "cart_exp", "variant_treatment", 456L
        );

        ArgumentCaptor<ExperimentMetricEvent> captor = ArgumentCaptor.forClass(ExperimentMetricEvent.class);
        verify(metricEventRepository).save(captor.capture());

        ExperimentMetricEvent saved = captor.getValue();
        assertThat(saved.getSubjectId()).isEqualTo("user_88");
        assertThat(saved.getMetricName()).isEqualTo("checkout_complete");
        assertThat(saved.getNumericValue()).isEqualTo(new BigDecimal("150.00"));
        assertThat(saved.getExperimentKey()).isEqualTo("cart_exp");
        assertThat(saved.getVariantKey()).isEqualTo("variant_treatment");
        assertThat(saved.getExposureId()).isEqualTo(456L);
    }

    @Test
    @DisplayName("getActiveClientExperiments hides salt, overrides, and targeting rules")
    void activeClientExperimentsSanitized() {
        Experiment exp = createBenchmarkExperiment();
        when(experimentRepository.findAllWithVariants()).thenReturn(List.of(exp));
        experimentService.reloadCache();

        List<ExperimentDtos.ClientExperimentDto> clientDtos = experimentService.getActiveClientExperiments();
        assertThat(clientDtos).hasSize(1);
        ExperimentDtos.ClientExperimentDto dto = clientDtos.get(0);
        assertThat(dto.experimentKey()).isEqualTo("courier_selection_strategy");
        assertThat(dto.variants()).hasSize(2);
    }
}
