package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.SettlementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, Long> {

    List<SettlementAdjustment> findBySettlementIdOrderByIdAsc(Long settlementId);
}
