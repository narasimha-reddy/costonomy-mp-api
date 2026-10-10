package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.Settlement;
import com.costonomy.mp.settlement.domain.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findBySupplierStoreIdAndPeriodStartAndPeriodEnd(
            Long supplierStoreId, Instant periodStart, Instant periodEnd);

    List<Settlement> findBySupplierStoreIdOrderBySettlementDateDesc(Long supplierStoreId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id")
    Optional<Settlement> lockById(@Param("id") Long id);

    List<Settlement> findByStatusOrderByIdAsc(SettlementStatus status);
}
