package com.costonomy.mp.notification.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.costonomy.mp.notification.domain.NotificationCategory.*;
import static com.costonomy.mp.notification.domain.NotificationChannel.*;
import static com.costonomy.mp.notification.domain.NotificationRule.Audience.OUTLET;
import static com.costonomy.mp.notification.domain.NotificationRule.Audience.SUPPLIER_STORE;

/**
 * The catalogue. Doc 08 §1's event list, mapped to doc 08 §4's notifications.
 *
 * <p>Data rather than code, in one place, because the interesting question about
 * notifications is always "who gets told what" and that should be answerable by
 * reading a table rather than by searching five modules for {@code notify(}.
 *
 * <p><b>Not every domain event is a notification.</b> Doc 08 §1 lists forty events
 * for the outbox; this maps the ones a person needs to act on. A location update
 * arrives every few seconds and belongs on a map, not in an inbox — pushing it
 * would be the fastest way to get the app's notifications turned off entirely.
 *
 * <p><b>Critical is doc 08 §4's list, not a judgement call.</b> Supplier
 * acceptance, rejection and expiry; payment failure; delivery assignment, failure
 * and completion; credit approval, modification and overdue; approval requests;
 * dispute updates. Those ignore preferences (doc 08 §5). Everything else can be
 * muted.
 *
 * <p><b>SMS is rarer still.</b> Doc 08 §4 says "SMS for selected critical events",
 * and the selection here is: your order was rejected or expired, your payment
 * failed, your credit is overdue. Each is a case where someone must act today and
 * a push may never be seen. An SMS for every status change trains people to ignore
 * SMS.
 */
public final class NotificationRules {

    private NotificationRules() {
    }

    private static final Map<String, List<NotificationRule>> BY_EVENT = build();

    public static List<NotificationRule> forEvent(String eventType) {
        return BY_EVENT.getOrDefault(eventType, List.of());
    }

    /**
     * The rules for an event whose wording depends on how it happened (D-109): a
     * refund that reached the wallet reads differently from one going back to the
     * bank. The producer names the variant in the payload; an event with none, or
     * one no rule was written for, keeps the event's own rule, so a mistyped or new
     * variant can only ever refine wording, never lose a notification. A variant
     * registered with no rule at all ({@code silence}) is the one deliberate way to
     * send nothing.
     */
    public static List<NotificationRule> forEvent(String eventType, String variant) {
        if (variant != null && !variant.isBlank()) {
            var refined = BY_EVENT.get(eventType + "#" + variant);
            if (refined != null) {
                return refined;
            }
        }
        return forEvent(eventType);
    }

    /** Every rule, for tests that assert properties across the whole catalogue. */
    public static List<NotificationRule> all() {
        return BY_EVENT.values().stream().flatMap(List::stream).toList();
    }

