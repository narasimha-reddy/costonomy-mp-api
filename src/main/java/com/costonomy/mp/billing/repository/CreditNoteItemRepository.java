package com.costonomy.mp.billing.repository;

import com.costonomy.mp.billing.domain.CreditNoteItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditNoteItemRepository extends JpaRepository<CreditNoteItem, Long> {
    List<CreditNoteItem> findByCreditNoteIdOrderByIdAsc(Long creditNoteId);
}
