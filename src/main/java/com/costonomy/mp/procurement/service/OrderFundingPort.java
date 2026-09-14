package com.costonomy.mp.procurement.service;

import com.costonomy.mp.procurement.domain.SupplierOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * How an order gets funded before a supplier ever sees it.
 *
 * <p>Guardrail 16 and doc 01 §14: a payment failure means the supplier receives
 * nothing. Procurement defines this interface and the payment module implements
 * it, so the dependency points the right way — procurement knows an order must be
 * funded, not how money works.
 *
 * <p>The contract is deliberately narrow. Procurement asks for funding to be
 * arranged and is told whether the order may be released; it never sees a payment,
 * a provider, or an amount it did not compute itself.
 */
public interface OrderFundingPort {

    /**
     * Arrange funding for newly created orders.
     *
     * <p>Called inside the submission transaction, <b>before</b> the orders are
     * released to suppliers. Creates the payment intents; it does not wait for the
     * customer to pay.
     *
     * @return what the client needs to complete payment, one per order
     */
    List<FundingIntent> arrangeFunding(List<SupplierOrder> orders);

    /**
     * Whether this order's funding is secured well enough to show the supplier.
     *
     * <p>The predicate behind guardrail 16. An order whose payment has not been
     * authorised must stay in DRAFT, and — importantly — its acceptance deadline
     * must not start, or a supplier would be handed an already-expired order the
     * moment they could see it.
     */
    boolean isFundingSecured(Long supplierOrderId);

    /**
     * The supplier committed to {@code acceptedAmount}. Take that much.
     *
     * <p>Doc 01 §14: only the accepted commercial value is captured, and the
     * remainder of the authorisation is released.
     */
    void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount);

    /**
     * The supplier rejected, timed out, or the order was cancelled. Return everything.
     *
     * <p>Releases an authorisation, or refunds if money was already captured.
     */
    void onOrderUnfulfilled(Long supplierOrderId, String reason);

    /**
     * What the client needs to pay for one order.
     *
     * @param providerOrderId the intent to open a checkout against
     * @param publicKey       publishable key — never a secret (doc 09 §4)
     */
    record FundingIntent(
            Long supplierOrderId,
            Long paymentId,
            String provider,
            String providerOrderId,
            BigDecimal amount,
            String currency,
            String publicKey) {
    }
}