    private static Map<String, List<NotificationRule>> build() {
        Map<String, List<NotificationRule>> rules = new LinkedHashMap<>();

        // ── Orders, restaurant side ──────────────────────────────────────
        add(rules, new NotificationRule("SupplierOrderAccepted", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Order accepted",
                "{supplierName} accepted order {orderNumber}.", "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("SupplierOrderPartiallyAccepted", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Order partly accepted",
                "{supplierName} can supply part of order {orderNumber}. "
                        + "The rest is still needed.", "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("SupplierOrderRejected", OUTLET, ORDERS, true,
                // Something must be re-sourced today. A push that arrives while the
                // phone is in a pocket is not enough.
                List.of(IN_APP, PUSH, SMS),
                "Order rejected",
                "{supplierName} can't supply order {orderNumber}. Find another supplier.",
                "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("SupplierOrderExpired", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH, SMS),
                "Order expired",
                "{supplierName} didn't respond to order {orderNumber} in time.",
                "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("SupplierOrderReady", OUTLET, ORDERS, false,
                List.of(IN_APP, PUSH),
                "Ready for pickup",
                "Order {orderNumber} is packed and waiting for collection.", "SUPPLIER_ORDER"));

        // ── Orders, supplier side ────────────────────────────────────────
        add(rules, new NotificationRule("SupplierOrderReleased", SUPPLIER_STORE, ORDERS, true,
                // The countdown starts now (doc 13). A supplier who misses this
                // loses the order to a timeout they never saw.
                List.of(IN_APP, PUSH),
                "New order",
                "Order {orderNumber} needs your answer.", "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("SupplierOrderExpired", SUPPLIER_STORE, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Order expired",
                "Order {orderNumber} expired without an answer.", "SUPPLIER_ORDER"));

        // Paid for, and already agreed to. Nothing to accept and no countdown --
        // the supplier committed to these quantities when they answered the
        // request, so the only thing left is to prepare it.
        add(rules, new NotificationRule("SupplierOrderConfirmed", SUPPLIER_STORE, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Order confirmed",
                "Order {orderNumber} is paid for and ready to prepare.", "SUPPLIER_ORDER"));

        // ── Chat ─────────────────────────────────────────────────────────
        //
        // <p>Two rules for one thing, because a message reaches whichever side
        // did not send it and a rule names one audience. D-095.
        //
        // <p><b>No preview in the body.</b> Doc 08 §8: a body renders from named
        // fields so that a template asking for an order number can only ever
        // contain an order number. A message preview is whatever somebody typed,
        // and this lands on a lock screen — so it says who, and opening it says
        // what.
        add(rules, new NotificationRule("ChatMessageToSupplier", SUPPLIER_STORE, ORDERS, false,
                List.of(IN_APP, PUSH),
                "New message",
                "{senderName} sent you a message.", "CHAT_THREAD"));

        add(rules, new NotificationRule("ChatMessageToRestaurant", OUTLET, ORDERS, false,
                List.of(IN_APP, PUSH),
                "New message",
                "{senderName} sent you a message.", "CHAT_THREAD"));

        // ── Requests ─────────────────────────────────────────────────────
        //
        // The one notification in this file that decides whether the feature
        // works at all. A request sits doing nothing until its supplier answers,
        // and a supplier who is not told has no reason to open the app — so an
        // unnotified request is a request that expires. Critical for that reason,
        // not because the money is large: there is no money yet.
        add(rules, new NotificationRule("IntentSent", SUPPLIER_STORE, ORDERS, true,
                List.of(IN_APP, PUSH),
                "New request",
                "A restaurant is asking what you can supply. Request {reference}.",
                "INTENT"));

        // The restaurant's window to order starts the moment this is sent, and
        // it is short. Missing it means the supplier held stock for nothing.
        add(rules, new NotificationRule("IntentAnswered", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Supplier replied",
                "{reference}: your supplier replied. Order within the window to confirm it.",
                "INTENT"));

        add(rules, new NotificationRule("IntentOrdered", SUPPLIER_STORE, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Order confirmed",
                "{reference} became order {orderNumber}. It is yours to prepare.",
                "SUPPLIER_ORDER", "supplierOrderId"));

        // The supplier said no to everything. Commercially this is the old
        // "order rejected", and it costs the kitchen the same day, so it carries
        // the same SMS.
        add(rules, new NotificationRule("IntentDeclined", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH, SMS),
                "Nothing available",
                "{reference}: your supplier can't supply any of it. Find another supplier.",
                "INTENT"));

