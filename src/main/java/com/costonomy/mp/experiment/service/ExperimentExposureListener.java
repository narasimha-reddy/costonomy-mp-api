package com.costonomy.mp.experiment.service;

import com.costonomy.mp.experiment.domain.ExperimentExposure;
import com.costonomy.mp.experiment.repository.ExperimentExposureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronously persists experiment exposure / impression events with in-memory deduplication.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExperimentExposureListener {

    private static final long DEDUPLICATION_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final int MAX_CACHE_ENTRIES = 50_000;

    private final ExperimentExposureRepository exposureRepository;
    private final Map<String, Long> recentExposures = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void onExposureEvent(ExperimentExposureEvent event) {
        if (event == null || event.experimentKey() == null || event.subjectId() == null) {
            return;
        }

        String deduplicationKey = event.experimentKey() + ":" + event.subjectId();
        long now = System.currentTimeMillis();

        Long lastSeen = recentExposures.get(deduplicationKey);
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            // Already logged an exposure for this subject recently
            return;
        }

        // Periodically guard memory if map grows large
        if (recentExposures.size() > MAX_CACHE_ENTRIES) {
            recentExposures.entrySet().removeIf(e -> (now - e.getValue()) > DEDUPLICATION_WINDOW_MS);
        }

        recentExposures.put(deduplicationKey, now);

        try {
            ExperimentExposure exposure = new ExperimentExposure(
                    event.experimentKey(),
                    event.variantKey(),
                    event.subjectId(),
                    event.subjectType() != null ? event.subjectType().name() : "USER",
                    event.reason() != null ? event.reason().name() : "BUCKETED",
                    event.attributesSnapshot()
            );
            exposureRepository.save(exposure);
        } catch (Exception e) {
            log.warn("Failed to persist experiment exposure event for key '{}' subject '{}': {}",
                    event.experimentKey(), event.subjectId(), e.getMessage());
        }
    }
}
