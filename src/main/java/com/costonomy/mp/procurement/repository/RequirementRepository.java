package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.Requirement;
import com.costonomy.mp.procurement.domain.RequirementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    List<Requirement> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    List<Requirement> findByOutletIdAndStatusInOrderByCreatedAtDesc(
            Long outletId, Collection<RequirementStatus> statuses);
}
