package com.costonomy.mp.supplier.repository;

import com.costonomy.mp.supplier.domain.SupplierOrganization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierOrganizationRepository extends JpaRepository<SupplierOrganization, Long> {
    Optional<SupplierOrganization> findByGstin(String gstin);
}
