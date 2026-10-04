package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SkuHandlingDeclaration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkuHandlingDeclarationRepository extends JpaRepository<SkuHandlingDeclaration, Long> {

    Optional<SkuHandlingDeclaration> findBySupplierSkuIdAndEffectiveToIsNull(Long supplierSkuId);

    List<SkuHandlingDeclaration> findBySupplierSkuIdOrderByIdAsc(Long supplierSkuId);
}
