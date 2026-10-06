package com.costonomy.mp.procurement.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionSkipDateRepository extends JpaRepository<SubscriptionSkipDate, Long> {

    List<SubscriptionSkipDate> findBySubscriptionId(Long subscriptionId);

    Optional<SubscriptionSkipDate> findBySubscriptionIdAndSkipDate(Long subscriptionId, LocalDate skipDate);

    boolean existsBySubscriptionIdAndSkipDate(Long subscriptionId, LocalDate skipDate);

    void deleteBySubscriptionIdAndSkipDate(Long subscriptionId, LocalDate skipDate);
}
