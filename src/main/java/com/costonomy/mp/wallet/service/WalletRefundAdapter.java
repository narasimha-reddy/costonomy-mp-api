package com.costonomy.mp.wallet.service;

import com.costonomy.mp.payment.service.RefundWalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Payments' way into a wallet, for refunds (D-104). */
@Component
@RequiredArgsConstructor
public class WalletRefundAdapter implements RefundWalletPort {

    private final WalletService wallets;

    @Override
    public void lock(Long outletId) {
        wallets.lock(outletId);
    }

    @Override
    public void creditRefund(Long outletId, Long supplierOrderId, Long refundId,
                             BigDecimal amount, String reason) {
        wallets.creditRefund(outletId, supplierOrderId, refundId, amount, reason);
    }

    @Override
    public void creditWithdrawalReversal(Long outletId, Long refundId, BigDecimal amount) {
        wallets.creditWithdrawalReversal(outletId, refundId, amount);
    }

    @Override
    public BigDecimal balanceOf(Long outletId) {
        return wallets.balanceOf(outletId);
    }
}
