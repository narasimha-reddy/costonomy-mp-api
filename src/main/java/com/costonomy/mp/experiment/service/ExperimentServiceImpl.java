package com.costonomy.mp.experiment.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Core implementation of A/B testing and experimentation platform service.
 * Features bounded multi-instance cache synchronization, deterministic layer-level mutual exclusion,
 * strict activation validation, and unambiguous conversion attribution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExperimentServiceImpl implements ExperimentService {

    private final ExperimentRepository experimentRepository;
    private final ExperimentExposureRepository exposureRepository;
    private final ExperimentMetricEventRepository metricEventRepository;
    private final BucketingEngine bucketingEngine;
    private final TargetingRuleEvaluator ruleEvaluator;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** High-performance in-memory cache of experiments for hot evaluation paths. */
    private final AtomicReference<Map<String, Experiment>> experimentCacheRef = new AtomicReference<>(Map.of());

    /** Tombstone map of recently deleted experiment keys with timestamp to prevent resurrecting deleted entities. */
    private final Map<String, Instant> tombstonedKeys = new ConcurrentHashMap<>();

    private volatile Instant cacheLastRefreshedAt = Instant.now();

    @PostConstruct
    public void init() {
        reloadCache();
    }

    /**
     * Bounded cache sync across API instances. Runs every 30 seconds (configurable via costonomy.mp.experiment.cache-sync-interval).
     * Guarantees creates, updates, status transitions, and deletes propagate within 30 seconds across instances.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.experiment.cache-sync-interval:PT30S}")
    public void syncCache() {
        reloadCache();
    }

    @Override
    public void reloadCache() {
        try {
            List<Experiment> allFromDb = experimentRepository.findAllWithVariants();
            Map<String, Experiment> refreshed = new HashMap<>();
            for (Experiment exp : allFromDb) {
                refreshed.put(exp.getExperimentKey(), exp);
            }
            experimentCacheRef.set(Collections.unmodifiableMap(refreshed));
            cacheLastRefreshedAt = Instant.now();

            // Prune tombstones older than 10 minutes
            Instant cutoff = Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES);
            tombstonedKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));

            log.info("Loaded {} experiments into in-memory cache", refreshed.size());
        } catch (Exception e) {
            log.warn("Failed to reload experiments into cache: {}", e.getMessage());
        }
    }

    @Override
    public ExperimentEvaluation evaluate(String experimentKey, ExperimentContext context) {
        if (experimentKey == null || experimentKey.isBlank()) {
            return buildFallbackResult("unknown", "control", EvaluationReason.FALLBACK_MISSING_EXPERIMENT, null);
        }

        String subjectId = (context != null && context.getSubjectId() != null) ? context.getSubjectId() : "anonymous";
        SubjectType subjectType = (context != null && context.getSubjectType() != null) ? context.getSubjectType() : SubjectType.USER;
        Map<String, Object> attributes = (context != null && context.getAttributes() != null) ? context.getAttributes() : Collections.emptyMap();

        Experiment experiment = getOrFetchExperiment(experimentKey);
        if (experiment == null) {
            return buildFallbackResult(experimentKey, "control", EvaluationReason.FALLBACK_MISSING_EXPERIMENT, null);
        }

        Instant now = Instant.now();
        // 1. Status & schedule check
        if (!experiment.isCurrentlyRunning(now)) {
            ExperimentVariant control = experiment.getControlVariant().orElse(null);
            String variantKey = control != null ? control.getVariantKey() : "control";
            String payloadJson = control != null ? control.getPayloadJson() : null;
            return buildFallbackResult(experimentKey, variantKey, EvaluationReason.FALLBACK_NOT_ACTIVE, payloadJson);
        }

        // 2. Audience targeting rule evaluation
        if (!ruleEvaluator.matches(experiment.getTargetingRules(), attributes)) {
            ExperimentVariant control = experiment.getControlVariant().orElse(null);
            String variantKey = control != null ? control.getVariantKey() : "control";
            String payloadJson = control != null ? control.getPayloadJson() : null;
            return buildFallbackResult(experimentKey, variantKey, EvaluationReason.FALLBACK_NOT_TARGETED, payloadJson);
        }

        // 3. Bucketing (overrides, layer mutual exclusion, traffic rollout, and variant weights)
        List<Experiment> activeInLayer = getActiveExperimentsInLayer(experiment.getLayer());
        BucketingEngine.BucketingResult bucketingResult = bucketingEngine.assign(experiment, subjectId, activeInLayer);
        ExperimentVariant assignedVariant = bucketingResult.variant();
        EvaluationReason reason = bucketingResult.reason();

        String variantKey = assignedVariant != null ? assignedVariant.getVariantKey() : "control";
        boolean isControl = assignedVariant != null && assignedVariant.isControl();
        String payloadJson = assignedVariant != null ? assignedVariant.getPayloadJson() : null;

        // 4. Asynchronously emit exposure event for impressions tracking
        publishExposure(experimentKey, variantKey, subjectId, subjectType, reason, attributes);

        return ExperimentEvaluation.builder()
                .experimentKey(experimentKey)
                .variantKey(variantKey)
                .isControl(isControl)
                .inExperiment(reason.isInExperiment())
                .reason(reason)
                .payloadJson(payloadJson)
                .build();
    }

    @Override
    public ExperimentEvaluation evaluate(String experimentKey, String subjectId, Map<String, Object> attributes) {
        ExperimentContext context = ExperimentContext.of(subjectId, SubjectType.USER, attributes);
        return evaluate(experimentKey, context);
    }

    @Override
    public ExperimentEvaluation evaluate(String experimentKey, String subjectId) {
        return evaluate(experimentKey, subjectId, Collections.emptyMap());
    }

    @Override
    public ExperimentEvaluation evaluate(String experimentKey) {
        Optional<ExperimentContext> currentContext = ExperimentContextHolder.get();
        return currentContext.map(ctx -> evaluate(experimentKey, ctx))
                .orElseGet(() -> evaluate(experimentKey, "anonymous", Collections.emptyMap()));
    }

    @Override
    public String getVariant(String experimentKey, String subjectId, String defaultVariant) {
        try {
            ExperimentEvaluation evaluation = evaluate(experimentKey, subjectId);
            return evaluation.getVariantKey() != null ? evaluation.getVariantKey() : defaultVariant;
        } catch (Exception e) {
            log.warn("Error evaluating experiment '{}' for subject '{}': {}", experimentKey, subjectId, e.getMessage());
            return defaultVariant;
        }
    }

    @Override
    public boolean isEnabled(String experimentKey, String subjectId) {
        try {
            ExperimentEvaluation evaluation = evaluate(experimentKey, subjectId);
            return evaluation.isInExperiment() && !evaluation.isControl();
        } catch (Exception e) {
            log.warn("Error evaluating isEnabled for experiment '{}': {}", experimentKey, e.getMessage());
            return false;
        }
    }

    @Override
    public <T> T getPayload(String experimentKey, String subjectId, Class<T> clazz, T fallback) {
        try {
            ExperimentEvaluation evaluation = evaluate(experimentKey, subjectId);
            return evaluation.getPayloadAs(clazz, fallback);
        } catch (Exception e) {
            log.warn("Error retrieving payload for experiment '{}': {}", experimentKey, e.getMessage());
            return fallback;
        }
    }

    @Override
    @Transactional
    public void trackMetric(String subjectId, String metricName, BigDecimal value, Map<String, Object> properties) {
        trackMetric(subjectId, metricName, value, properties, null, null, null);
    }

    @Override
    @Transactional
    public void trackMetric(String subjectId, String metricName, BigDecimal value, Map<String, Object> properties,
                            String experimentKey, String variantKey, Long exposureId) {
        if (subjectId == null || metricName == null) {
            return;
        }
        String propertiesJson = null;
        if (properties != null && !properties.isEmpty()) {
            try {
                propertiesJson = objectMapper.writeValueAsString(properties);
            } catch (Exception e) {
                log.warn("Failed to serialize properties for metric {}: {}", metricName, e.getMessage());
            }
        }

        String resolvedExpKey = experimentKey;
        String resolvedVarKey = variantKey;

        if (exposureId != null && (resolvedExpKey == null || resolvedVarKey == null)) {
            Optional<ExperimentExposure> exposureOpt = exposureRepository.findById(exposureId);
            if (exposureOpt.isPresent()) {
                ExperimentExposure exp = exposureOpt.get();
                resolvedExpKey = exp.getExperimentKey();
                resolvedVarKey = exp.getVariantKey();
            }
        }

        ExperimentMetricEvent event = new ExperimentMetricEvent(
                subjectId,
                metricName,
                value,
                propertiesJson,
                resolvedExpKey,
                resolvedVarKey,
                exposureId
        );
        metricEventRepository.save(event);
    }

    @Override
    public Map<String, ExperimentEvaluation> evaluateBatch(ExperimentContext context, List<String> experimentKeys) {
        Map<String, ExperimentEvaluation> results = new HashMap<>();
        List<String> keysToEvaluate = (experimentKeys != null && !experimentKeys.isEmpty())
                ? experimentKeys
                : experimentCacheRef.get().values().stream()
                        .filter(e -> e.getStatus() == ExperimentStatus.ACTIVE)
                        .map(Experiment::getExperimentKey)
                        .toList();

        for (String key : keysToEvaluate) {
            results.put(key, evaluate(key, context));
        }
        return results;
    }

    @Override
    public void recordExposure(String experimentKey, String variantKey, String subjectId,
                               SubjectType subjectType, Map<String, Object> attributes) {
        publishExposure(experimentKey, variantKey, subjectId, subjectType, EvaluationReason.BUCKETED, attributes);
    }

    @Override
    public void trackMetric(String subjectId, String metricName) {
        trackMetric(subjectId, metricName, BigDecimal.ONE, Collections.emptyMap());
    }

    // ── Admin & Lifecycle Management ─────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentDtos.ExperimentDto> listExperiments() {
        return listExperiments(null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentDtos.ExperimentDto> listExperiments(ExperimentStatus status, SubjectType subjectType, String search) {
        List<Experiment> all = experimentRepository.findAllWithVariants();
        return all.stream()
                .filter(e -> status == null || e.getStatus() == status)
                .filter(e -> subjectType == null || e.getSubjectType() == subjectType)
                .filter(e -> {
                    if (search == null || search.isBlank()) {
                        return true;
                    }
                    String q = search.trim().toLowerCase();
                    return e.getExperimentKey().toLowerCase().contains(q)
                            || (e.getName() != null && e.getName().toLowerCase().contains(q))
                            || (e.getDescription() != null && e.getDescription().toLowerCase().contains(q));
                })
                .map(ExperimentDtos.ExperimentDto::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentDtos.ExperimentDto> getActiveExperiments() {
        return listExperiments(ExperimentStatus.ACTIVE, null, null);
    }

    @Override
    public List<ExperimentDtos.ClientExperimentDto> getActiveClientExperiments() {
        return experimentCacheRef.get().values().stream()
                .filter(e -> e.getStatus() == ExperimentStatus.ACTIVE)
                .map(ExperimentDtos.ClientExperimentDto::fromEntity)
                .toList();
    }

    @Override
    public com.costonomy.mp.experiment.dto.LiveExperimentSnapshot getLiveSnapshot() {
        List<ExperimentDtos.ExperimentDto> cachedDtos = experimentCacheRef.get().values().stream()
                .map(ExperimentDtos.ExperimentDto::fromEntity)
                .toList();

        int activeCount = (int) cachedDtos.stream().filter(e -> e.status() == ExperimentStatus.ACTIVE).count();
        int pausedCount = (int) cachedDtos.stream().filter(e -> e.status() == ExperimentStatus.PAUSED).count();
        int draftCount = (int) cachedDtos.stream().filter(e -> e.status() == ExperimentStatus.DRAFT).count();

        List<ExperimentDtos.ExperimentDto> activeRunning = cachedDtos.stream()
                .filter(e -> e.status() == ExperimentStatus.ACTIVE)
                .toList();

        Map<String, Object> metadata = Map.of(
                "hashingAlgorithm", "MurmurHash3 (32-bit)",
                "trafficGranularity", 10000,
                "cacheStrategy", "AtomicReference with bounded scheduled sync and tombstoning",
                "cacheSyncInterval", "30s",
                "jvmAvailableProcessors", Runtime.getRuntime().availableProcessors(),
                "jvmTotalMemoryBytes", Runtime.getRuntime().totalMemory()
        );

        return com.costonomy.mp.experiment.dto.LiveExperimentSnapshot.builder()
                .snapshotTimestamp(Instant.now())
                .totalExperimentsCached(experimentCacheRef.get().size())
                .activeExperimentsCount(activeCount)
                .pausedExperimentsCount(pausedCount)
                .draftExperimentsCount(draftCount)
                .cacheLastRefreshedAt(cacheLastRefreshedAt)
                .activeExperiments(activeRunning)
                .engineMetadata(metadata)
                .build();
    }

    @Override
    public com.costonomy.mp.experiment.dto.InspectionResult inspect(String experimentKey, String subjectId,
                                                                   SubjectType subjectType, Map<String, Object> attributes) {
        Experiment experiment = getOrFetchExperiment(experimentKey);
        if (experiment == null) {
            throw new NotFoundException("Experiment", experimentKey);
        }

        Instant now = Instant.now();
        boolean running = experiment.isCurrentlyRunning(now);
        boolean targetingMatched = ruleEvaluator.matches(experiment.getTargetingRules(), attributes);

        List<Experiment> activeInLayer = getActiveExperimentsInLayer(experiment.getLayer());
        BucketingEngine.DetailedBucketingResult details = bucketingEngine.inspect(experiment, subjectId, activeInLayer);
        ExperimentVariant assigned = details.variant();
        EvaluationReason finalReason = details.reason();

        if (!running) {
            finalReason = EvaluationReason.FALLBACK_NOT_ACTIVE;
            assigned = experiment.getControlVariant().orElse(assigned);
        } else if (!targetingMatched && finalReason != EvaluationReason.OVERRIDE) {
            finalReason = EvaluationReason.FALLBACK_NOT_TARGETED;
            assigned = experiment.getControlVariant().orElse(assigned);
        }

        String targetExplanation = targetingMatched ? "Targeting rules satisfied." : "Attributes did not match targeting criteria.";

        return com.costonomy.mp.experiment.dto.InspectionResult.builder()
                .experimentKey(experimentKey)
                .subjectId(subjectId)
                .assignedVariantKey(assigned != null ? assigned.getVariantKey() : "control")
                .isControl(assigned != null && assigned.isControl())
                .inExperiment(finalReason.isInExperiment())
                .reason(finalReason)
                .targetingMatched(targetingMatched)
                .targetingDetails(targetExplanation)
                .trafficBucket(details.trafficBucket())
                .trafficThreshold(details.trafficThreshold())
                .inTrafficRollout(details.inTrafficRollout())
                .variantBucket(details.variantBucket())
                .totalVariantWeight(details.totalWeight())
                .payloadJson(assigned != null ? assigned.getPayloadJson() : null)
                .evaluatedAt(now)
                .evaluatedAttributes(attributes)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentDtos.ExposureRecordDto> getRecentExposures(String experimentKey) {
        return exposureRepository.findTop100ByExperimentKeyOrderByExposedAtDesc(experimentKey).stream()
                .map(ExperimentDtos.ExposureRecordDto::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExperimentDtos.ExperimentDto getExperiment(String experimentKey) {
        Experiment experiment = experimentRepository.findByExperimentKeyWithVariants(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));
        return ExperimentDtos.ExperimentDto.fromEntity(experiment);
    }

    @Override
    @Transactional
    public ExperimentDtos.ExperimentDto createExperiment(ExperimentDtos.CreateExperimentRequest request) {
        if (experimentRepository.existsByExperimentKey(request.experimentKey())) {
            throw new BusinessException(ErrorCode.EXPERIMENT_ALREADY_EXISTS,
                    "Experiment with key '" + request.experimentKey() + "' already exists.");
        }

        Experiment experiment = new Experiment();
        experiment.setExperimentKey(request.experimentKey());
        experiment.setName(request.name());
        experiment.setDescription(request.description());
        experiment.setStatus(ExperimentStatus.DRAFT);
        experiment.setSubjectType(request.subjectType() != null ? request.subjectType() : SubjectType.USER);
        experiment.setTrafficAllocationPct(request.trafficAllocationPct() != null ? request.trafficAllocationPct() : 100);
        experiment.setSalt(UUID.randomUUID().toString());
        experiment.setLayer(request.layer());
        experiment.setTargetingRules(request.targetingRules());
        experiment.setOverrides(request.overrides());
        experiment.setStartTime(request.startTime());
        experiment.setEndTime(request.endTime());

        if (request.variants() != null && !request.variants().isEmpty()) {
            boolean hasControl = request.variants().stream().anyMatch(v -> Boolean.TRUE.equals(v.isControl()));
            for (int i = 0; i < request.variants().size(); i++) {
                ExperimentDtos.CreateVariantRequest reqVar = request.variants().get(i);
                boolean isControl = Boolean.TRUE.equals(reqVar.isControl()) || (!hasControl && i == 0);
                ExperimentVariant variant = new ExperimentVariant(
                        reqVar.variantKey(),
                        reqVar.name(),
                        reqVar.weight() != null ? reqVar.weight() : 50,
                        isControl,
                        reqVar.payloadJson()
                );
                variant.setDescription(reqVar.description());
                experiment.addVariant(variant);
            }
        }

        tombstonedKeys.remove(experiment.getExperimentKey());
        Experiment saved = experimentRepository.save(experiment);
        updateCacheWithExperiment(saved);
        return ExperimentDtos.ExperimentDto.fromEntity(saved);
    }

    @Override
    @Transactional
    public ExperimentDtos.ExperimentDto updateExperiment(String experimentKey, ExperimentDtos.UpdateExperimentRequest request) {
        Experiment experiment = experimentRepository.findByExperimentKeyWithVariants(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));

        experiment.setName(request.name());
        experiment.setDescription(request.description());
        if (request.trafficAllocationPct() != null) {
            experiment.setTrafficAllocationPct(request.trafficAllocationPct());
        }
        experiment.setLayer(request.layer());
        experiment.setTargetingRules(request.targetingRules());
        experiment.setOverrides(request.overrides());
        experiment.setStartTime(request.startTime());
        experiment.setEndTime(request.endTime());

        if (request.variants() != null) {
            experiment.getVariants().clear();
            boolean hasControl = request.variants().stream().anyMatch(v -> Boolean.TRUE.equals(v.isControl()));
            for (int i = 0; i < request.variants().size(); i++) {
                ExperimentDtos.CreateVariantRequest reqVar = request.variants().get(i);
                boolean isControl = Boolean.TRUE.equals(reqVar.isControl()) || (!hasControl && i == 0);
                ExperimentVariant variant = new ExperimentVariant(
                        reqVar.variantKey(),
                        reqVar.name(),
                        reqVar.weight() != null ? reqVar.weight() : 50,
                        isControl,
                        reqVar.payloadJson()
                );
                variant.setDescription(reqVar.description());
                experiment.addVariant(variant);
            }
        }

        if (experiment.getStatus() == ExperimentStatus.ACTIVE) {
            validateForActivation(experiment);
        }

        Experiment updated = experimentRepository.save(experiment);
        updateCacheWithExperiment(updated);
        return ExperimentDtos.ExperimentDto.fromEntity(updated);
    }

    @Override
    @Transactional
    public ExperimentDtos.ExperimentDto updateStatus(String experimentKey, ExperimentStatus status) {
        Experiment experiment = experimentRepository.findByExperimentKeyWithVariants(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));

        if (status == ExperimentStatus.ACTIVE) {
            validateForActivation(experiment);
        }

        experiment.setStatus(status);
        Experiment updated = experimentRepository.save(experiment);
        updateCacheWithExperiment(updated);
        return ExperimentDtos.ExperimentDto.fromEntity(updated);
    }

    @Override
    @Transactional
    public ExperimentDtos.ExperimentDto addOverride(String experimentKey, String variantKey, List<String> subjectIds) {
        Experiment experiment = experimentRepository.findByExperimentKeyWithVariants(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));

        Map<String, Set<String>> overridesMap = new HashMap<>();
        if (experiment.getOverrides() != null && !experiment.getOverrides().isBlank()) {
            try {
                Map<String, List<String>> existing = objectMapper.readValue(
                        experiment.getOverrides(),
                        new TypeReference<>() {}
                );
                for (Map.Entry<String, List<String>> entry : existing.entrySet()) {
                    overridesMap.put(entry.getKey(), new HashSet<>(entry.getValue()));
                }
            } catch (Exception e) {
                log.warn("Could not parse existing overrides for {}: {}", experimentKey, e.getMessage());
            }
        }

        Set<String> list = overridesMap.computeIfAbsent(variantKey, k -> new HashSet<>());
        if (subjectIds != null) {
            list.addAll(subjectIds);
        }

        try {
            experiment.setOverrides(objectMapper.writeValueAsString(overridesMap));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG, "Failed to serialize overrides");
        }

        if (experiment.getStatus() == ExperimentStatus.ACTIVE) {
            validateForActivation(experiment);
        }

        Experiment updated = experimentRepository.save(experiment);
        updateCacheWithExperiment(updated);
        return ExperimentDtos.ExperimentDto.fromEntity(updated);
    }

    @Override
    @Transactional
    public void deleteExperiment(String experimentKey) {
        Experiment experiment = experimentRepository.findByExperimentKey(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));

        experimentRepository.delete(experiment);
        experimentRepository.flush();

        // Tombstone immediately so this instance and concurrent lookups cannot resurrect it
        tombstonedKeys.put(experimentKey, Instant.now());

        // Atomically remove from in-memory cache
        Map<String, Experiment> updatedMap = new HashMap<>(experimentCacheRef.get());
        updatedMap.remove(experimentKey);
        experimentCacheRef.set(Collections.unmodifiableMap(updatedMap));

        log.info("Deleted experiment '{}' and tombstoned cache entry", experimentKey);
    }

    @Override
    @Transactional(readOnly = true)
    public ExperimentDtos.ExperimentAnalyticsResponse getAnalytics(String experimentKey, String metricName, Instant since) {
        Experiment experiment = experimentRepository.findByExperimentKeyWithVariants(experimentKey)
                .orElseThrow(() -> new NotFoundException("Experiment", experimentKey));

        // Get exposure counts (only BUCKETED and OVERRIDE, excluding FALLBACK_OUT_OF_TRAFFIC)
        List<ExperimentExposureRepository.VariantExposureCount> exposures =
                exposureRepository.getExposureSummary(experimentKey, since);
        Map<String, ExperimentExposureRepository.VariantExposureCount> exposureMap = exposures.stream()
                .collect(Collectors.toMap(ExperimentExposureRepository.VariantExposureCount::getVariantKey, e -> e));

        // Get conversion counts with unambiguous attribution
        Map<String, ExperimentMetricEventRepository.MetricConversionSummary> conversionMap = new HashMap<>();
        if (metricName != null && !metricName.isBlank()) {
            List<ExperimentMetricEventRepository.MetricConversionSummary> conversions =
                    metricEventRepository.getConversionSummary(experimentKey, metricName, since);
            for (var c : conversions) {
                conversionMap.put(c.getVariantKey(), c);
            }
        }

        List<ExperimentDtos.VariantAnalyticsDto> variantAnalytics = new ArrayList<>();
        for (ExperimentVariant variant : experiment.getVariants()) {
            String vKey = variant.getVariantKey();
            var expCount = exposureMap.get(vKey);
            long totalExp = expCount != null ? expCount.getExposures() : 0L;
            long uniqueSubjects = expCount != null ? expCount.getUniqueSubjects() : 0L;

            var conv = conversionMap.get(vKey);
            long convCount = conv != null ? conv.getConversionCount() : 0L;
            long uniqueConv = conv != null ? conv.getUniqueConvertedSubjects() : 0L;
            BigDecimal totalVal = conv != null ? conv.getTotalValue() : BigDecimal.ZERO;
            Double avgVal = conv != null ? conv.getAverageValue() : 0.0;

            double convRate = uniqueSubjects > 0
                    ? ((double) uniqueConv / uniqueSubjects) * 100.0
                    : 0.0;
            // Round to 2 decimal places
            double roundedRate = BigDecimal.valueOf(convRate).setScale(2, RoundingMode.HALF_UP).doubleValue();

            variantAnalytics.add(new ExperimentDtos.VariantAnalyticsDto(
                    vKey,
                    variant.getName(),
                    variant.isControl(),
                    totalExp,
                    uniqueSubjects,
                    convCount,
                    uniqueConv,
                    totalVal,
                    avgVal,
                    roundedRate
            ));
        }

        return new ExperimentDtos.ExperimentAnalyticsResponse(
                experimentKey,
                metricName,
                since,
                variantAnalytics
        );
    }

    /**
     * Validates experiment setup before activation:
     * - Valid JSON payload for each variant
     * - Valid JSON targeting rules and overrides
     * - Non-negative variant weights
     * - Exactly one control variant
     * - Positive total variant weight
     * - Layer traffic allocation does not exceed 100% across active experiments
     */
    private void validateForActivation(Experiment experiment) {
        if (experiment == null) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG, "Experiment cannot be null.");
        }

        // 1. Validate variants
        List<ExperimentVariant> variants = experiment.getVariants();
        if (variants == null || variants.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                    "Experiment must have at least one variant before activation.");
        }

        long controlCount = variants.stream().filter(ExperimentVariant::isControl).count();
        if (controlCount != 1) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                    "Experiment must have exactly one control variant before activation, found: " + controlCount);
        }

        for (ExperimentVariant v : variants) {
            if (v.getWeight() < 0) {
                throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                        "Variant weights must be non-negative. Variant '" + v.getVariantKey() + "' has weight " + v.getWeight());
            }
            if (v.getPayloadJson() != null && !v.getPayloadJson().isBlank()) {
                try {
                    objectMapper.readTree(v.getPayloadJson());
                } catch (Exception ex) {
                    throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                            "Variant '" + v.getVariantKey() + "' has invalid JSON payload: " + ex.getMessage());
                }
            }
        }

        int totalWeight = variants.stream().mapToInt(ExperimentVariant::getWeight).sum();
        if (totalWeight <= 0) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                    "Active experiment must have positive total variant weight.");
        }

        // 2. Validate targeting rules JSON
        if (experiment.getTargetingRules() != null && !experiment.getTargetingRules().isBlank()) {
            try {
                objectMapper.readTree(experiment.getTargetingRules());
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                        "Targeting rules must be valid JSON: " + ex.getMessage());
            }
        }

        // 3. Validate overrides JSON
        if (experiment.getOverrides() != null && !experiment.getOverrides().isBlank()) {
            try {
                objectMapper.readValue(experiment.getOverrides(), new TypeReference<Map<String, List<String>>>() {});
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                        "Overrides must be valid JSON: " + ex.getMessage());
            }
        }

        // 4. Validate traffic allocation percentage
        int traffic = experiment.getTrafficAllocationPct();
        if (traffic < 0 || traffic > 100) {
            throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                    "Traffic allocation percentage must be between 0 and 100.");
        }

        // 5. Validate layer traffic allocation if layer is configured
        if (experiment.getLayer() != null && !experiment.getLayer().isBlank()) {
            String layer = experiment.getLayer().trim();
            List<Experiment> activeInLayer = experimentRepository.findAllWithVariants().stream()
                    .filter(e -> e.getStatus() == ExperimentStatus.ACTIVE
                            && layer.equalsIgnoreCase(e.getLayer())
                            && !e.getExperimentKey().equals(experiment.getExperimentKey()))
                    .toList();

            int existingLayerTraffic = activeInLayer.stream()
                    .mapToInt(Experiment::getTrafficAllocationPct)
                    .sum();

            if (existingLayerTraffic + experiment.getTrafficAllocationPct() > 100) {
                throw new BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG,
                        "Total traffic allocation for active experiments in layer '" + layer + "' cannot exceed 100%. "
                                + "Existing active traffic: " + existingLayerTraffic + "%, proposed: " + experiment.getTrafficAllocationPct() + "%");
            }
        }
    }

    private List<Experiment> getActiveExperimentsInLayer(String layer) {
        if (layer == null || layer.isBlank()) {
            return Collections.emptyList();
        }
        return experimentCacheRef.get().values().stream()
                .filter(e -> e.getStatus() == ExperimentStatus.ACTIVE && layer.equalsIgnoreCase(e.getLayer()))
                .toList();
    }

    private void updateCacheWithExperiment(Experiment experiment) {
        Map<String, Experiment> updatedMap = new HashMap<>(experimentCacheRef.get());
        updatedMap.put(experiment.getExperimentKey(), experiment);
        experimentCacheRef.set(Collections.unmodifiableMap(updatedMap));
    }

    private Experiment getOrFetchExperiment(String experimentKey) {
        if (experimentKey == null || tombstonedKeys.containsKey(experimentKey)) {
            return null;
        }

        Experiment experiment = experimentCacheRef.get().get(experimentKey);
        if (experiment == null) {
            Optional<Experiment> fromDb = experimentRepository.findByExperimentKeyWithVariants(experimentKey);
            if (fromDb.isPresent()) {
                experiment = fromDb.get();
                updateCacheWithExperiment(experiment);
            }
        }
        return experiment;
    }

    private void publishExposure(String experimentKey, String variantKey, String subjectId,
                                 SubjectType subjectType, EvaluationReason reason, Map<String, Object> attributes) {
        String attributesSnapshot = null;
        if (attributes != null && !attributes.isEmpty()) {
            try {
                attributesSnapshot = objectMapper.writeValueAsString(attributes);
            } catch (Exception ignored) {
            }
        }
        eventPublisher.publishEvent(new ExperimentExposureEvent(
                experimentKey, variantKey, subjectId, subjectType, reason, attributesSnapshot
        ));
    }

    private ExperimentEvaluation buildFallbackResult(String experimentKey, String variantKey,
                                                     EvaluationReason reason, String payloadJson) {
        return ExperimentEvaluation.builder()
                .experimentKey(experimentKey)
                .variantKey(variantKey)
                .isControl(true)
                .inExperiment(false)
                .reason(reason)
                .payloadJson(payloadJson)
                .build();
    }
}
