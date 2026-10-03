package com.costonomy.mp.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How fast the refund of a cancelled, already-debited order is sent (D-109):
 * {@code normal}, Razorpay's default and free (banks take 5 to 7 working days), or
 * {@code optimum}, which sends an instant refund where Razorpay can and falls back to
 * normal where it cannot, at a per-refund fee Costonomy pays. A withdrawal is always
 * normal.
 *
 * <p>One place read by both what sends the refund and what tells the restaurant about it,
 * so the message never promises "5–7 working days" for a refund sent instantly (F5).
 */
@Component
public class CancelRefundSpeed {

    /** Mutable only so a test can switch it; production reads the setting once. */
    private volatile String speed;

    public CancelRefundSpeed(
            @Value("${costonomy.mp.razorpay.cancel-refund-speed:normal}") String speed) {
        if (!"normal".equals(speed) && !"optimum".equals(speed)) {
            throw new IllegalStateException(
                    "costonomy.mp.razorpay.cancel-refund-speed must be normal or optimum, not " + speed);
        }
        this.speed = speed;
    }

    /** {@code normal} or {@code optimum}: what a cancellation's refund asks Razorpay for. */
    public String speed() {
        return speed;
    }

    /** Whether cancellation refunds ask for an instant refund. */
    public boolean instant() {
        return "optimum".equals(speed);
    }
}
