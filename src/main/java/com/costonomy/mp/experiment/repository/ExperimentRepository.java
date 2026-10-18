package com.costonomy.mp.experiment.repository;

import com.costonomy.mp.experiment.domain.Experiment;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    @Query("SELECT e FROM Experiment e LEFT JOIN FETCH e.variants WHERE e.experimentKey = :experimentKey")
    Optional<Experiment> findByExperimentKeyWithVariants(String experimentKey);

    Optional<Experiment> findByExperimentKey(String experimentKey);

    boolean existsByExperimentKey(String experimentKey);

    @Query("SELECT DISTINCT e FROM Experiment e LEFT JOIN FETCH e.variants ORDER BY e.id DESC")
    List<Experiment> findAllWithVariants();

    @Query("SELECT DISTINCT e FROM Experiment e LEFT JOIN FETCH e.variants WHERE e.status = :status")
    List<Experiment> findAllByStatusWithVariants(ExperimentStatus status);

    List<Experiment> findAllByStatus(ExperimentStatus status);
}
