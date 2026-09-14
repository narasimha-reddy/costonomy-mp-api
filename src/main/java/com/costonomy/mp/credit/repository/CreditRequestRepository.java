package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditRequestRepository extends JpaRepository<CreditRequest, Long> {

    List<CreditRequest> findByCreditAgreementIdOrderByCreatedAtDesc(Long creditAgreementId);

    Optional<CreditRequest> findFirstByCreditAgreementIdOrderByCreatedAtDesc(Long creditAgreementId);

    List<CreditRequest> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);
}
