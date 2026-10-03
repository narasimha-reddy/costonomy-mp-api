package com.costonomy.mp.wallet.invoice.repository;

import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoicePage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletEntryInvoicePageRepository extends JpaRepository<WalletEntryInvoicePage, Long> {

    List<WalletEntryInvoicePage> findByInvoiceIdOrderByPageNo(Long invoiceId);

    void deleteByInvoiceId(Long invoiceId);
}
