package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SupplierSkuImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierSkuImageRepository extends JpaRepository<SupplierSkuImage, Long> {

    List<SupplierSkuImage> findBySupplierSkuIdOrderByPositionAscIdAsc(Long supplierSkuId);

    void deleteBySupplierSkuId(Long supplierSkuId);
}
