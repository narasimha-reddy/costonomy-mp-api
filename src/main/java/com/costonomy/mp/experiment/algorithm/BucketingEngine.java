package com.costonomy.mp.experiment.algorithm;

import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.costonomy.mp.experiment.domain.Experiment;
import com.costonomy.mp.experiment.domain.ExperimentVariant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic bucketing engine powered by MurmurHash3.
 * Guarantees stateless, reproducible, uniform assignment across variants with traffic gating
 * and deterministic layer-level mutual exclusion.
 */
@Slf4j
@Component
public class BucketingEngine {

    private static final int TRAFFIC_GRANULARITY = 10000; // 0.01% precision
    private final ObjectMapper objectMapper;

    public BucketingEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record BucketingResult(ExperimentVariant variant, EvaluationReason reason) {
    }

    public record DetailedBucketingResult(
            ExperimentVariant variant,
            EvaluationReason reason,
            Integer trafficBucket,
            Integer trafficThreshold,
            boolean inTrafficRollout,
            Integer variantBucket,
            Integer totalWeight
    ) {}

    public DetailedBucketingResult inspect(Experiment experiment, String subjectId) {
        return inspect(experiment, subjectId, Collections.emptyList());
    }

    public DetailedBucketingResult inspect(Experiment experiment, String subjectId, List<Experiment> activeLayerExperiments) {
        if (experiment == null || experiment.getVariants() == null || experiment.getVariants().isEmpty()) {
            return new DetailedBucketingResult(null, EvaluationReason.FALLBACK_MISSING_EXPERIMENT, null, null, false, null, null);
        }

        Optional<ExperimentVariant> overridden = checkOverrides(experiment, subjectId);
        if (overridden.isPresent()) {
            return new DetailedBucketingResult(overridden.get(), EvaluationReason.OVERRIDE, null, null, true, null, null);
        }

        boolean hasLayer = experiment.getLayer() != null && !experiment.getLayer().isBlank();
        int trafficBucket;
        int threshold;
        boolean inTraffic;
        EvaluationReason fallbackReason = EvaluationReason.FALLBACK_OUT_OF_TRAFFIC;

        if (hasLayer) {
            String layer = experiment.getLayer().trim();
            String layerKey = subjectId + ":layer:" + layer;
            trafficBucket = MurmurHash3.bucket(layerKey, TRAFFIC_GRANULARITY);

            List<Experiment> layerExps = new ArrayList<>(
                    activeLayerExperiments != null
                            ? activeLayerExperiments.stream().filter(e -> layer.equalsIgnoreCase(e.getLayer())).toList()
                            : List.of()
            );
            if (layerExps.stream().noneMatch(e -> e.getExperimentKey().equals(experiment.getExperimentKey()))) {
                layerExps.add(experiment);
            }
            layerExps.sort(Comparator.comparing(Experiment::getExperimentKey));

            int offset = 0;
            boolean allocatedToThis = false;
            boolean allocatedToOther = false;
            int thisSlice = 0;

            for (Experiment exp : layerExps) {
                int slice = Math.max(0, Math.min(100, exp.getTrafficAllocationPct())) * 100;
                int start = offset;
                int end = offset + slice;
                offset = end;

                if (trafficBucket >= start && trafficBucket < end) {
                    if (exp.getExperimentKey().equals(experiment.getExperimentKey())) {
                        allocatedToThis = true;
                        thisSlice = slice;
                    } else {
                        allocatedToOther = true;
                    }
                    break;
                }
            }

            threshold = thisSlice;
            inTraffic = allocatedToThis;
            if (allocatedToOther) {
                fallbackReason = EvaluationReason.FALLBACK_LAYER_EXCLUDED;
            }
        } else {
            int trafficPct = Math.max(0, Math.min(100, experiment.getTrafficAllocationPct()));
            String trafficKey = subjectId + ":" + experiment.getSalt() + ":traffic";
            trafficBucket = MurmurHash3.bucket(trafficKey, TRAFFIC_GRANULARITY);
            threshold = trafficPct * 100;
            inTraffic = trafficBucket < threshold;
        }

        List<ExperimentVariant> variants = experiment.getVariants();
        int totalWeight = variants.stream().mapToInt(ExperimentVariant::getWeight).sum();
        String variantKey = subjectId + ":" + experiment.getSalt() + ":variant";
        int variantBucket = totalWeight > 0 ? MurmurHash3.bucket(variantKey, totalWeight) : 0;

        if (!inTraffic) {
            ExperimentVariant control = experiment.getControlVariant().orElse(variants.get(0));
            return new DetailedBucketingResult(control, fallbackReason,
                    trafficBucket, threshold, false, variantBucket, totalWeight);
        }

        if (totalWeight <= 0) {
            ExperimentVariant control = experiment.getControlVariant().orElse(variants.get(0));
            return new DetailedBucketingResult(control, EvaluationReason.BUCKETED,
                    trafficBucket, threshold, true, variantBucket, totalWeight);
        }

        int cumulativeWeight = 0;
        for (ExperimentVariant variant : variants) {
            cumulativeWeight += variant.getWeight();
            if (variantBucket < cumulativeWeight) {
                return new DetailedBucketingResult(variant, EvaluationReason.BUCKETED,
                        trafficBucket, threshold, true, variantBucket, totalWeight);
            }
        }

        ExperimentVariant fallback = experiment.getControlVariant().orElse(variants.get(0));
        return new DetailedBucketingResult(fallback, EvaluationReason.BUCKETED,
                trafficBucket, threshold, true, variantBucket, totalWeight);
    }

