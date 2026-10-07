package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditInvoiceNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreditInvoiceNoteRepository extends JpaRepository<CreditInvoiceNote, Long> {

    Optional<CreditInvoiceNote> findByIdempotencyKey(String idempotencyKey);

    List<CreditInvoiceNote> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    Page<CreditInvoiceNote> findByCreditAgreementIdOrderByIdDesc(Long creditAgreementId, Pageable pageable);

    List<CreditInvoiceNote> findByIdIn(Collection<Long> ids);
}
