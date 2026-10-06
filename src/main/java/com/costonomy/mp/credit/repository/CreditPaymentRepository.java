package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {

    Optional<CreditPayment> findByIdempotencyKey(String idempotencyKey);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtAsc(Long creditInvoiceId);

    List<CreditPayment> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    List<CreditPayment> findByCreditInvoiceIdInOrderByIdAsc(java.util.Collection<Long> creditInvoiceIds);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtDescIdDesc(Long creditInvoiceId);
}
