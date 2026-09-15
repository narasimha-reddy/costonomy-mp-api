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
    /** Answer a dispute raised against this store's order. Doc 04 §16. */
    public static final String DISPUTE_RESPOND = "DISPUTE_RESPOND";
    public static final String SETTLEMENT_VIEW = "SETTLEMENT_VIEW";
    public static final String PERFORMANCE_VIEW = "PERFORMANCE_VIEW";

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
}
