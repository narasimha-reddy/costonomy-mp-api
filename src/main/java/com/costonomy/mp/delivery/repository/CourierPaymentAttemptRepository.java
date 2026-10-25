package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.CourierPaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourierPaymentAttemptRepository extends JpaRepository<CourierPaymentAttempt, Long> {

    List<CourierPaymentAttempt> findByPayableIdOrderByAttemptNumberAsc(Long payableId);

    Optional<CourierPaymentAttempt> findFirstByPayableIdOrderByAttemptNumberDesc(Long payableId);
}
