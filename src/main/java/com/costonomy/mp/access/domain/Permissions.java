package com.costonomy.mp.access.domain;

/**
 * Permission codes, mirroring {@code V5__seed_roles_permissions.sql}.
 *
 * <p>Constants rather than an enum, because the database is the source of truth:
 * operations can add a role or re-grant a permission without a deploy, and an
 * enum would imply the code knows the full set. These exist so a controller
 * refers to {@code Permissions.ORDER_ACCEPT} rather than a string literal that a
 * typo turns into a permission nobody holds — which fails open in the worst way,
 * silently denying rather than silently allowing, but still wrongly.
 *
 * <p>{@code AccessControlIT} asserts every constant here exists in the database,
 * so a rename on one side cannot drift from the other.
 */
public final class Permissions {

    private Permissions() {
    }

    // Restaurant
    public static final String RESTAURANT_VIEW = "RESTAURANT_VIEW";
    public static final String RESTAURANT_EDIT = "RESTAURANT_EDIT";
    public static final String OUTLET_VIEW = "OUTLET_VIEW";
    public static final String OUTLET_EDIT = "OUTLET_EDIT";
    public static final String OUTLET_USER_MANAGE = "OUTLET_USER_MANAGE";
    public static final String REQUIREMENT_CREATE = "REQUIREMENT_CREATE";
    public static final String REQUIREMENT_EDIT = "REQUIREMENT_EDIT";
    public static final String PROCUREMENT_CREATE = "PROCUREMENT_CREATE";
    public static final String PROCUREMENT_SUBMIT = "PROCUREMENT_SUBMIT";
    public static final String PROCUREMENT_APPROVE = "PROCUREMENT_APPROVE";
    public static final String ORDER_CANCEL = "ORDER_CANCEL";
    public static final String ORDER_RECEIVE = "ORDER_RECEIVE";
    public static final String DISPUTE_CREATE = "DISPUTE_CREATE";
    public static final String RATING_CREATE = "RATING_CREATE";
    public static final String CREDIT_REQUEST = "CREDIT_REQUEST";
    public static final String PAYMENT_CREATE = "PAYMENT_CREATE";
    /** Send wallet money back to the card it came from. D-104, V42. */
    public static final String WALLET_WITHDRAW = "WALLET_WITHDRAW";
    /** Pay a shop by QR from the wallet. D-106, V44. */
    public static final String QUICKSCAN_PAY = "QUICKSCAN_PAY";
    /** Repay a supplier's credit invoice from the restaurant side. D-151, V77. */
    public static final String CREDIT_REPAY = "CREDIT_REPAY";

    // Supplier
    public static final String SUPPLIER_VIEW = "SUPPLIER_VIEW";
    public static final String SUPPLIER_EDIT = "SUPPLIER_EDIT";
    public static final String SUPPLIER_USER_MANAGE = "SUPPLIER_USER_MANAGE";
    public static final String STORE_VIEW = "STORE_VIEW";
    public static final String STORE_EDIT = "STORE_EDIT";
    public static final String CATALOG_VIEW = "CATALOG_VIEW";
    public static final String CATALOG_EDIT = "CATALOG_EDIT";
    public static final String CATALOG_IMPORT = "CATALOG_IMPORT";
    public static final String ORDER_ACCEPT = "ORDER_ACCEPT";
    public static final String ORDER_PARTIAL_ACCEPT = "ORDER_PARTIAL_ACCEPT";
    public static final String ORDER_REJECT = "ORDER_REJECT";
    public static final String ORDER_PREPARE = "ORDER_PREPARE";
    public static final String ORDER_READY = "ORDER_READY";
    public static final String CREDIT_REQUEST_VIEW = "CREDIT_REQUEST_VIEW";
    public static final String CREDIT_APPROVE = "CREDIT_APPROVE";
    public static final String CREDIT_REJECT = "CREDIT_REJECT";
    public static final String CREDIT_MODIFY = "CREDIT_MODIFY";
    /** Record a payment, confirm or reject a claim. {@link #CREDIT_MODIFY} is accepted wherever this is. V80. */
    public static final String CREDIT_COLLECT = "CREDIT_COLLECT";
    /** Write off a debt. Owner and admin only. V80. */
    public static final String CREDIT_WRITE_OFF = "CREDIT_WRITE_OFF";
    /** Answer a dispute raised against this store's order. Doc 04 §16. */
    public static final String DISPUTE_RESPOND = "DISPUTE_RESPOND";
    /** Approve or decline a dispute refund; an approval comes out of the payout. D-104, V43. */
    public static final String DISPUTE_REFUND_DECIDE = "DISPUTE_REFUND_DECIDE";
    public static final String SETTLEMENT_VIEW = "SETTLEMENT_VIEW";
    public static final String PERFORMANCE_VIEW = "PERFORMANCE_VIEW";

