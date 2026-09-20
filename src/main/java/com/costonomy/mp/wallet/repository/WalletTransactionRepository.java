package com.costonomy.mp.wallet.repository;

import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findBySupplierOrderIdAndDirection(
            Long supplierOrderId, WalletDirection direction);

    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);
}
