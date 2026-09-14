package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

    List<CreditTransaction> findByCreditAgreementIdOrderByCreatedAtDescIdDesc(Long creditAgreementId);
}
