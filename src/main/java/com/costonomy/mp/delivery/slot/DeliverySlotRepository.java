package com.costonomy.mp.delivery.slot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliverySlotRepository extends JpaRepository<DeliverySlot, Long> {

    List<DeliverySlot> findBySupplierStoreId(Long supplierStoreId);

    List<DeliverySlot> findBySupplierStoreIdAndActiveTrue(Long supplierStoreId);

    Optional<DeliverySlot> findByIdAndSupplierStoreId(Long id, Long supplierStoreId);
}
