package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SupplierSku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierSkuRepository extends JpaRepository<SupplierSku, Long> {

    Page<SupplierSku> findBySupplierStoreId(Long supplierStoreId, Pageable pageable);

    Page<SupplierSku> findBySupplierStoreIdAndStatus(Long supplierStoreId, String status, Pageable pageable);

    Optional<SupplierSku> findBySupplierStoreIdAndSkuCode(Long supplierStoreId, String skuCode);

    List<SupplierSku> findBySupplierStoreIdAndCanonicalProductId(Long storeId, Long canonicalProductId);

    long countBySupplierStoreIdAndStatus(Long supplierStoreId, String status);
}
