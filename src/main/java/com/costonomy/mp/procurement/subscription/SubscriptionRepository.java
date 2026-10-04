package com.costonomy.mp.procurement.subscription;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    List<Subscription> findBySupplierStoreIdOrderByCreatedAtDesc(Long supplierStoreId);

    List<Subscription> findBySupplierStoreIdAndStatus(Long supplierStoreId, SubscriptionStatus status);

    List<Subscription> findByStatusAndNextDeliveryDateLessThanEqual(
            SubscriptionStatus status, LocalDate targetDate);

    /** Locked, so two generations for one subscription run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id")
    Optional<Subscription> lockById(@Param("id") Long id);

    /** Subscriptions in a status whose start/end span covers the date; the schedule decides the rest. */
    @Query("""
            select s.id from Subscription s
             where s.status = :status and s.startDate <= :date
               and (s.endDate is null or s.endDate >= :date)
             order by s.id
            """)
    List<Long> findIdsSpanning(@Param("status") SubscriptionStatus status, @Param("date") LocalDate date);
}
