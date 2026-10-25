package com.costonomy.mp.access.domain;

/**
 * Permission codes Marketplace checks: costonomy-common-api's {@code <module>.<action>} codes (D-75), which
 * {@link PermissionCodes} maps from the codes {@code V5__seed_roles_permissions.sql} and later migrations seeded.
 *
 * <p>Constants rather than an enum, because the database is the source of truth:
 * operations can add a role or re-grant a permission without a deploy, and an
 * enum would imply the code knows the full set. These exist so a controller
 * refers to {@code Permissions.ORDER_ACCEPT} rather than a string literal that a
 * typo turns into a permission nobody holds — which fails open in the worst way,
 * silently denying rather than silently allowing, but still wrongly.
 *
 * <p>{@code PermissionCatalogIT} asserts every constant here is one {@link PermissionCodes} maps a seeded
 * permission to, so a rename on one side cannot drift from the other.
 */
public final class Permissions {

    private Permissions() {
    }

    // Restaurant
    public static final String RESTAURANT_VIEW = "business.view";
    public static final String RESTAURANT_EDIT = "business.edit";
    public static final String OUTLET_VIEW = "outlets.view";
    public static final String OUTLET_EDIT = "outlets.edit";
    public static final String OUTLET_USER_MANAGE = "team.edit";
    public static final String REQUIREMENT_CREATE = "requirements.create";
    public static final String REQUIREMENT_EDIT = "requirements.edit";
    public static final String PROCUREMENT_CREATE = "procurement.create";
    public static final String PROCUREMENT_SUBMIT = "procurement.submit";
    public static final String PROCUREMENT_APPROVE = "procurement.approve";
    public static final String ORDER_CANCEL = "orders.cancel";
    public static final String ORDER_RECEIVE = "orders.receive";
    public static final String DISPUTE_CREATE = "disputes.create";
    public static final String RATING_CREATE = "ratings.create";
    public static final String CREDIT_REQUEST = "credit.request";
    public static final String PAYMENT_CREATE = "payments.create";
    /** Send wallet money back to the card it came from. D-104, V42. */
    public static final String WALLET_WITHDRAW = "wallet.withdraw";
    /** Pay a shop by QR from the wallet. D-106, V44. */
    public static final String QUICKSCAN_PAY = "wallet.pay";
    /** Repay a supplier's credit invoice from the restaurant side. D-151, V77. */
    public static final String CREDIT_REPAY = "credit.repay";

    // Supplier
    public static final String SUPPLIER_VIEW = "business.view";
    public static final String SUPPLIER_EDIT = "business.edit";
    public static final String SUPPLIER_USER_MANAGE = "team.edit";
    public static final String STORE_VIEW = "outlets.view";
    public static final String STORE_EDIT = "outlets.edit";
    public static final String CATALOG_VIEW = "catalog.view";
    public static final String CATALOG_EDIT = "catalog.edit";
    public static final String CATALOG_IMPORT = "catalog.import";
    public static final String ORDER_ACCEPT = "orders.accept";
    public static final String ORDER_PARTIAL_ACCEPT = "orders.partial_accept";
    public static final String ORDER_REJECT = "orders.reject";
    public static final String ORDER_PREPARE = "orders.prepare";
    public static final String ORDER_READY = "orders.ready";
    public static final String CREDIT_REQUEST_VIEW = "credit_requests.view";
    public static final String CREDIT_APPROVE = "credit.approve";
    public static final String CREDIT_REJECT = "credit.reject";
    public static final String CREDIT_MODIFY = "credit.edit";
    /** Record a payment, confirm or reject a claim. {@link #CREDIT_MODIFY} is accepted wherever this is. V80. */
    public static final String CREDIT_COLLECT = "credit.collect";
    /** Write off a debt. Owner and admin only. V80. */
    public static final String CREDIT_WRITE_OFF = "credit.write_off";
    /** Answer a dispute raised against this store's order. Doc 04 §16. */
    public static final String DISPUTE_RESPOND = "disputes.respond";
    /** Approve or decline a dispute refund; an approval comes out of the payout. D-104, V43. */
    public static final String DISPUTE_REFUND_DECIDE = "disputes.refund";
    public static final String SETTLEMENT_VIEW = "settlements.view";
    public static final String PERFORMANCE_VIEW = "performance.view";

    /**
     * Chat between an outlet and a store. D-095, seeded by {@code V33__chat.sql}.
     *
     * <p>Read and write are separate (D-046). The restaurant and supplier constants
     * share one code since D-75: a grant is per outlet or store, so the scope the
     * check names already says which side is asking.
     */
    public static final String CHAT_VIEW = "chat.view";
    public static final String CHAT_SEND = "chat.send";
    public static final String CHAT_VIEW_SUPPLIER = "chat.view";
    public static final String CHAT_SEND_SUPPLIER = "chat.send";

    // Shared between both worlds; the scope of the grant disambiguates.
    public static final String ORDER_VIEW = "orders.view";
    public static final String CREDIT_VIEW = "credit.view";
    public static final String NOTIFICATION_VIEW = "notifications.view";

    // Internal
    public static final String SUPPLIER_VERIFY = "suppliers.verify";
    public static final String SUPPLIER_SUSPEND = "suppliers.suspend";
    public static final String CATALOG_MODERATE = "catalog.moderate";
    public static final String ORDER_SUPPORT = "orders.support";
    public static final String DISPUTE_MODERATE = "disputes.moderate";
    /** Hide or restore a published rating. Doc 09 §9. */
    public static final String RATING_MODERATE = "ratings.moderate";
    public static final String DELIVERY_OPERATE = "delivery.operate";
    public static final String PAYMENT_RECONCILE = "payments.reconcile";
    public static final String CREDIT_AUDIT = "credit.audit";
    public static final String SETTLEMENT_OPERATE = "settlements.operate";
    public static final String AUDIT_VIEW = "mp_audit.view";
    public static final String CONFIG_MANAGE = "config.edit";

    // Internal, read-only. Doc 09 §13: "support users may inspect records without
    // receiving unrestricted mutation rights". Each is the read half of a
    // mutation permission above — without them, granting someone the ability to
    // look at a delivery also granted the ability to reassign it.
    public static final String SUPPLIER_INSPECT = "suppliers.inspect";
    public static final String ORDER_INSPECT = "orders.inspect";
    public static final String PAYMENT_INSPECT = "payments.inspect";
    public static final String DELIVERY_INSPECT = "delivery.inspect";
    public static final String DISPUTE_INSPECT = "disputes.inspect";
    /** Decide a dispute refund the supplier declined or did not answer. Moves money. D-104, V43. */
    public static final String REFUND_DECIDE = "refunds.decide";
    public static final String CONFIG_VIEW = "config.view";
    /**
     * Decide what happens to a refund the provider did not send: verify it, retry it, put a
     * withdrawal back in the wallet, send a cancellation refund to the wallet, mark it done, block
     * a payment as a refund source. Moves money. Its read side is PAYMENT_INSPECT. D-110, V48.
     */
    public static final String REFUND_OPERATE = "refunds.operate";
    /** See courier payables, courier payments, gateway refunds and their queues. Internal, read-only. D-204, V104. */
    public static final String DELIVERY_FINANCE_INSPECT = "delivery.finance_inspect";
    /** Record a courier payment, retry, void or adjust a payable, retry or resolve a gateway refund. Moves money. D-204. */
    public static final String DELIVERY_FINANCE_OPERATE = "delivery.finance_operate";
}
