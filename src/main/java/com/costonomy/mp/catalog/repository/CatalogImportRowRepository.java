package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.CatalogImportRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogImportRowRepository extends JpaRepository<CatalogImportRow, Long> {

    List<CatalogImportRow> findByCatalogImportIdOrderByRowNumberAsc(Long catalogImportId);

    List<CatalogImportRow> findByCatalogImportIdAndStatusOrderByRowNumberAsc(
            Long catalogImportId, CatalogImportRow.Status status);

    long countByCatalogImportIdAndStatus(Long catalogImportId, CatalogImportRow.Status status);
}
