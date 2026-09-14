package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditAgreementRepository extends JpaRepository<CreditAgreement, Long> {

    Optional<CreditAgreement> findByOutletIdAndSupplierStoreId(Long outletId, Long supplierStoreId);

    List<CreditAgreement> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    List<CreditAgreement> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);

    List<CreditAgreement> findByStatus(CreditAgreementStatus status);
}
