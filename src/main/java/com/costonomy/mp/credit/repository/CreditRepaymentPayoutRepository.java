package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditRepaymentPayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreditRepaymentPayoutRepository extends JpaRepository<CreditRepaymentPayout, Long> {

    Optional<CreditRepaymentPayout> findByCreditRepaymentId(Long creditRepaymentId);
}
