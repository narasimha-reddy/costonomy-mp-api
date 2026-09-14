package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditLimitHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditLimitHistoryRepository extends JpaRepository<CreditLimitHistory, Long> {

    List<CreditLimitHistory> findByCreditAgreementIdOrderByCreatedAtDesc(Long creditAgreementId);
}