    /**
     * Chat between an outlet and a store. D-095, seeded by {@code V33__chat.sql}.
     *
     * <p>Read and write are separate on both sides (D-046), and the two sides are
     * separate codes rather than one: a permission carries a scope, a restaurant's
     * is held on an outlet and a supplier's on a store, and one code held at both
     * would make "can this person write here" a question with two answers.
     */
    public static final String CHAT_VIEW = "CHAT_VIEW";
    public static final String CHAT_SEND = "CHAT_SEND";
    public static final String CHAT_VIEW_SUPPLIER = "CHAT_VIEW_SUPPLIER";
    public static final String CHAT_SEND_SUPPLIER = "CHAT_SEND_SUPPLIER";

    // Shared between both worlds; the scope of the grant disambiguates.
    public static final String ORDER_VIEW = "ORDER_VIEW";
    public static final String CREDIT_VIEW = "CREDIT_VIEW";
    public static final String NOTIFICATION_VIEW = "NOTIFICATION_VIEW";

    // Internal
    public static final String SUPPLIER_VERIFY = "SUPPLIER_VERIFY";
    public static final String SUPPLIER_SUSPEND = "SUPPLIER_SUSPEND";
    public static final String CATALOG_MODERATE = "CATALOG_MODERATE";
    public static final String ORDER_SUPPORT = "ORDER_SUPPORT";
    public static final String DISPUTE_MODERATE = "DISPUTE_MODERATE";
    /** Hide or restore a published rating. Doc 09 §9. */
    public static final String RATING_MODERATE = "RATING_MODERATE";
    public static final String DELIVERY_OPERATE = "DELIVERY_OPERATE";
    public static final String PAYMENT_RECONCILE = "PAYMENT_RECONCILE";
    public static final String CREDIT_AUDIT = "CREDIT_AUDIT";
    public static final String SETTLEMENT_OPERATE = "SETTLEMENT_OPERATE";
    public static final String AUDIT_VIEW = "AUDIT_VIEW";
    public static final String CONFIG_MANAGE = "CONFIG_MANAGE";

    // Internal, read-only. Doc 09 §13: "support users may inspect records without
    // receiving unrestricted mutation rights". Each is the read half of a
    // mutation permission above — without them, granting someone the ability to
    // look at a delivery also granted the ability to reassign it.
    public static final String SUPPLIER_INSPECT = "SUPPLIER_INSPECT";
    public static final String ORDER_INSPECT = "ORDER_INSPECT";
    public static final String PAYMENT_INSPECT = "PAYMENT_INSPECT";
    public static final String DELIVERY_INSPECT = "DELIVERY_INSPECT";
    public static final String DISPUTE_INSPECT = "DISPUTE_INSPECT";
    /** Decide a dispute refund the supplier declined or did not answer. Moves money. D-104, V43. */
    public static final String REFUND_DECIDE = "REFUND_DECIDE";
    public static final String CONFIG_VIEW = "CONFIG_VIEW";
    /**
     * Decide what happens to a refund the provider did not send: verify it, retry it, put a
     * withdrawal back in the wallet, send a cancellation refund to the wallet, mark it done, block
     * a payment as a refund source. Moves money. Its read side is PAYMENT_INSPECT. D-110, V48.
     */
    public static final String REFUND_OPERATE = "REFUND_OPERATE";
}
