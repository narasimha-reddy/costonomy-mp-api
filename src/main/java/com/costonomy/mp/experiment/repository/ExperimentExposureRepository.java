package com.costonomy.mp.experiment.repository;

import com.costonomy.mp.experiment.domain.ExperimentExposure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ExperimentExposureRepository extends JpaRepository<ExperimentExposure, Long> {

    long countByExperimentKey(String experimentKey);

    long countByExperimentKeyAndVariantKey(String experimentKey, String variantKey);

    List<ExperimentExposure> findTop100ByExperimentKeyOrderByExposedAtDesc(String experimentKey);

    interface VariantExposureCount {
        String getVariantKey();
        Long getExposures();
        Long getUniqueSubjects();
    }

    @Query("""
            SELECT e.variantKey as variantKey,
                   COUNT(e.id) as exposures,
                   COUNT(DISTINCT e.subjectId) as uniqueSubjects
            FROM ExperimentExposure e
            WHERE e.experimentKey = :experimentKey
              AND e.evaluationReason IN ('BUCKETED', 'OVERRIDE')
              AND (:since IS NULL OR e.exposedAt >= :since)
            GROUP BY e.variantKey
            """)
    List<VariantExposureCount> getExposureSummary(@Param("experimentKey") String experimentKey,
                                                  @Param("since") Instant since);
}
