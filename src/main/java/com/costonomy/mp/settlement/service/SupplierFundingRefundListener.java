package com.costonomy.mp.settlement.service;

import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.service.GatewayRefundOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Closes out a supplier's direct payment once its refund has gone through the gateway (D-204). */
@Component
@RequiredArgsConstructor
public class SupplierFundingRefundListener {

    private final JdbcTemplate jdbc;

    @EventListener
    public void onRefunded(GatewayRefundOutcome e) {
        if (!GatewayRefund.SUPPLIER_DELIVERY_FUNDING.equals(e.sourceType())) {
            return;
        }
        if ("UNUSABLE_PAYMENT".equals(e.purpose()) || "DUPLICATE_PAYMENT".equals(e.purpose())) {
            jdbc.update("""
                    update supplier_deduction set gateway_refund_id = ?, gateway_refunded_at = now(6),
                           version = version + 1, updated_at = now(6)
                     where id = ? and gateway_refunded_at is null
                    """, e.providerRefundId(), e.sourceId());
        }
    }
}
