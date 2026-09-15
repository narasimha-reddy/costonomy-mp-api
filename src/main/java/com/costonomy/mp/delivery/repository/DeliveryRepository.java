package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findBySupplierOrderId(Long supplierOrderId);

    Optional<Delivery> findByProviderCodeAndProviderDeliveryId(
            String providerCode, String providerDeliveryId);

    List<Delivery> findByStatusIn(List<DeliveryStatus> statuses);

    List<Delivery> findByOutletIdOrderByCreatedAtDesc(Long outletId);
}
