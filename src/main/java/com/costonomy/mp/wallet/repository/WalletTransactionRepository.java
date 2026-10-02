package com.costonomy.mp.wallet.repository;

import com.costonomy.mp.wallet.domain.WalletTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /**
     * An order's payment from the wallet, or its return. By kind, not direction:
     * a card-paid order can also have refund credits against it (D-104), and there
     * may be several.
     */
    Optional<WalletTransaction> findBySupplierOrderIdAndKind(
            Long supplierOrderId, com.costonomy.mp.wallet.domain.WalletEntryKind kind);

    boolean existsByReference(String reference);

    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);
}
