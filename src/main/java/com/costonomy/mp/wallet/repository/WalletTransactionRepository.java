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

    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(t.amount), 0) from WalletTransaction t
             where t.supplierOrderId = :orderId and t.kind = :kind
            """)
    java.math.BigDecimal sumBySupplierOrderIdAndKind(
            @org.springframework.data.repository.query.Param("orderId") Long supplierOrderId,
            @org.springframework.data.repository.query.Param("kind") com.costonomy.mp.wallet.domain.WalletEntryKind kind);

    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(t.amount), 0) from WalletTransaction t
             where t.supplierOrderId = :orderId and t.kind = :kind and t.direction = :direction
            """)
    java.math.BigDecimal sumBySupplierOrderIdAndKindAndDirection(
            @org.springframework.data.repository.query.Param("orderId") Long supplierOrderId,
            @org.springframework.data.repository.query.Param("kind") com.costonomy.mp.wallet.domain.WalletEntryKind kind,
            @org.springframework.data.repository.query.Param("direction") com.costonomy.mp.wallet.domain.WalletDirection direction);

    /** One entry of an order's ledger, as {@link #lockedLedgerOf} returns it: what the dispute refund decides on. */
    interface LedgerLine {
        String getKind();

        /** CREDIT or DEBIT: an adjustment can be either, and the two move what is refundable opposite ways. */
        String getDirection();

        java.math.BigDecimal getAmount();

        String getReference();
    }

    /**
     * Every entry of an order, in one locking read: it sees what has been committed now, whatever this transaction
     * saw first (REPEATABLE READ gives plain reads the snapshot of the first one). For a decision taken after the
     * wallet lock in a transaction that has already read: on the connection it already holds, so it needs no second
     * one (D-110).
     *
     * <p><b>By the order, never by the reference.</b> A locking read of a key that is not there yet (a new dispute
     * refund's reference) takes a shared lock on the gap where it would be. Two transactions on two wallets, whose
     * new references are neighbours, then both hold that gap and both need to insert into it: InnoDB rolls one of
     * them back with a deadlock, every time they overlap. The order's own entries are always there (its payment is
     * the first), so this read locks rows that exist, and only in this order's part of the index: two orders never
     * wait on each other. The unique index on the reference stays as the backstop.
     *
     * <p><b>The index is forced.</b> A locking read locks every row it scans, and on a table small enough for the
     * optimizer to prefer reading it whole that is every row and the gap after the last one, which is exactly where
     * every other order's new entry goes: the deadlock is back. It is the order's own index or nothing.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select kind as kind, direction as direction, amount as amount, reference as reference
              from wallet_transaction force index (ix_wallet_txn_order) where supplier_order_id = :orderId for share
            """)
    List<LedgerLine> lockedLedgerOf(@org.springframework.data.repository.query.Param("orderId") Long supplierOrderId);

    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);
}
