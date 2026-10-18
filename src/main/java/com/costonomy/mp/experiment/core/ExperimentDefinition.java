package com.costonomy.mp.experiment.core;

import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.domain.SubjectType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decoupled, framework-agnostic definition of an experiment.
 * Can be serialized/deserialized or used across any backend service.
 */
@Getter
@Builder
public class ExperimentDefinition {

    private final String experimentKey;
    private final String name;
    private final String description;
    @Builder.Default
    private final ExperimentStatus status = ExperimentStatus.DRAFT;
    @Builder.Default
    private final SubjectType subjectType = SubjectType.USER;
    @Builder.Default
    private final int trafficAllocationPct = 100;
    private final String salt;
    private final String layer;
    private final String targetingRules;
    private final String overrides;
    private final Instant startTime;
    private final Instant endTime;
    private final List<VariantDefinition> variants;

    public Optional<VariantDefinition> getControlVariant() {
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        return variants.stream()
                .filter(VariantDefinition::isControl)
                .findFirst()
                .or(() -> variants.stream().findFirst());
    }

    public Optional<VariantDefinition> getVariantByKey(String key) {
        if (variants == null || key == null) {
            return Optional.empty();
        }
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
