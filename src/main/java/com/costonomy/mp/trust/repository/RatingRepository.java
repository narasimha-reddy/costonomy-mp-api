package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.Rating;
import com.costonomy.mp.trust.domain.RatingModerationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    Optional<Rating> findBySupplierOrderId(Long supplierOrderId);

    List<Rating> findBySupplierStoreIdAndModerationStatusOrderByCreatedAtDesc(
            Long supplierStoreId, RatingModerationStatus moderationStatus);
}
