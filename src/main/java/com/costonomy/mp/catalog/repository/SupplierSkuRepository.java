package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SupplierSku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierSkuRepository extends JpaRepository<SupplierSku, Long> {

    /** Locked, so two handling declarations for one SKU take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupplierSku s where s.id = :id")
    Optional<SupplierSku> lockById(@Param("id") Long id);

    Page<SupplierSku> findBySupplierStoreId(Long supplierStoreId, Pageable pageable);

    Page<SupplierSku> findBySupplierStoreIdAndStatus(Long supplierStoreId, String status, Pageable pageable);

    Optional<SupplierSku> findBySupplierStoreIdAndSkuCode(Long supplierStoreId, String skuCode);

    List<SupplierSku> findBySupplierStoreIdAndCanonicalProductId(Long storeId, Long canonicalProductId);

    long countBySupplierStoreIdAndStatus(Long supplierStoreId, String status);
}
