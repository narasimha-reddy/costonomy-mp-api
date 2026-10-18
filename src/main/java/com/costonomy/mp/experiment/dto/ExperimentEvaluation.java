package com.costonomy.mp.experiment.dto;

import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Result of an experiment evaluation for a specific subject.
 */
@Getter
@Builder
@Slf4j
public class ExperimentEvaluation {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String experimentKey;
    private final String variantKey;
    private final boolean isControl;
    private final boolean inExperiment;
    private final EvaluationReason reason;
    private final String payloadJson;

    /**
     * Strongly-typed deserialization of the variant's payload JSON.
     * Returns fallback if payload is empty or cannot be parsed.
     */
    public <T> T getPayloadAs(Class<T> clazz, T fallback) {
        if (payloadJson == null || payloadJson.isBlank() || clazz == null) {
            return fallback;
        }
        try {
            return OBJECT_MAPPER.readValue(payloadJson, clazz);
        } catch (Exception e) {
            log.warn("Failed to parse payload for experiment '{}' variant '{}': {}",
                    experimentKey, variantKey, e.getMessage());
            return fallback;
        }
    }

    public <T> T getPayloadAs(Class<T> clazz) {
        return getPayloadAs(clazz, null);
    }
}
