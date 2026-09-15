package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findBySupplierOrderIdOrderByCreatedAtDesc(Long supplierOrderId);

    List<Dispute> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    List<Dispute> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);
}
