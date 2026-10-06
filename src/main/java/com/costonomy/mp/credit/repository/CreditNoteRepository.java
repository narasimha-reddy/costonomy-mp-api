package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    Optional<CreditNote> findByIdempotencyKey(String idempotencyKey);

    List<CreditNote> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    Page<CreditNote> findByCreditAgreementIdOrderByIdDesc(Long creditAgreementId, Pageable pageable);

    List<CreditNote> findByIdIn(Collection<Long> ids);
}
