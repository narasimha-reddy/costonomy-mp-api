package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SkuHandlingDeclaration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkuHandlingDeclarationRepository extends JpaRepository<SkuHandlingDeclaration, Long> {

    Optional<SkuHandlingDeclaration> findBySupplierSkuIdAndEffectiveToIsNull(Long supplierSkuId);

    /** The current declaration, read with a lock: a snapshot read could miss one a concurrent declaration just closed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from SkuHandlingDeclaration d where d.supplierSkuId = :id and d.effectiveTo is null")
    Optional<SkuHandlingDeclaration> lockCurrent(@Param("id") Long supplierSkuId);

    List<SkuHandlingDeclaration> findBySupplierSkuIdOrderByIdAsc(Long supplierSkuId);
}
