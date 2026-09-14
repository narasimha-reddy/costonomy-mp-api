package com.costonomy.mp.access.domain;

/** Role codes, mirroring {@code V5__seed_roles_permissions.sql}. */
public final class Roles {

    private Roles() {
    }

    public static final String REST_OWNER = "REST_OWNER";
    public static final String REST_ADMIN = "REST_ADMIN";
    public static final String REST_PURCHASE_MANAGER = "REST_PURCHASE_MANAGER";
    public static final String REST_STORE_MANAGER = "REST_STORE_MANAGER";
    public static final String REST_PROCUREMENT_STAFF = "REST_PROCUREMENT_STAFF";
    public static final String REST_RECEIVING_STAFF = "REST_RECEIVING_STAFF";
    public static final String REST_FINANCE_STAFF = "REST_FINANCE_STAFF";

    public static final String SUP_OWNER = "SUP_OWNER";
    public static final String SUP_ADMIN = "SUP_ADMIN";
    public static final String SUP_STORE_MANAGER = "SUP_STORE_MANAGER";
    public static final String SUP_SALESPERSON = "SUP_SALESPERSON";
    public static final String SUP_OPERATIONS_STAFF = "SUP_OPERATIONS_STAFF";
    public static final String SUP_FINANCE_STAFF = "SUP_FINANCE_STAFF";

    public static final String OPS_MARKETPLACE = "OPS_MARKETPLACE";
    public static final String OPS_SUPPLIER_VERIFY = "OPS_SUPPLIER_VERIFY";
    public static final String OPS_SUPPORT = "OPS_SUPPORT";
    public static final String OPS_FINANCE = "OPS_FINANCE";
    public static final String OPS_MODERATION = "OPS_MODERATION";
    public static final String OPS_ADMIN = "OPS_ADMIN";
    public static final String OPS_DELIVERY = "OPS_DELIVERY";
}