    /**
     * Evaluates which variant a subject should receive.
     */
    public BucketingResult assign(Experiment experiment, String subjectId) {
        return assign(experiment, subjectId, Collections.emptyList());
    }

    public BucketingResult assign(Experiment experiment, String subjectId, List<Experiment> activeLayerExperiments) {
        if (experiment == null || experiment.getVariants() == null || experiment.getVariants().isEmpty()) {
            return new BucketingResult(null, EvaluationReason.FALLBACK_MISSING_EXPERIMENT);
        }

        // 1. Check direct subject whitelist / overrides
        Optional<ExperimentVariant> overridden = checkOverrides(experiment, subjectId);
        if (overridden.isPresent()) {
            return new BucketingResult(overridden.get(), EvaluationReason.OVERRIDE);
        }

        // 2. Traffic allocation gating (with deterministic layer-level mutual exclusion if layered)
        boolean hasLayer = experiment.getLayer() != null && !experiment.getLayer().isBlank();
        if (hasLayer) {
            String layer = experiment.getLayer().trim();
            String layerKey = subjectId + ":layer:" + layer;
            int layerBucket = MurmurHash3.bucket(layerKey, TRAFFIC_GRANULARITY);

            List<Experiment> layerExps = new ArrayList<>(
                    activeLayerExperiments != null
                            ? activeLayerExperiments.stream().filter(e -> layer.equalsIgnoreCase(e.getLayer())).toList()
                            : List.of()
            );
            if (layerExps.stream().noneMatch(e -> e.getExperimentKey().equals(experiment.getExperimentKey()))) {
                layerExps.add(experiment);
            }
            layerExps.sort(Comparator.comparing(Experiment::getExperimentKey));

            int offset = 0;
            boolean allocatedToThis = false;
            boolean allocatedToOther = false;

            for (Experiment exp : layerExps) {
                int slice = Math.max(0, Math.min(100, exp.getTrafficAllocationPct())) * 100;
                int start = offset;
                int end = offset + slice;
                offset = end;

                if (layerBucket >= start && layerBucket < end) {
                    if (exp.getExperimentKey().equals(experiment.getExperimentKey())) {
                        allocatedToThis = true;
                    } else {
                        allocatedToOther = true;
                    }
                    break;
                }
            }

            if (allocatedToOther) {
                ExperimentVariant control = experiment.getControlVariant().orElse(experiment.getVariants().get(0));
                return new BucketingResult(control, EvaluationReason.FALLBACK_LAYER_EXCLUDED);
            }
            if (!allocatedToThis) {
                ExperimentVariant control = experiment.getControlVariant().orElse(experiment.getVariants().get(0));
                return new BucketingResult(control, EvaluationReason.FALLBACK_OUT_OF_TRAFFIC);
            }
        } else {
            int trafficPct = Math.max(0, Math.min(100, experiment.getTrafficAllocationPct()));
            if (trafficPct < 100) {
                String trafficKey = subjectId + ":" + experiment.getSalt() + ":traffic";
                int trafficBucket = MurmurHash3.bucket(trafficKey, TRAFFIC_GRANULARITY);
                int threshold = trafficPct * 100;
                if (trafficBucket >= threshold) {
                    // Not in rollout traffic: assign control variant
                    ExperimentVariant control = experiment.getControlVariant().orElse(experiment.getVariants().get(0));
                    return new BucketingResult(control, EvaluationReason.FALLBACK_OUT_OF_TRAFFIC);
                }
            }
        }

        // 3. Variant distribution hashing
        List<ExperimentVariant> variants = experiment.getVariants();
        int totalWeight = variants.stream().mapToInt(ExperimentVariant::getWeight).sum();
        if (totalWeight <= 0) {
            ExperimentVariant control = experiment.getControlVariant().orElse(variants.get(0));
            return new BucketingResult(control, EvaluationReason.BUCKETED);
        }

        String variantKey = subjectId + ":" + experiment.getSalt() + ":variant";
        int variantBucket = MurmurHash3.bucket(variantKey, totalWeight);

        int cumulativeWeight = 0;
        for (ExperimentVariant variant : variants) {
            cumulativeWeight += variant.getWeight();
            if (variantBucket < cumulativeWeight) {
                return new BucketingResult(variant, EvaluationReason.BUCKETED);
            }
        }

        // Fallback safety to control or first variant
        ExperimentVariant fallback = experiment.getControlVariant().orElse(variants.get(0));
        return new BucketingResult(fallback, EvaluationReason.BUCKETED);
    }

    private Optional<ExperimentVariant> checkOverrides(Experiment experiment, String subjectId) {
        if (experiment.getOverrides() == null || experiment.getOverrides().isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, List<String>> overridesMap = objectMapper.readValue(
                    experiment.getOverrides(),
                    new TypeReference<>() {}
            );

            for (Map.Entry<String, List<String>> entry : overridesMap.entrySet()) {
                String targetVariantKey = entry.getKey();
                List<String> subjects = entry.getValue() != null ? entry.getValue() : Collections.emptyList();
                if (subjects.contains(subjectId)) {
                    return experiment.getVariantByKey(targetVariantKey);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse overrides for experiment '{}': {}", experiment.getExperimentKey(), e.getMessage());
        }

        return Optional.empty();
    }
}
