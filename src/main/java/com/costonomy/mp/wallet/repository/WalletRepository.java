package com.costonomy.mp.wallet.repository;

import com.costonomy.mp.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByOutletId(Long outletId);

    boolean existsByOutletId(Long outletId);

    @Query("select w.outletId from Wallet w where w.id = :walletId")
    Long outletIdOf(@Param("walletId") Long walletId);

    /**
     * The wallet, held until the transaction ends (D-104). A withdrawal decides
     * how much can go back to which card while holding it, so two withdrawals
     * cannot both spend the same credit.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.outletId = :outletId")
    Optional<Wallet> lockByOutletId(@Param("outletId") Long outletId);

    /**
     * Take money, but only if it is there.
     *
     * <p><b>One statement, and the guard is in the {@code where} clause.</b>
     * Reading the balance, comparing it in Java and then writing would let two
     * orders placed at the same instant each see enough and each spend it —
     * doc 10 §2's mandatory race, in the one place where losing it means an
     * order reaching a supplier with nothing behind it.
     *
     * <p><b>Clears the persistence context.</b> Without it the entity already
     * loaded in this transaction keeps the balance it had before the update,
     * and every ledger row records a {@code balance_after} that never happened —
     * which is the one column a statement cannot be read back without.
     *
     * @return 1 when the money was taken, 0 when the balance was short
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w
               set w.balance = w.balance - :amount, w.version = w.version + 1
             where w.id = :walletId and w.balance >= :amount and w.status = 'ACTIVE'
            """)
    int debit(@Param("walletId") Long walletId, @Param("amount") BigDecimal amount);

    /** Put money back. No guard: a balance can always grow. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w
               set w.balance = w.balance + :amount, w.version = w.version + 1
             where w.id = :walletId
            """)
    int credit(@Param("walletId") Long walletId, @Param("amount") BigDecimal amount);
}
