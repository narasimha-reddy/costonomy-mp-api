package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.domain.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);

    List<Refund> findByStatusIn(Collection<RefundStatus> statuses);
}
