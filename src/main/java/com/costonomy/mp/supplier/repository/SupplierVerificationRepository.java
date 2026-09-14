package com.costonomy.mp.supplier.repository;

import com.costonomy.mp.supplier.domain.SupplierVerification;
import com.costonomy.mp.supplier.domain.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierVerificationRepository extends JpaRepository<SupplierVerification, Long> {

    List<SupplierVerification> findBySupplierOrganizationIdOrderByCreatedAtDesc(Long supplierOrganizationId);

    Optional<SupplierVerification> findFirstBySupplierOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long supplierOrganizationId, VerificationStatus status);
}
