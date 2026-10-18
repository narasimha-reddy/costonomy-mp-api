package com.costonomy.mp.experiment.dto;

import com.costonomy.mp.experiment.domain.EvaluationReason;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Detailed inspection and simulation result showing exactly how an assignment was made
 * at any given point in time.
 */
@Getter
@Builder
public class InspectionResult {

    private final String experimentKey;
    private final String subjectId;
    private final String assignedVariantKey;
    private final boolean isControl;
    private final boolean inExperiment;
    private final EvaluationReason reason;
    private final boolean targetingMatched;
    private final String targetingDetails;
    private final Integer trafficBucket;
    private final Integer trafficThreshold;
    private final boolean inTrafficRollout;
    private final Integer variantBucket;
    private final Integer totalVariantWeight;
    private final String payloadJson;
    private final Instant evaluatedAt;
    private final Map<String, Object> evaluatedAttributes;
}
