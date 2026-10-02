package com.costonomy.mp.payment.service;

import java.math.BigDecimal;

/**
 * Where a wallet refund's money lands (D-104). Implemented by the wallet module,
 * so payments can credit a wallet without reaching into it — the same shape as
 * procurement's {@code OrderFundingPort}.
 *
 * <p><b>Lock order: wallet, then payment.</b> A withdrawal holds the wallet and
 * then writes refunds against payments; a wallet refund must take the two in the
 * same order, or the two meet in the middle and MySQL kills one of them.
 */
public interface RefundWalletPort {

    /** Hold the outlet's wallet for the rest of the transaction, opening it if it has none. */
    void lock(Long outletId);

    /**
     * Credit a refund. Idempotent on the refund: the ledger's reference is unique,
     * so a second call for the same refund cannot move the balance twice.
     */
    void creditRefund(Long outletId, Long supplierOrderId, Long refundId,
                      BigDecimal amount, String reason);
}
