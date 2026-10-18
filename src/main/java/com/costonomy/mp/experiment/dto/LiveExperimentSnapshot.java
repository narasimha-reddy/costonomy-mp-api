package com.costonomy.mp.experiment.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live diagnostic snapshot of the experimentation engine at any given point in time.
 */
@Getter
@Builder
public class LiveExperimentSnapshot {

    private final Instant snapshotTimestamp;
    private final int totalExperimentsCached;
    private final int activeExperimentsCount;
    private final int pausedExperimentsCount;
    private final int draftExperimentsCount;
    private final Instant cacheLastRefreshedAt;
    private final List<ExperimentDtos.ExperimentDto> activeExperiments;
    private final Map<String, Object> engineMetadata;
}
