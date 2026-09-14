package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.RequirementItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementItemRepository extends JpaRepository<RequirementItem, Long> {
    List<RequirementItem> findByRequirementId(Long requirementId);
}
