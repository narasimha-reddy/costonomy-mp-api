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

    /**
     * This outlet's wallet if it has one, without opening it. For the read models (history and
     * statements): the wallet repository is held by this service alone, so that only it can move a balance.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Wallet> find(Long outletId) {
        return wallets.findByOutletId(outletId);
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
     * Credit a Razorpay top-up that has been captured (D-107). Idempotent on the
     * reference: the top-up id is the operation, so a second call is a no-op
     * rather than a second credit, whatever the caller did to get here.
     *
     * <p>Called by {@code WalletTopUpService} inside the transaction that moves
     * the top-up to CREDITED, with the wallet already locked and the limits
     * already checked — this method only moves the money and writes the ledger.
     */
    @Transactional
    public Wallet creditTopUp(Long outletId, Long topUpId, BigDecimal amount) {
        String reference = "topup-" + topUpId;
        var wallet = forOutlet(outletId);
        if (entries.existsByReference(reference)) {
            return wallet;
        }
        wallets.credit(wallet.getId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.CREDIT, WalletEntryKind.TOP_UP, amount,
                "Wallet top-up", reference, null);
        log.info("Wallet {} credited {} for top-up {}; balance {}",
                wallet.getId(), amount.toPlainString(), topUpId, refreshed.getBalance().toPlainString());
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
        // The locking read comes first, before anything reads the wallet or anything else. Under
        // REPEATABLE READ the first plain SELECT of a transaction fixes what every later plain SELECT
        // sees, so a check that ran before the lock was granted made a caller that had waited for the lock
        // read the world as it was before the caller ahead of it committed: a withdrawal decided
        // what a payment could still give back from figures that left out the withdrawal it had just waited
        // for (D-110). A locking read sees the latest committed row, and everything after it in this
        // transaction sees at least that.
        //
        // And locked before anything loads it as an entity: a wallet already in the persistence context
        // is not re-read by the locking query, only version-checked — so a withdrawal that waited on the
        // lock saw the balance from before the one ahead of it, and failed on a stale version instead of
        // finding the money gone.
        var locked = wallets.lockByOutletId(outletId);
        if (locked.isPresent()) {
            return locked.get();
        }
        forOutlet(outletId);
        wallets.flush();
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
        // An adjustment credit has already given part of the order's money back; an adjustment debit took more.
        var adjustedBack = entries.sumBySupplierOrderIdAndKindAndDirection(
                supplierOrderId, WalletEntryKind.ORDER_ADJUSTMENT, WalletDirection.CREDIT);
        var adjustedMore = entries.sumBySupplierOrderIdAndKindAndDirection(
                supplierOrderId, WalletEntryKind.ORDER_ADJUSTMENT, WalletDirection.DEBIT);
        return paid.add(adjustedMore).subtract(returned).subtract(refunded).subtract(adjustedBack)
                .max(BigDecimal.ZERO);
    }

    /**
     * A dispute refund on a wallet-paid order, back into the wallet. Idempotent on
     * the reference.
     */
    @Transactional
    public void creditDisputeRefund(Long supplierOrderId, BigDecimal amount, String reference) {
        // The order's payment row never changes, so a plain read of it says which wallet it was, before the lock.
        var debit = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "This order wasn't paid from the wallet."));
        // The outlet by a scalar query, not by loading the wallet: a wallet already
        // in the persistence context is not re-read by the lock (see lock()).
        lock(wallets.outletIdOf(debit.getWalletId()));
        // What the lock protects is read after it, and as a locking read: this may be one step of a caller's larger
        // transaction whose own plain reads see the ledger as it was at its first read, before the lock was granted.
        // A locking read sees what whoever held the wallet before us has committed. Not a second transaction for
        // the read: that would take a second connection while this one holds the wallet, and as many concurrent
        // refunds as the pool has connections would each wait for one that none of them can give back. One read of
        // the order's own entries, and the decisions are made from it: a locking read of the new reference would
        // deadlock two refunds on two wallets (see WalletTransactionRepository#lockedLedgerOf).
        var ledger = entries.lockedLedgerOf(supplierOrderId);
        if (ledger.stream().anyMatch(line -> reference.equals(line.getReference()))) {
            return;
        }
        BigDecimal refundable = refundableFrom(ledger);
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most ₹%s of this order can be refunded.".formatted(Rupees.of(refundable)));
        }
        wallets.credit(debit.getWalletId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(debit.getWalletId()).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, WalletEntryKind.DISPUTE_REFUND,
                amount, "Refund", reference, null);
    }

    /** {@link #refundableForOrder}, from the order's entries as one locking read read them. */
    private static BigDecimal refundableFrom(List<WalletTransactionRepository.LedgerLine> ledger) {
        var paid = sumOf(ledger, WalletEntryKind.ORDER_PAYMENT);
        var returned = sumOf(ledger, WalletEntryKind.ORDER_REFUND);
        var refunded = sumOf(ledger, WalletEntryKind.DISPUTE_REFUND, null);
        var adjustedBack = sumOf(ledger, WalletEntryKind.ORDER_ADJUSTMENT, WalletDirection.CREDIT);
        var adjustedMore = sumOf(ledger, WalletEntryKind.ORDER_ADJUSTMENT, WalletDirection.DEBIT);
        return paid.add(adjustedMore).subtract(returned).subtract(refunded).subtract(adjustedBack)
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal sumOf(List<WalletTransactionRepository.LedgerLine> ledger, WalletEntryKind kind) {
        return sumOf(ledger, kind, null);
    }

    /** @param direction restrict to credits or debits, or null for either */
    private static BigDecimal sumOf(List<WalletTransactionRepository.LedgerLine> ledger, WalletEntryKind kind,
                                    WalletDirection direction) {
        return ledger.stream()
                .filter(line -> kind.name().equals(line.getKind()))
                .filter(line -> direction == null || direction.name().equals(line.getDirection()))
                .map(WalletTransactionRepository.LedgerLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The order is ready: give back what it was paid beyond what it finally comes to (D-128).
     *
     * <p>The wallet paid the accepted total when the order was created. A catch-weight shortfall made the
     * final payable smaller, and this returns the difference in one credit, once: the reference is unique per
     * order, so a repeated ready (a retry, a duplicate event) credits nothing. A wallet order that weighed in
     * full, or has no catch-weight lines, owes nothing back and this writes nothing.
     */
    @Transactional
    public void settleOrder(Long supplierOrderId, BigDecimal finalPayable) {
        var debit = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT)
                .orElse(null);
        if (debit == null) {
            return;
        }
        String reference = "order-settle-" + supplierOrderId;
        lock(wallets.outletIdOf(debit.getWalletId()));
        var ledger = entries.lockedLedgerOf(supplierOrderId);
        if (ledger.stream().anyMatch(line -> reference.equals(line.getReference()))) {
            return;
        }
        BigDecimal owedBack = debit.getAmount().subtract(finalPayable);
        if (owedBack.signum() < 0) {
            // The buyer would owe more than the wallet took. Weighing never raises a price, so this is a
            // wrong figure upstream: refusing is better than a credit nobody can explain.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This order's final amount exceeds what the wallet paid for it.");
        }
        if (owedBack.signum() == 0) {
            return;
        }
        creditOrderAdjustmentLocked(debit.getWalletId(), supplierOrderId, ledger, owedBack, reference,
                "Order weighed lighter than ordered");
    }

    /**
     * A reduction after the goods left (a doorstep rejection) on a wallet-paid order: back into the wallet,
     * once per reference, never more than the order can still give back (D-128).
     */
    @Transactional
    public void creditOrderAdjustment(Long supplierOrderId, BigDecimal amount, String reference, String reason) {
        var debit = entries.findBySupplierOrderIdAndKind(supplierOrderId, WalletEntryKind.ORDER_PAYMENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "This order wasn't paid from the wallet."));
        lock(wallets.outletIdOf(debit.getWalletId()));
        var ledger = entries.lockedLedgerOf(supplierOrderId);
        if (ledger.stream().anyMatch(line -> reference.equals(line.getReference()))) {
            return;
        }
        creditOrderAdjustmentLocked(debit.getWalletId(), supplierOrderId, ledger, amount, reference, reason);
    }

    /** The credit itself, with the wallet locked and the order's ledger read under that lock. */
    private void creditOrderAdjustmentLocked(Long walletId, Long supplierOrderId,
                                             List<WalletTransactionRepository.LedgerLine> ledger,
                                             BigDecimal amount, String reference, String reason) {
        BigDecimal refundable = refundableFrom(ledger);
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most ₹%s of this order can be refunded.".formatted(Rupees.of(refundable)));
        }
        wallets.credit(walletId, amount);
        wallets.flush();

        var refreshed = wallets.findById(walletId).orElseThrow();
        record(refreshed, supplierOrderId, WalletDirection.CREDIT, WalletEntryKind.ORDER_ADJUSTMENT,
                amount, reason, reference, null);
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

    /**
     * Debit wallet balance for an IMPS/NEFT bank payout to verified account.
     */
    @Transactional
    public Wallet debitBankPayout(Long outletId, String payoutReference, BigDecimal amount, String maskedAccount) {
        var wallet = forOutlet(outletId);
        if (wallets.debit(wallet.getId(), amount) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet doesn't have ₹%s to transfer.".formatted(Rupees.of(amount)));
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.DEBIT, WalletEntryKind.BANK_PAYOUT, amount,
                "Bank transfer to " + maskedAccount, payoutReference, null);
        log.info("Wallet {} debited {} for bank payout {}; new balance {}",
                wallet.getId(), amount.toPlainString(), payoutReference, refreshed.getBalance().toPlainString());
        return refreshed;
    }

    /**
     * Reverse a failed bank payout back into the wallet. Idempotent on reference.
     */
    @Transactional
    public void returnBankPayout(Long outletId, String payoutReference, BigDecimal amount, String reason) {
        String reversalRef = "reversal-" + payoutReference;
        if (entries.existsByReference(reversalRef)) {
            return;
        }
        var wallet = forOutlet(outletId);
        wallets.credit(wallet.getId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.CREDIT, WalletEntryKind.BANK_PAYOUT_REVERSAL, amount,
                reason, reversalRef, null);
        log.info("Wallet {} credited {} for reversed bank payout {}; new balance {}",
                wallet.getId(), amount.toPlainString(), payoutReference, refreshed.getBalance().toPlainString());
    }

    /**
     * Take a QuickScan payment's money (D-106). Called with the wallet already
     * locked by the caller — {@code QuickScanService.payFromWallet} locks before
     * inserting the payment row, so the conditional debit below never races a
     * second click.
     *
     * @throws BusinessException VALIDATION_ERROR if the balance is short
     */
    @Transactional
    public void debitQuickScan(Long outletId, Long paymentId, BigDecimal total) {
        var wallet = forOutlet(outletId);
        if (wallets.debit(wallet.getId(), total) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet doesn't have ₹%s.".formatted(Rupees.of(total)));
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.DEBIT, WalletEntryKind.QUICKSCAN_PAYMENT, total,
                "QuickScan payment", "quickscan-" + paymentId, null);
    }

    /**
     * Take the money of a credit repayment (D-152): the restaurant's own cash moving to a supplier that
     * funded the credit. Called with the wallet already locked by the caller ({@link #lock}), inside the
     * caller's transaction, like {@link #debitQuickScan}; the caller is the pay-from-wallet endpoint (D-153).
     *
     * <p>The reference {@code credit-repayment-{repaymentId}} is unique, so a repayment debits once: a second
     * call is refused before anything moves, rather than relying on the unique key to roll it back.
     *
     * @param counterpartyName the supplier's name, so the row reads "Credit repayment to X" (D-158); blank
     *                         or null leaves the plain "Credit repayment"
     * @return the id of the ledger entry it wrote
     * @throws BusinessException FORBIDDEN if the wallet is on hold, VALIDATION_ERROR if the balance is short,
     *                           INVALID_STATE_TRANSITION if this repayment was already debited
     */
    @Transactional
    public Long debitCreditRepayment(Long outletId, Long repaymentId, BigDecimal amount, String counterpartyName) {
        String reference = "credit-repayment-" + repaymentId;
        if (entries.existsByReference(reference)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This repayment has already been taken from the wallet.");
        }
        var wallet = forOutlet(outletId);
        if (!wallet.isUsable()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This wallet is on hold. Please contact support.");
        }
        if (wallets.debit(wallet.getId(), amount) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet doesn't have ₹%s.".formatted(Rupees.of(amount)));
        }
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        return record(refreshed, null, WalletDirection.DEBIT, WalletEntryKind.CREDIT_REPAYMENT, amount,
                creditRepaymentReason(counterpartyName), reference, null);
    }

    private static final String CREDIT_REPAYMENT_REASON = "Credit repayment";
    /** wallet_transaction.reason is VARCHAR(200) (V31). */
    private static final int REASON_MAX = 200;

    /**
     * The ledger text of a credit repayment (D-158): "Credit repayment to {name}". A blank name leaves the
     * plain text. A name too long for the column is cut, never the prefix.
     */
    public static String creditRepaymentReason(String counterpartyName) {
        String name = counterpartyName == null ? "" : counterpartyName.strip();
        if (name.isEmpty()) {
            return CREDIT_REPAYMENT_REASON;
        }
        String prefix = CREDIT_REPAYMENT_REASON + " to ";
        int room = REASON_MAX - prefix.length();
        if (name.length() > room) {
            int end = room;
            if (Character.isHighSurrogate(name.charAt(end - 1))) {
                end--; // never leave half of a surrogate pair
            }
            name = name.substring(0, end).stripTrailing();
        }
        return prefix + name;
    }

    /**
     * Give a QuickScan payment's money back — the payout was refused or reversed
     * (D-106). Idempotent on the reference, so a payment failing once and a
     * REVERSED arriving for it later cannot return the money twice.
     */
    @Transactional
    public void returnQuickScan(Long outletId, Long paymentId, BigDecimal total, String reason) {
        String reference = "quickscan-return-" + paymentId;
        if (entries.existsByReference(reference)) {
            return;
        }
        var wallet = forOutlet(outletId);
        wallets.credit(wallet.getId(), total);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.CREDIT, WalletEntryKind.QUICKSCAN_RETURN, total,
                reason, reference, null);
    }

    /**
     * Put a withdrawal part the provider did not send back in the wallet (D-110).
     *
     * <p>Idempotent on the refund: the reference {@code withdrawal-reversal-{refundId}} is
     * unique, so a second call is a no-op and a racing second insert fails and rolls its own
     * credit back with it. Called with the wallet locked, by the reversal, after it has
     * decided; the credit is the atomic update every balance change goes through.
     */
    @Transactional
    public void creditWithdrawalReversal(Long outletId, Long refundId, BigDecimal amount) {
        String reference = "withdrawal-reversal-" + refundId;
        if (entries.existsByReference(reference)) {
            return;
        }
        var wallet = forOutlet(outletId);
        wallets.credit(wallet.getId(), amount);
        wallets.flush();

        var refreshed = wallets.findById(wallet.getId()).orElseThrow();
        record(refreshed, null, WalletDirection.CREDIT, WalletEntryKind.WITHDRAWAL_REVERSAL, amount,
                "Withdrawal returned to your wallet", reference, refundId);
        log.info("Wallet {} credited {} for reversed withdrawal refund {}; balance {}",
                wallet.getId(), amount.toPlainString(), refundId, refreshed.getBalance().toPlainString());
    }

    private Long record(Wallet wallet, Long supplierOrderId, WalletDirection direction,
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
        return entries.save(entry).getId();
    }
}
