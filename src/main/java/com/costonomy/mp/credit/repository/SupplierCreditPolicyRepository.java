package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.SupplierCreditPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierCreditPolicyRepository extends JpaRepository<SupplierCreditPolicy, Long> {

    Optional<SupplierCreditPolicy> findBySupplierStoreId(Long supplierStoreId);
}
