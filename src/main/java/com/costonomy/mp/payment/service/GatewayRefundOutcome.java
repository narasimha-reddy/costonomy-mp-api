package com.costonomy.mp.payment.service;

import java.math.BigDecimal;

/**
 * Published inside the transaction that settles a gateway refund, so the owner of the money (a food delivery, a supplier
 * deduction) can record that it went back without the payment module knowing those modules (D-204).
 */
public record GatewayRefundOutcome(Long refundId, String sourceType, Long sourceId, String purpose, BigDecimal amount,
                                   String providerRefundId) {
}
