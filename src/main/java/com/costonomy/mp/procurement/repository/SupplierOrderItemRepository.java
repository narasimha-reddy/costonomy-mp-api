package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierOrderItemRepository extends JpaRepository<SupplierOrderItem, Long> {
    List<SupplierOrderItem> findBySupplierOrderId(Long supplierOrderId);
}
