package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.procurement.service.OrderReleaseService;

/**
 * What happens to an order once its payment has taken a provider's word (D-109).
 *
 * <p>Three places learn a payment's state from the provider — the payer's confirm call,
 * the provider's webhook and the reconciliation sweep — and each has to do the same
 * thing about the order afterwards. They used to each carry their own copy, and the copies
 * drifted: the confirm call had no answer for money that reached a cancelled order, so a
 * draft it left open could later be released against money that was being sent back (F1).
 * One rule, in one place:
 *
 * <ul>
 *   <li>the money funds the order: release it to its supplier;</li>
 *   <li>the payment failed: end the order;</li>
 *   <li>the order was cancelled, and money is on its way back (or has been captured only to
 *       be returned): the order must not be funded. It is nearly always cancelled already;
 *       if it is still a draft, end it.</li>
 * </ul>
 *
 * <p>Ending or releasing an order that is not a draft does nothing, so this is safe to call
 * after every state change from any of them.
 */
public final class PaymentFollowUp {

    private PaymentFollowUp() {
    }

    public static void apply(OrderReleaseService orderRelease, Payment updated) {
        if (updated.fundsSecuredForOrder()) {
            // The order the customer paid for, finally released.
            orderRelease.releaseIfFunded(updated.getSupplierOrderId());
        } else if (updated.getStatus() == PaymentStatus.FAILED) {
            orderRelease.abandonUnfunded(updated.getSupplierOrderId(),
                    "Payment failed: " + String.valueOf(updated.getFailureCode()));
        } else if (updated.getCancelRequestedAt() != null) {
            // Money reached an order that must not be funded, or was captured only to be
            // returned (D-109).
            orderRelease.abandonUnfunded(updated.getSupplierOrderId(),
                    "Payment returned: the order was not funded");
        }
    }
}
