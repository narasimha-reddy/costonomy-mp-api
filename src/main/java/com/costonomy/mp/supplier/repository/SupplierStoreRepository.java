package com.costonomy.mp.supplier.repository;

import com.costonomy.mp.supplier.domain.SupplierStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierStoreRepository extends JpaRepository<SupplierStore, Long> {
    List<SupplierStore> findBySupplierOrganizationId(Long supplierOrganizationId);
    List<SupplierStore> findBySupplierOrganizationIdAndStatus(Long supplierOrganizationId, String status);
}
