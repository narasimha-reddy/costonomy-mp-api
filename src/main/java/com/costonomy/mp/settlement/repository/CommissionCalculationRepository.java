package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.CommissionCalculation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommissionCalculationRepository
        extends JpaRepository<CommissionCalculation, Long> {

    Optional<CommissionCalculation> findBySupplierOrderId(Long supplierOrderId);

    List<CommissionCalculation> findBySettlementId(Long settlementId);

    List<CommissionCalculation> findBySupplierStoreIdAndSettlementIdIsNull(Long supplierStoreId);
}
