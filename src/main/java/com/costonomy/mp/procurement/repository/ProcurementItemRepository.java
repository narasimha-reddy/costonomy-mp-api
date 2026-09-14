package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.ProcurementItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcurementItemRepository extends JpaRepository<ProcurementItem, Long> {

    List<ProcurementItem> findByProcurementIdAndStatus(Long procurementId, String status);

    Optional<ProcurementItem> findByProcurementIdAndSupplierSkuId(Long procurementId, Long skuId);
}
