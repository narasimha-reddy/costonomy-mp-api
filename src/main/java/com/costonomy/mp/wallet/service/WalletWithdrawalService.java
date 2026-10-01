package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.payment.service.WithdrawalSources;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * Money out of a wallet, back to the card or bank it came from (D-104).
 *
 * <p><b>Only refund money can leave, and only to where it came from.</b> The
 * wallet is not a bank account: a withdrawal is a provider refund against the
 * payment each rupee was refunded from, so it can never reach an account the
 * restaurant did not pay with. A balance with no card behind it — a mock top-up,
 * a cancelled wallet order's return — stays spendable and cannot be withdrawn.
 *
 * <p><b>The debit is one transaction, with the wallet locked.</b> The amount is checked, split
 * across payments oldest credit first, each part written as a refund and taken from the
 * balance, all before anything commits. Two withdrawals of the whole balance at the same moment
 * therefore run one after the other, and the second finds nothing left. The provider is called
 * afterwards by the refund job, from refunds that already exist — so a crash cannot send money
 * the ledger does not record.
 *
 * <p><b>Before the debit, the provider is asked</b> (D-110) what each source payment can still give
 * back, with no transaction open (D-099); a source it will never take is blocked, and more than can
 * go back is refused whole with the amount that can. If it refuses a part after all, the money comes
 * back to the wallet on proof that it did not leave ({@code WithdrawalReversalService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletWithdrawalService {

    private final WalletService wallets;
    private final RefundService refunds;
    private final WithdrawalSources sources;
    private final org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * Not {@code @Transactional}: the provider is asked about the sources with no connection held
     * (D-099), and only the debit runs in a transaction. Three phases (D-110): read the sources,
     * ask the provider about them, then under the wallet lock recompute and debit.
     */
    public WalletDtos.WithdrawalResponse withdraw(Long actorId, Long outletId, BigDecimal amount,
                                                  String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount greater than zero.");
        }
        // Before any provider call: refunds are failing for a reason on our side (or this outlet has a double
        // credit waiting for a person), so a debit now would only be put back. The wallet is untouched and the
        // money is safe; nothing was attempted, so the client's key is released and the same request can be
        // sent again once the pause ends.
        sources.requireOpen(outletId);
        // The wallet's own limits first, without the lock, so a request that cannot succeed asks the
        // provider nothing. Checked again under the lock.
        requireWithdrawable(wallets.forOutlet(outletId), amount);

        var candidates = sources.candidates(outletId);
        var checked = sources.inspect(candidates, amount);

        // READ COMMITTED, so that once the wallet lock is granted every read below sees what the withdrawal
        // ahead of this one committed. Under the default REPEATABLE READ a plain SELECT sees the world as of the
        // transaction's first read, and what a payment can still give back would be decided from figures
        // that leave that withdrawal out (D-110). Nothing that matters changes while the wallet is held.
        var tx = new TransactionTemplate(txManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> {
            var wallet = wallets.lock(outletId);
            requireWithdrawable(wallet, amount);

            // The database again, now that nobody else can change this wallet's refunds.
            var plan = sources.allowed(refunds.withdrawable(outletId), checked, wallet.getBalance());
            if (amount.compareTo(plan.withdrawableNow()) > 0) {
                throw WithdrawalSources.exceeded(amount, plan);
            }

            var parts = new ArrayList<WalletDtos.WithdrawalPart>();
            BigDecimal left = amount;
            var balance = wallet.getBalance();
            for (var source : plan.parts()) {
                if (left.signum() == 0) {
                    break;
                }
                BigDecimal part = left.min(source.allowed());
                // Scoped to the outlet and the payment: the refund key is unique across
                // every refund, and a client's key is only unique to that client.
                var refund = refunds.requestWithdrawal(actorId, source.paymentId(), part,
                        "withdraw-%d-%s-%d".formatted(outletId, idempotencyKey, source.paymentId()));
                balance = wallets.debitWithdrawal(outletId, refund.getId(), part).getBalance();
                parts.add(new WalletDtos.WithdrawalPart(refund.getId(), source.paymentId(), part,
                        refund.getStatus().name()));
                left = left.subtract(part);
            }

            log.info("Wallet of outlet {} withdrew {} to {} payment(s); balance {}",
                    outletId, amount.toPlainString(), parts.size(), balance.toPlainString());
            return new WalletDtos.WithdrawalResponse(outletId, amount, balance, parts,
                    plan.checkedSources(), plan.uncheckedSources());
        });
    }

    private static void requireWithdrawable(com.costonomy.mp.wallet.domain.Wallet wallet, BigDecimal amount) {
        if (!wallet.isUsable()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This wallet is on hold. Please contact support.");
        }
        if (amount.compareTo(wallet.getBalance()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet has ₹%s.".formatted(Rupees.of(wallet.getBalance())));
        }
    }
}
