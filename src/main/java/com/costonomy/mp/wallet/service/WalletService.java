package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.repository.WalletRepository;
import com.costonomy.mp.wallet.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * An outlet's prepaid balance, and what moves it.
 *
 * <p>Every movement writes a ledger row alongside the balance, in the same
 * transaction. A balance that changed with nothing to explain it is a figure
 * nobody can dispute, and the first question about money is always "why".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository entries;

    /**
     * This outlet's wallet, opened on first sight.
     *
     * <p>Lazily rather than at outlet creation: a wallet is a balance, and an
     * outlet that has never used one does not need a row saying it has nothing.
     */
    @Transactional
    public Wallet forOutlet(Long outletId) {
        return wallets.findByOutletId(outletId).orElseGet(() -> {
            var wallet = new Wallet();
            wallet.setOutletId(outletId);
            return wallets.save(wallet);
        });
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(Long outletId) {
        return wallets.findByOutletId(outletId).map(Wallet::getBalance).orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> statement(Long outletId, int limit) {
        return wallets.findByOutletId(outletId)
                .map(wallet -> entries.findByWalletIdOrderByCreatedAtDesc(
                        wallet.getId(), PageRequest.of(0, limit)))
                .orElseGet(List::of);
    }

    /**
     * Put money in.
     *
     * <p><b>This stands in for a funding rail that does not exist yet.</b> Real
     * money reaches a wallet through a gateway, a bank transfer or an ops
     * adjustment, and each of those has a reconciliation story this does not.
     * It is here so the wallet can be used end to end; it is not the thing that
     * ships to a restaurant.
     */
    @Transactional
    public Wallet topUp(Long outletId, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount greater than zero.");
        }

        var wallet = forOutlet(outletId);
        wallets.credit(wallet.getId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.CREDIT, amount, reason);
        return refreshed;
    }

    /**
     * Take the money for an order, or report that it is not there.
     *
     * <p>The guard lives in the {@code update}'s {@code where} clause, so two
     * orders placed in the same instant cannot both pass it. A read, a compare
     * and a write would let them, and the loser would be an order sitting in
     * front of a supplier with nothing behind it.
     *
     * @return false when the balance was short — a refusal, not a failure
     */
    @Transactional
    public boolean debitFor(Long outletId, Long supplierOrderId, BigDecimal amount) {
        var wallet = forOutlet(outletId);

        // Already paid for. A retry that reached this far twice must not charge
        // twice, and the unique constraint on (order, direction) is the backstop.
        if (entries.findBySupplierOrderIdAndDirection(
                supplierOrderId, WalletDirection.DEBIT).isPresent()) {
            return true;
        }

        if (wallets.debit(wallet.getId(), amount) == 0) {
            log.info("Wallet {} could not cover {} for order {}",
                    wallet.getId(), amount, supplierOrderId);
            return false;
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.DEBIT, amount, "Order payment");
        return true;
    }

    /**
     * Put an order's money back.
     *
     * <p>Idempotent on the pair, like the debit: a cancellation that arrives
     * twice returns the money once.
     */
    @Transactional
    public void refundFor(Long supplierOrderId, String reason) {
        var debit = entries.findBySupplierOrderIdAndDirection(
                supplierOrderId, WalletDirection.DEBIT).orElse(null);
        if (debit == null) {
            return;
        }
        if (entries.findBySupplierOrderIdAndDirection(
                supplierOrderId, WalletDirection.CREDIT).isPresent()) {
            return;
        }

        wallets.credit(debit.getWalletId(), debit.getAmount());
        wallets.flush();

        var refreshed = wallets.findById(debit.getWalletId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, debit.getAmount(), reason);
    }

    /** Whether this order's money has been taken and not given back. */
    @Transactional(readOnly = true)
    public boolean isPaid(Long supplierOrderId) {
        return entries.findBySupplierOrderIdAndDirection(
                        supplierOrderId, WalletDirection.DEBIT).isPresent()
                && entries.findBySupplierOrderIdAndDirection(
                        supplierOrderId, WalletDirection.CREDIT).isEmpty();
    }

    private void record(Wallet wallet, Long supplierOrderId, WalletDirection direction,
                        BigDecimal amount, String reason) {
        var entry = new WalletTransaction();
        entry.setWalletId(wallet.getId());
        entry.setSupplierOrderId(supplierOrderId);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setBalanceAfter(wallet.getBalance());
        entry.setReason(reason);
        entries.save(entry);
    }
}
