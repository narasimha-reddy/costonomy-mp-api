package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
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
        record(refreshed, null, WalletDirection.CREDIT, WalletEntryKind.TOP_UP, amount, reason, null, null);
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
        if (entries.findBySupplierOrderIdAndKind(
                supplierOrderId, WalletEntryKind.ORDER_PAYMENT).isPresent()) {
            return true;
        }

        if (wallets.debit(wallet.getId(), amount) == 0) {
            log.info("Wallet {} could not cover {} for order {}",
                    wallet.getId(), amount, supplierOrderId);
            return false;
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.DEBIT, WalletEntryKind.ORDER_PAYMENT,
                amount, "Order payment", null, null);
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
        var debit = entries.findBySupplierOrderIdAndKind(
                supplierOrderId, WalletEntryKind.ORDER_PAYMENT).orElse(null);
        if (debit == null) {
            return;
        }
        if (entries.findBySupplierOrderIdAndKind(
                supplierOrderId, WalletEntryKind.ORDER_REFUND).isPresent()) {
            return;
        }

        wallets.credit(debit.getWalletId(), debit.getAmount());
        wallets.flush();

        var refreshed = wallets.findById(debit.getWalletId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, WalletEntryKind.ORDER_REFUND,
                debit.getAmount(), reason, null, null);
    }

    /** Whether this order's money has been taken and not given back. */
    @Transactional(readOnly = true)
    public boolean isPaid(Long supplierOrderId) {
        return entries.findBySupplierOrderIdAndKind(
                        supplierOrderId, WalletEntryKind.ORDER_PAYMENT).isPresent()
                && entries.findBySupplierOrderIdAndKind(
                        supplierOrderId, WalletEntryKind.ORDER_REFUND).isEmpty();
    }

    /**
     * Hold this outlet's wallet until the transaction ends, opening it first if it
     * has none (D-104). Everything that decides what a wallet can give back does
     * so while holding it.
     */
    @Transactional
    public Wallet lock(Long outletId) {
        // Locked before anything loads it. A wallet already in the persistence
        // context is not re-read by the locking query, only version-checked — so a
        // withdrawal that waited on the lock saw the balance from before the one
        // ahead of it, and failed on a stale version instead of finding the money
        // gone.
        if (!wallets.existsByOutletId(outletId)) {
            forOutlet(outletId);
            wallets.flush();
        }
        return wallets.lockByOutletId(outletId).orElseThrow();
    }

    /**
     * Credit a refund of a card payment (D-104). Idempotent on the refund: the
     * reference is unique, so a second call is a no-op rather than a second credit.
     */
    @Transactional
    public void creditRefund(Long outletId, Long supplierOrderId, Long refundId,
                             BigDecimal amount, String reason) {
        String reference = "refund-" + refundId;
        if (entries.existsByReference(reference)) {
            return;
        }
        var wallet = forOutlet(outletId);
        wallets.credit(wallet.getId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, WalletEntryKind.REFUND,
                amount, reason, reference, refundId);
        log.info("Wallet {} credited {} for refund {}; balance {}",
                wallet.getId(), amount.toPlainString(), refundId, refreshed.getBalance().toPlainString());
    }

    /**
     * A wallet-paid order's money, as its payment status: PAID once the wallet paid
     * for it, REFUNDED once a cancellation gave it back, PARTIALLY_REFUNDED after a
     * dispute refund. Empty before the wallet has paid.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> paymentState(Long supplierOrderId) {
        if (entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_REFUND).isPresent()) {
            return java.util.Optional.of("REFUNDED");
        }
        if (entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                entries.sumBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.DISPUTE_REFUND).signum() > 0
                        ? "PARTIALLY_REFUNDED" : "PAID");
    }

    /**
     * What of a wallet-paid order can still come back: what it took, less a
     * cancellation's return and earlier dispute refunds (D-104).
     */
    @Transactional(readOnly = true)
    public BigDecimal refundableForOrder(Long supplierOrderId) {
        var paid = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT)
                .map(WalletTransaction::getAmount).orElse(BigDecimal.ZERO);
        var returned = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_REFUND)
                .map(WalletTransaction::getAmount).orElse(BigDecimal.ZERO);
        var refunded = entries.sumBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.DISPUTE_REFUND);
        return paid.subtract(returned).subtract(refunded).max(BigDecimal.ZERO);
    }

    /**
     * A dispute refund on a wallet-paid order, back into the wallet. Idempotent on
     * the reference.
     */
    @Transactional
    public void creditDisputeRefund(Long supplierOrderId, BigDecimal amount, String reference) {
        var debit = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "This order wasn't paid from the wallet."));
        if (entries.existsByReference(reference)) {
            return;
        }
        // The outlet by a scalar query, not by loading the wallet: a wallet already
        // in the persistence context is not re-read by the lock (see lock()).
        lock(wallets.outletIdOf(debit.getWalletId()));
        if (amount.compareTo(refundableForOrder(supplierOrderId)) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most ₹%s of this order can be refunded."
                            .formatted(Rupees.of(refundableForOrder(supplierOrderId))));
        }
        wallets.credit(debit.getWalletId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(debit.getWalletId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, WalletEntryKind.DISPUTE_REFUND,
                amount, "Refund", reference, null);
    }

    /**
     * Take the part of a withdrawal that one refund sends back to a card.
     *
     * <p>Called with the wallet locked and the amount already checked against the
     * balance, so a short balance here means something else moved it — which is
     * a bug, and the whole withdrawal rolls back rather than sending money the
     * wallet no longer has.
     */
    @Transactional
    public Wallet debitWithdrawal(Long outletId, Long refundId, BigDecimal amount) {
        var wallet = forOutlet(outletId);
        if (wallets.debit(wallet.getId(), amount) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet doesn't have ₹%s to withdraw.".formatted(Rupees.of(amount)));
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.DEBIT, WalletEntryKind.WITHDRAWAL, amount,
                "Withdrawal to the original payment method", "withdrawal-" + refundId, refundId);
        return refreshed;
    }

    private void record(Wallet wallet, Long supplierOrderId, WalletDirection direction,
                        WalletEntryKind kind, BigDecimal amount, String reason,
                        String reference, Long refundId) {
        var entry = new WalletTransaction();
        entry.setWalletId(wallet.getId());
        entry.setSupplierOrderId(supplierOrderId);
        entry.setDirection(direction);
        entry.setKind(kind);
        entry.setReference(reference);
        entry.setRefundId(refundId);
        entry.setAmount(amount);
        entry.setBalanceAfter(wallet.getBalance());
        entry.setReason(reason);
        entries.save(entry);
    }
}
