package com.costonomy.mp.intent.service;

import com.costonomy.mp.common.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * When a supplier's own delivery charge is high enough to warn the restaurant (D-144).
 *
 * <p>The restaurant sees the amount before ordering and can collect instead, so this is a prompt, not a limit. A
 * charge is high when it is at least a share of the goods value <b>and</b> at least a floor in rupees: the share alone
 * would flag every small order (₹40 on ₹200 is 20%), the floor alone would ignore a charge that doubles a small order.
 * Both are configuration ({@code delivery.highCharge.percent}, default 10; {@code delivery.highCharge.minAmount},
 * default 100), kept here so every screen agrees.
 */
@Component
@RequiredArgsConstructor
public class DeliveryChargeWarning {

    static final BigDecimal DEFAULT_PERCENT = new BigDecimal("10");
    static final BigDecimal DEFAULT_MIN_AMOUNT = new BigDecimal("100");

    private final AppConfigService config;

    /** @param charge what the supplier charges for delivery; @param goodsValue the goods before GST. */
    public boolean isHigh(BigDecimal charge, BigDecimal goodsValue) {
        if (charge == null || goodsValue == null || charge.signum() <= 0 || goodsValue.signum() <= 0) {
            return false;
        }
        BigDecimal percent = config.getDecimal("delivery.highCharge.percent", DEFAULT_PERCENT);
        BigDecimal floor = config.getDecimal("delivery.highCharge.minAmount", DEFAULT_MIN_AMOUNT);
        BigDecimal share = goodsValue.multiply(percent).divide(BigDecimal.valueOf(100));
        return charge.compareTo(floor) >= 0 && charge.compareTo(share) >= 0;
    }
}
