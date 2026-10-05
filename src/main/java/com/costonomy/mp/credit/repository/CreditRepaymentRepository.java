package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreditRepaymentRepository extends JpaRepository<CreditRepayment, Long> {

    Optional<CreditRepayment> findByIdempotencyKey(String idempotencyKey);
}
