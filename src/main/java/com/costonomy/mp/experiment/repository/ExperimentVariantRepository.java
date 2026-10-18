package com.costonomy.mp.experiment.repository;

import com.costonomy.mp.experiment.domain.ExperimentVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, Long> {

    List<ExperimentVariant> findAllByExperimentId(Long experimentId);

    Optional<ExperimentVariant> findByExperimentIdAndVariantKey(Long experimentId, String variantKey);
}
