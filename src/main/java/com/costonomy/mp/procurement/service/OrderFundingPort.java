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
 *
 * <p>There is one implementation per payment method — prepaid money in the payment
 * module, reserved credit in the credit module — and {@link OrderFunding} picks
 * between them. Procurement depends on that router, never on an implementation:
 * the two ways of funding an order differ in almost everything except the promise
 * this interface makes, which is the only part procurement cares about.
 */
public interface OrderFundingPort {

    /**
     * The payment method this funds — {@code PREPAID} or {@code CREDIT}.
     *
     * <p>Declared by the implementation rather than configured, so adding a funding
     * method is adding a class and nothing else.
     */
    String paymentMethod();

    /**
     * Arrange funding for newly created orders.
     *
     * <p>Called inside the submission transaction, <b>before</b> the orders are
     * released to suppliers.
     *
     * <p>Whether funding is secured by the time this returns depends on the method.
     * Prepaid creates intents the customer must still complete; credit is reserved
     * here and now, so the orders are ready to release immediately. Callers must
     * not assume either — they ask {@link #isFundingSecured} afterwards.
     *
     * @return what the client needs to complete payment, one per order; empty when
     *         there is nothing for the client to do
     */
    List<FundingIntent> arrangeFunding(List<SupplierOrder> orders);

    /**
     * Whatever the client still has to complete for an order whose funding was arranged, made ready now, after the
     * order has committed (D-136). For a card payment that opens the provider's checkout, a network call, which is why
     * it is separate from {@link #arrangeFunding} (it runs inside the order's transaction and must not make one).
     * Must not be called from inside a transaction. The default is the open intent as it stands: wallet and credit have
     * nothing to open.
     *
     * @throws com.costonomy.mp.common.error.BusinessException {@code PAYMENT_FAILED} when the provider could not be
     *         reached: nothing was charged, and calling again retries
     */
    default java.util.Optional<FundingIntent> prepareCheckout(Long supplierOrderId) {
        return openIntent(supplierOrderId);
    }

