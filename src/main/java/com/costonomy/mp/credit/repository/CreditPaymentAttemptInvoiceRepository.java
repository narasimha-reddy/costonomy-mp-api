package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPaymentAttemptInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditPaymentAttemptInvoiceRepository extends JpaRepository<CreditPaymentAttemptInvoice, Long> {

    List<CreditPaymentAttemptInvoice> findByAttemptIdOrderByCreditInvoiceIdAsc(Long attemptId);
}
