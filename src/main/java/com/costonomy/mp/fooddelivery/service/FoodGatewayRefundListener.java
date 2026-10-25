package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.service.GatewayRefundOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Notes on the food delivery that a gateway refund of its payment has gone through (D-204). A refund of a payment that
 * could not be used (late, twice, wrong amount) closes that payment out; a refund of the fee (cancelled, no courier,
 * unused part) was already counted as given back when it was recorded, so only its history is added.
 */
@Component
@RequiredArgsConstructor
public class FoodGatewayRefundListener {

    private final JdbcTemplate jdbc;

    @EventListener
    public void onRefunded(GatewayRefundOutcome e) {
        if (!GatewayRefund.FOOD_DELIVERY.equals(e.sourceType())) {
            return;
        }
        if ("UNUSABLE_PAYMENT".equals(e.purpose()) || "DUPLICATE_PAYMENT".equals(e.purpose())) {
            jdbc.update("""
                    update food_delivery set gateway_refund_id = ?, gateway_refunded_at = now(6),
                           version = version + 1, updated_at = now(6)
                     where id = ? and gateway_refunded_at is null
                    """, e.providerRefundId(), e.sourceId());
        }
        jdbc.update("""
                insert into food_delivery_event (food_delivery_id, event_type, status, provider_code, description, occurred_at)
                select d.id, 'FoodDeliveryRefundedToGateway', d.status, d.provider_code, ?, now(6)
                  from food_delivery d where d.id = ?
                """, "₹%s refunded to the original payment method (%s)".formatted(e.amount().toPlainString(), e.purpose()),
                e.sourceId());
    }
}
