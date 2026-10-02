package com.costonomy.mp.procurement.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    List<Subscription> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);

    List<Subscription> findBySupplierStoreIdAndStatus(Long supplierStoreId, SubscriptionStatus status);

    List<Subscription> findByStatusAndNextDeliveryDateLessThanEqual(
            SubscriptionStatus status, LocalDate targetDate);
}
