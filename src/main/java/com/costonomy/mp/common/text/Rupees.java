package com.costonomy.mp.common.text;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount as a person reads it: two decimals. Money is stored at four
 * (`DECIMAL(19,4)`), and `toPlainString` on a stored figure put "₹50.0000" in a
 * dispute thread and a push notification. Logs keep the stored figure.
 */
public final class Rupees {

    private Rupees() {
    }

    public static String of(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
