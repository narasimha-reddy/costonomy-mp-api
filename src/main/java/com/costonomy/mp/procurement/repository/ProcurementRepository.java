package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.Procurement;
import com.costonomy.mp.procurement.domain.ProcurementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcurementRepository extends JpaRepository<Procurement, Long> {

    List<Procurement> findByOutletIdAndStatusInOrderByCreatedAtDesc(
            Long outletId, Collection<ProcurementStatus> statuses);

    /**
     * The outlet's open cart.
     *
     * <p>One DRAFT per outlet, so "add to cart" from anywhere in the app lands in
     * the same place — the restaurant has one cart, not one per screen.
     */
    Optional<Procurement> findFirstByOutletIdAndStatusOrderByCreatedAtDesc(
            Long outletId, ProcurementStatus status);

    List<Procurement> findByRequirementId(Long requirementId);
}
