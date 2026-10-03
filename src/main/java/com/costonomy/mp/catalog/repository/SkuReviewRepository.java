package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SkuReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkuReviewRepository extends JpaRepository<SkuReview, Long> {

    List<SkuReview> findBySupplierSkuIdAndModerationStatusOrderByCreatedAtDesc(
            Long supplierSkuId, String moderationStatus);

    Optional<SkuReview> findBySupplierOrderItemId(Long supplierOrderItemId);
}