    /**
     * Whether this method could fund an order between an outlet and a supplier store at all, before any
     * order exists (a standing arrangement such as a subscription is refused up front rather than
     * failing every morning). Wallet and card have nothing to check ahead of an amount; credit needs an
     * active agreement.
     */
    default boolean canFund(Long outletId, Long supplierStoreId) {
        return true;
    }

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
     * The goods are about to leave: settle the order's money to what it finally comes to (D-103, D-128).
     *
     * <p>{@code finalPayable} is the accepted amount less any catch-weight shortfall, and {@code reductionAmount} is that shortfall (zero when the order weighed in full). Called exactly once in
     * effect, with the order locked, and idempotent: a repeat finds the money already settled.
     * <ul>
     *   <li><b>Card:</b> prepaid money is held from confirmation and taken here, at most what was
     *       authorised, so the shortfall is released and never refunded.</li>
     *   <li><b>Wallet:</b> the wallet paid the accepted total up front; the difference comes back as one
     *       credit.</li>
     *   <li><b>Credit:</b> the drawn amount and the invoice come down to {@code finalPayable}.</li>
     * </ul>
     * From here the money only moves down ({@link #reduceAfterDispatch}); weighing never touches it.
     */
    default Reduction onOrderDispatched(Long supplierOrderId, BigDecimal finalPayable, BigDecimal reductionAmount) {
        return Reduction.applied(null);
    }

    /**
     * Reduce what the restaurant pays after the goods left, once per key (D-128): a doorstep rejection. The
     * supplier bears it, through the order's final payable, and Costonomy never funds it.
     *
     * <p>Down only. Card: a refund of the captured payment to the wallet, withdrawable (D-104). Wallet: a
     * credit back. Credit: the invoice and the drawn amount come down.
     *
     * @param amount          how much less the restaurant pays
     * @param newFinalPayable what the order now comes to in total, for a method that tracks a figure
     * @param key             makes a repeat a no-op
     */
    default Reduction reduceAfterDispatch(Long supplierOrderId, BigDecimal amount, BigDecimal newFinalPayable,
                                          String key, Long actorId, String reason) {
        throw new com.costonomy.mp.common.error.BusinessException(
                com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                "This order's payment can't be adjusted after dispatch.");
    }

    /**
     * Whether the money behind this order can still be taken (D-103). False once
     * a held payment is too close to its provider's hold expiry: an order must
     * not be handed over against money that will lapse back to the customer.
     */
    default boolean canTakeFunds(Long supplierOrderId) {
        return true;
    }

    /**
     * The intent a client can still pay this order against, if any (D-102).
     *
     * <p>A read: it never creates a provider order. Empty for a funding method with
     * no client step (credit, wallet), or once the payment is funded or ended.
     */
    default java.util.Optional<FundingIntent> openIntent(Long supplierOrderId) {
        return java.util.Optional.empty();
    }

    /**
     * How much of this order's money can still be given back to the restaurant's
     * wallet (D-104). Zero by default: a credit order's money never passed
     * through Mandi, and a refund on it is a credit note between the two parties.
     */
    default BigDecimal refundableToWallet(Long supplierOrderId) {
        return BigDecimal.ZERO;
    }

    /**
     * Give part of this order's money back to the outlet's wallet, once per key
     * (D-104). The caller has already charged it to the supplier.
     *
     * @return the refund behind the credit, where the funding method has one
     */
    default Long refundToWallet(Long supplierOrderId, BigDecimal amount, String key,
                                Long actorId, String note) {
        throw new com.costonomy.mp.common.error.BusinessException(
                com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                "Refunds on this order are settled with the supplier directly.");
    }

    /**
     * Where this order's money stands now, in the funding method's own words —
     * the value an order shows as its payment status.
     *
     * <p>Read live. {@code supplier_order.payment_status} is a copy written once,
     * at release, as "AUTHORIZED" whatever the method: a wallet order read
     * "Authorized" when its money was already paid, and a card order still said so
     * after the money was taken, refunded or released. Empty means no answer, and
     * the stored value stands.
     */
    default java.util.Optional<String> paymentState(Long supplierOrderId) {
        return java.util.Optional.empty();
    }

    /**
     * How the order was paid for, as the provider names it — {@code card},
     * {@code upi}, {@code netbanking}, {@code wallet}… — for wording only (D-109):
     * whether "you were not charged" is true depends on it, because a card is
     * only held and a UPI payment is debited. Empty when the method has no such
     * thing (credit, the wallet) or has not been read yet.
     */
    default java.util.Optional<String> paymentInstrument(Long supplierOrderId) {
        return java.util.Optional.empty();
    }

    /**
     * The refund that sends a cancelled order's debited money back to where it came
     * from (D-109), for the apps to say how much is coming and when it landed. Empty
     * for an order that was not cancelled, whose money was only a held card (nothing
     * to refund), or whose refund has not been raised yet.
     */
    default java.util.Optional<CancelRefund> cancelRefund(Long supplierOrderId) {
        return java.util.Optional.empty();
    }

    /**
     * @param amount      what is being refunded
     * @param completedAt when the refund completed, or null while it is still on its way
     */
    record CancelRefund(BigDecimal amount, java.time.Instant completedAt) {
    }

    /**
     * What a funding method did about a reduction (D-129).
     *
     * <p>{@code DEFERRED} is allowed only when the money exists but has not been taken yet: a card whose capture
     * is still pending cannot be refunded until it is captured, so the reduction waits for it instead of
     * failing the doorstep check-in.
     *
     * @param fundingReference where the money went, for the adjustment row: {@code refund:{id}},
     *                         {@code wallet:{reference}}, {@code credit_invoice:{id}} or {@code payment:{id}}
     * @param settledOutside   the part a credit invoice could not absorb because it was already repaid
     */
    record Reduction(Outcome outcome, String fundingReference, BigDecimal settledOutside) {

        public enum Outcome { APPLIED, DEFERRED }

        public static Reduction applied(String fundingReference) {
            return new Reduction(Outcome.APPLIED, fundingReference, BigDecimal.ZERO);
        }

        public static Reduction applied(String fundingReference, BigDecimal settledOutside) {
            return new Reduction(Outcome.APPLIED, fundingReference,
                    settledOutside == null ? BigDecimal.ZERO : settledOutside.max(BigDecimal.ZERO));
        }

        public static Reduction deferred() {
            return new Reduction(Outcome.DEFERRED, null, BigDecimal.ZERO);
        }

        public boolean isApplied() {
            return outcome == Outcome.APPLIED;
        }
    }

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
