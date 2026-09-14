package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.CatalogImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogImportRepository extends JpaRepository<CatalogImport, Long> {
    List<CatalogImport> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);
}
