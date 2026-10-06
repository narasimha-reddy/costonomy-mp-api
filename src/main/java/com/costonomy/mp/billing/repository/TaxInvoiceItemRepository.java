package com.costonomy.mp.billing.repository;

import com.costonomy.mp.billing.domain.TaxInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxInvoiceItemRepository extends JpaRepository<TaxInvoiceItem, Long> {
    List<TaxInvoiceItem> findByTaxInvoiceIdOrderByIdAsc(Long taxInvoiceId);
}
