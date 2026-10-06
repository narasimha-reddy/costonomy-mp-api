package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditDueExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditDueExtensionRepository extends JpaRepository<CreditDueExtension, Long> {

    /** An invoice's extensions, newest first. */
    List<CreditDueExtension> findByCreditInvoiceIdOrderByIdDesc(Long creditInvoiceId);

    /** The first extension, whose old due date is the invoice's original one. */
    Optional<CreditDueExtension> findFirstByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);
}
