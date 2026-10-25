package com.costonomy.mp.delivery.service;

/**
 * Ends the order a delivery belongs to, on operations' decision (D-204). A port, like the funding port: delivery decides
 * that a delivery cannot be completed, procurement owns what that means for the order and the money.
 */
public interface OrderCancellationPort {

    /**
     * Cancel the order and send its money back. Idempotent. Joins the caller's transaction, so the delivery and the order
     * end together.
     */
    void cancelForOperations(Long actorId, Long supplierOrderId, String reason);
}
