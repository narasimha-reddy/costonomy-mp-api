package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>One transaction, with the wallet locked.</b> The amount is checked, split
 * across payments oldest credit first, each part written as a refund and taken
 * from the balance, all before anything commits. Two withdrawals of the whole
 * balance at the same moment therefore run one after the other, and the second
 * finds nothing left. The provider is called afterwards by the refund job, from
 * refunds that already exist — so a crash cannot send money the ledger does not
 * record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletWithdrawalService {

    private final WalletService wallets;
    private final RefundService refunds;

    @Transactional
    public WalletDtos.WithdrawalResponse withdraw(Long actorId, Long outletId, BigDecimal amount,
                                                  String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount greater than zero.");
        }

        var wallet = wallets.lock(outletId);
        if (!wallet.isUsable()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This wallet is on hold. Please contact support.");
        }
        if (amount.compareTo(wallet.getBalance()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Your wallet has ₹%s.".formatted(wallet.getBalance().toPlainString()));
        }

        var sources = refunds.withdrawable(outletId);
        BigDecimal returnable = sources.stream()
                .map(RefundService.Withdrawable::available)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(wallet.getBalance());
        if (amount.compareTo(returnable) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    ("₹%s can go back to your card or bank. The rest of your balance can be "
                            + "spent on orders.").formatted(returnable.toPlainString()));
        }

        var parts = new ArrayList<WalletDtos.WithdrawalPart>();
        BigDecimal left = amount;
        var balance = wallet.getBalance();
        for (var source : sources) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal part = left.min(source.available());
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
        return new WalletDtos.WithdrawalResponse(outletId, amount, balance, parts);
    }
}