        // Nobody answered. The kitchen still needs these goods today, which is
        // why this one is worth an SMS and the two below are not.
        add(rules, new NotificationRule("IntentExpired", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH, SMS),
                "No reply in time",
                "{reference}: your supplier didn't reply. Try another supplier.",
                "INTENT"));

        // Distinct from the above, and deliberately so: the supplier did their
        // part here. Telling them their reply "expired" would read as a
        // reprimand, so it says what it means — the stock is theirs again.
        add(rules, new NotificationRule("IntentOrderWindowExpired", SUPPLIER_STORE, ORDERS, false,
                List.of(IN_APP),
                "Request closed",
                "{reference} wasn't ordered in time. The stock you held is free again.",
                "INTENT"));

        add(rules, new NotificationRule("IntentOrderWindowExpired", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Reply expired",
                "{reference}: the time to order from that reply has passed. Ask again.",
                "INTENT"));

        add(rules, new NotificationRule("IntentCancelled", SUPPLIER_STORE, ORDERS, false,
                List.of(IN_APP),
                "Request withdrawn",
                "{reference} was withdrawn by the restaurant. No reply needed.",
                "INTENT"));

        // ── Approvals ────────────────────────────────────────────────────
        add(rules, new NotificationRule("ProcurementApprovalRequested", OUTLET, APPROVALS, true,
                List.of(IN_APP, PUSH),
                "Approval needed",
                "A procurement of {totalAmount} is waiting for your approval.", "PROCUREMENT"));

        add(rules, new NotificationRule("ProcurementRejected", OUTLET, APPROVALS, true,
                List.of(IN_APP, PUSH),
                "Procurement rejected",
                "Your procurement was rejected. {reason}", "PROCUREMENT"));

        // ── Payments ─────────────────────────────────────────────────────
        add(rules, new NotificationRule("PaymentFailed", OUTLET, PAYMENTS, true,
                // Nothing reaches a supplier until this is fixed (guardrail 16).
                List.of(IN_APP, PUSH, SMS),
                "Payment failed",
                "Payment for your order didn't go through. {reason}", "SUPPLIER_ORDER"));

        // A subscription's delivery could not be arranged. Critical: a missed morning delivery is the cost, and
        // the restaurant can still fix a funding failure the same evening (generation retries hourly until
        // 23:00 India time). Told once per date, by the producer (D-132).
        add(rules, new NotificationRule("SubscriptionFundingFailed", OUTLET, PAYMENTS, true,
                List.of(IN_APP, PUSH),
                "Subscription delivery not arranged",
                "Your subscription delivery for {scheduledDate} couldn't be paid for. {reason}",
                "SUBSCRIPTION"));

        add(rules, new NotificationRule("SubscriptionOrderSkipped", OUTLET, ORDERS, true,
                List.of(IN_APP, PUSH),
                "Subscription delivery skipped",
                "Your subscription delivery for {scheduledDate} was skipped. {reason}",
                "SUBSCRIPTION"));

        add(rules, new NotificationRule("RefundCompleted", OUTLET, PAYMENTS, false,
                List.of(IN_APP, PUSH),
                "Refund sent",
                "{amount} has been refunded.", "SUPPLIER_ORDER", "supplierOrderId"));

        // The same event, worded for where the money went (D-109). Selected by the
        // payload's notificationVariant.
        add(rules, new NotificationRule("RefundCompleted#WALLET", OUTLET, PAYMENTS, false,
                List.of(IN_APP, PUSH),
                "Added to your wallet",
                "{amount} has been added to your wallet.", "SUPPLIER_ORDER", "supplierOrderId"));

        add(rules, new NotificationRule("RefundCompleted#CANCELLATION_TO_SOURCE", OUTLET, PAYMENTS, false,
                List.of(IN_APP, PUSH),
                "Refund sent",
                "{amount} for order {orderNumber} has been refunded to the account you paid from.",
                "SUPPLIER_ORDER", "supplierOrderId"));

        // Refunds the restaurant is already told about, or that would only mislead (F5).
        // A variant with no rule sends nothing, unlike an unknown variant, which keeps the
        // event's own rule: naming one of these is a decision, made by the producer.
        //   DISPUTE    - an approved dispute refund; DisputeRefundApproved says it was added
        //                to the wallet, and a second push for the same event is noise.
        //   WITHDRAWAL - one part of a wallet withdrawal; each part would read "refunded"
        //                and open whichever old order the money was drawn from.
        silence(rules, "RefundCompleted#DISPUTE");
        silence(rules, "RefundCompleted#WITHDRAWAL");

        // A cancelled order whose money had already left the payer's account (D-109):
        // told when the refund starts, not only when it lands days later.
        add(rules, new NotificationRule("RefundRequested", OUTLET, PAYMENTS, false,
                List.of(IN_APP, PUSH),
                "Refund started",
                "Refund started: {amount} for order {orderNumber} is on its way back to the account "
                        + "you paid from (5–7 working days).", "SUPPLIER_ORDER", "supplierOrderId"));

        // With instant refund switched on (costonomy.mp.razorpay.cancel-refund-speed=optimum)
        // the days do not apply: it is sent instantly where the bank allows and falls back to
        // the normal speed where not, so no number of days is promised either way (F5).
        add(rules, new NotificationRule("RefundRequested#INSTANT", OUTLET, PAYMENTS, false,
                List.of(IN_APP, PUSH),
                "Refund started",
                "Refund started: {amount} for order {orderNumber} is on its way back to the account "
                        + "you paid from.", "SUPPLIER_ORDER", "supplierOrderId"));

        // ── Credit ───────────────────────────────────────────────────────
        add(rules, new NotificationRule("CreditRequested", SUPPLIER_STORE, CREDIT, true,
                List.of(IN_APP, PUSH),
                "Credit request",
                "A restaurant has asked you for {requestedLimit} of credit.",
                "CREDIT_AGREEMENT"));

        add(rules, new NotificationRule("CreditApproved", OUTLET, CREDIT, true,
                List.of(IN_APP, PUSH),
                "Credit approved",
                "You have {approvedLimit} of credit, payable in {creditPeriodDays} days.",
                "CREDIT_AGREEMENT"));

        add(rules, new NotificationRule("CreditModified", OUTLET, CREDIT, true,
                // Terms changed under a restaurant's feet is exactly the thing they
                // must not discover at a checkout.
                List.of(IN_APP, PUSH),
                "Credit terms changed",
                "Your credit limit is now {approvedLimit}. {reason}", "CREDIT_AGREEMENT"));

        add(rules, new NotificationRule("CreditSuspended", OUTLET, CREDIT, true,
                List.of(IN_APP, PUSH),
                "Credit suspended",
                "Credit with this supplier is suspended. {reason}", "CREDIT_AGREEMENT"));

        add(rules, new NotificationRule("CreditOverdue", OUTLET, CREDIT, true,
                List.of(IN_APP, PUSH, SMS),
                "Payment overdue",
                "{outstanding} was due on {dueDate}.", "CREDIT_INVOICE"));

        // ── Delivery ─────────────────────────────────────────────────────
        add(rules, new NotificationRule("DriverAssigned", OUTLET, DELIVERY, true,
                List.of(IN_APP, PUSH),
                "Driver on the way",
                "A driver is collecting your order.", "DELIVERY", "supplierOrderId"));

        add(rules, new NotificationRule("DeliveryDelivered", OUTLET, DELIVERY, true,
                List.of(IN_APP, PUSH),
                "Delivered",
                "Your order has arrived. Check it in when you're ready.", "DELIVERY", "supplierOrderId"));

        add(rules, new NotificationRule("DeliveryProviderUnavailable", OUTLET, DELIVERY, true,
                List.of(IN_APP, PUSH),
                "Delivery problem",
                "We couldn't find a delivery partner for your order. {description}", "DELIVERY", "supplierOrderId"));

        add(rules, new NotificationRule("DeliveryReassigned", OUTLET, DELIVERY, true,
                List.of(IN_APP, PUSH),
                "New driver",
                "Your delivery partner is being reassigned.", "DELIVERY", "supplierOrderId"));

        // ── Trust ────────────────────────────────────────────────────────
        add(rules, new NotificationRule("DisputeCreated", SUPPLIER_STORE, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Dispute raised",
                "A restaurant raised a {category} dispute on order {disputeNumber}.", "DISPUTE", "supplierOrderId"));

        add(rules, new NotificationRule("DisputeResponded", OUTLET, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Dispute answered",
                "Your supplier responded to dispute {disputeNumber}.", "DISPUTE", "supplierOrderId"));

        add(rules, new NotificationRule("DisputeResolved", SUPPLIER_STORE, MARKETPLACE, false,
                List.of(IN_APP),
                "Dispute resolved",
                "Dispute {disputeNumber} was closed.", "DISPUTE", "supplierOrderId"));

        // D-104. Critical: the request has a 48-hour clock, and the answers are money.
        add(rules, new NotificationRule("DisputeRefundRequested", SUPPLIER_STORE, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Refund requested",
                "A restaurant asked for a refund of ₹{amount} on dispute {disputeNumber}. "
                        + "Please answer within 48 hours.", "DISPUTE", "supplierOrderId"));

        add(rules, new NotificationRule("DisputeRefundApproved", OUTLET, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Refund approved",
                "₹{amount} from dispute {disputeNumber} has been added to your wallet.",
                "DISPUTE", "supplierOrderId"));

        // The supplier too: it comes out of their payout, whoever approved it.
        add(rules, new NotificationRule("DisputeRefundApproved", SUPPLIER_STORE, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Refund approved",
                "A refund of ₹{amount} on dispute {disputeNumber} was approved. It will be "
                        + "taken from your payout for the order.", "DISPUTE", "supplierOrderId"));

        add(rules, new NotificationRule("DisputeRefundDeclined", OUTLET, MARKETPLACE, true,
                List.of(IN_APP, PUSH),
                "Refund declined",
                "Your refund on dispute {disputeNumber} was declined.", "DISPUTE", "supplierOrderId"));

        add(rules, new NotificationRule("ReceivingCompleted", SUPPLIER_STORE, ORDERS, false,
                List.of(IN_APP),
                "Order received",
                "Order {orderNumber} was checked in.", "SUPPLIER_ORDER"));

        add(rules, new NotificationRule("RatingSubmitted", SUPPLIER_STORE, MARKETPLACE, false,
                List.of(IN_APP),
                "New rating",
                "A restaurant rated order {supplierOrderId} {overall} out of 5.",
                "SUPPLIER_ORDER"));

        return rules;
    }

    /** Registers a variant that deliberately sends nothing; see {@link #forEvent(String, String)}. */
    private static void silence(Map<String, List<NotificationRule>> rules, String variantKey) {
        rules.put(variantKey, List.of());
    }

    private static void add(Map<String, List<NotificationRule>> rules, NotificationRule rule) {
        rules.computeIfAbsent(rule.eventType(), key -> new java.util.ArrayList<>()).add(rule);
    }
}
