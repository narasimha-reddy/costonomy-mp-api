package com.costonomy.mp.wallet.invoice.repository;

import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoiceWaiver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletEntryInvoiceWaiverRepository extends JpaRepository<WalletEntryInvoiceWaiver, Long> {

    Optional<WalletEntryInvoiceWaiver> findByWalletTransactionId(Long walletTransactionId);
}
