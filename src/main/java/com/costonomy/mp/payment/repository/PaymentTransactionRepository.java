package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

    Optional<PaymentTransaction> findFirstByPaymentIdAndTransactionTypeAndStatusOrderByCreatedAtAsc(
            Long paymentId, String transactionType, String status);
}
