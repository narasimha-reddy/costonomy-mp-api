package com.costonomy.mp.billing.repository;

import com.costonomy.mp.billing.domain.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {
    List<CreditNote> findBySupplierOrderIdOrderByIdAsc(Long supplierOrderId);

    boolean existsBySupplierOrderIdAndReasonCode(Long supplierOrderId, String reasonCode);
    Optional<CreditNote> findByCreditNoteNumber(String creditNoteNumber);
    List<CreditNote> findBySupplierStoreIdOrderByIssuedAtDesc(Long supplierStoreId);
    List<CreditNote> findByOutletIdOrderByIssuedAtDesc(Long outletId);
}
