package com.costonomy.mp.billing.repository;

import com.costonomy.mp.billing.domain.TaxInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {
    Optional<TaxInvoice> findBySupplierOrderId(Long supplierOrderId);
    Optional<TaxInvoice> findByInvoiceNumber(String invoiceNumber);
    List<TaxInvoice> findBySupplierStoreIdOrderByIssuedAtDesc(Long supplierStoreId);
    List<TaxInvoice> findByOutletIdOrderByIssuedAtDesc(Long outletId);
}
