package com.costonomy.mp.wallet.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payouts from restaurant prepaid balance directly to verified bank accounts (IMPS/NEFT).
 * Solves the "trapped money" gap for catch-weight refunds, doorstep rejections, and top-ups.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletBankPayoutService {

    private final WalletService wallets;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final PlatformTransactionManager txManager;

    public WalletDtos.BankPayoutResponse initiatePayout(
            Long actorId, Long outletId, WalletDtos.BankPayoutRequest request, String idempotencyKey) {

        accessControl.requireScoped(actorId, Permissions.WALLET_WITHDRAW,
                ScopeType.OUTLET, outletId, "Outlet");

        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Amount must be greater than zero.");
        }

        String rawAccount = request.accountNumber().trim();
        String maskedAccount = rawAccount.length() > 4
                ? "••••" + rawAccount.substring(rawAccount.length() - 4)
                : "••••";

        String payoutRef = "payout-" + outletId + "-" + UUID.randomUUID().toString().substring(0, 8);

        var tx = new TransactionTemplate(txManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);

        return tx.execute(status -> {
            Wallet locked = wallets.lock(outletId);
            if (locked.getBalance().compareTo(request.amount()) < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Your wallet balance (₹%s) is insufficient to transfer ₹%s.".formatted(
                                Rupees.of(locked.getBalance()), Rupees.of(request.amount())));
            }

            Wallet refreshed = wallets.debitBankPayout(outletId, payoutRef, request.amount(), maskedAccount);

            auditService.record(actorId, null, "WALLET_BANK_PAYOUT_INITIATED", "WALLET",
                    refreshed.getId(), "ACTIVE", "ACTIVE",
                    "Bank payout of ₹%s initiated to %s (%s)".formatted(
                            Rupees.of(request.amount()), maskedAccount, request.ifscCode()),
                    "API");

            outbox.publish("WalletBankPayoutInitiated", "WALLET", refreshed.getId(),
                    Map.of("outletId", outletId,
                            "payoutReference", payoutRef,
                            "amount", request.amount().toPlainString(),
                            "accountNumberMasked", maskedAccount,
                            "ifscCode", request.ifscCode(),
                            "beneficiaryName", request.beneficiaryName()),
                    actorId);

            return new WalletDtos.BankPayoutResponse(
                    outletId,
                    payoutRef,
                    request.amount(),
                    refreshed.getBalance(),
                    request.beneficiaryName(),
                    maskedAccount,
                    request.ifscCode(),
                    "INITIATED",
                    Instant.now());
        });
    }
}
