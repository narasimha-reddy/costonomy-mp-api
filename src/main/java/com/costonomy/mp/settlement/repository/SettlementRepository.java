package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.Settlement;
import com.costonomy.mp.settlement.domain.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findBySupplierStoreIdAndPeriodStartAndPeriodEnd(
            Long supplierStoreId, Instant periodStart, Instant periodEnd);

    List<Settlement> findBySupplierStoreIdOrderBySettlementDateDesc(Long supplierStoreId);

    List<Settlement> findByStatusOrderByIdAsc(SettlementStatus status);
}
