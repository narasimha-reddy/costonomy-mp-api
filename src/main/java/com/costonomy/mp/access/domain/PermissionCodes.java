package com.costonomy.mp.access.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Marketplace's permission codes in costonomy-common-api's access model (D-75): {@code <module>.<action>}.
 *
 * <p>Marketplace's own {@code permission}/{@code role}/{@code role_permission} tables keep the codes its migrations
 * seeded (ORDER_ACCEPT…); they describe what exists and which built-in role held what, and are the source of the
 * catalog {@code MarketplaceCatalogPublisher} sends to common. This map turns each into the code common grants and
 * {@link Permissions} checks. Seeing and changing the business, its outlets or stores and its team are common's own
 * permissions (app COMMON), which Marketplace's access carries too; those rows map onto them and are not published.
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    /** The business, its outlets/stores and its team: common's permissions, not Marketplace's. */
    public static final Set<String> COMMON_MODULES = Set.of("business", "outlets", "team");

    /** Old Marketplace code → access-model code. */
    public static final Map<String, String> BY_OLD_CODE = build();

    /** Module names for common's role editor, in display order. */
    public static final Map<String, String> MODULES = modules();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();
        // The business, outlets and team (common's)
        m.put("RESTAURANT_VIEW", "business.view");
        m.put("RESTAURANT_EDIT", "business.edit");
        m.put("SUPPLIER_VIEW", "business.view");
        m.put("SUPPLIER_EDIT", "business.edit");
        m.put("OUTLET_VIEW", "outlets.view");
        m.put("OUTLET_EDIT", "outlets.edit");
        m.put("STORE_VIEW", "outlets.view");
        m.put("STORE_EDIT", "outlets.edit");
        m.put("OUTLET_USER_MANAGE", "team.edit");
        m.put("SUPPLIER_USER_MANAGE", "team.edit");
        // Restaurant side
        m.put("REQUIREMENT_CREATE", "requirements.create");
        m.put("REQUIREMENT_EDIT", "requirements.edit");
        m.put("PROCUREMENT_CREATE", "procurement.create");
        m.put("PROCUREMENT_SUBMIT", "procurement.submit");
        m.put("PROCUREMENT_APPROVE", "procurement.approve");
        m.put("ORDER_CANCEL", "orders.cancel");
        m.put("ORDER_RECEIVE", "orders.receive");
        m.put("DISPUTE_CREATE", "disputes.create");
        m.put("RATING_CREATE", "ratings.create");
        m.put("CREDIT_REQUEST", "credit.request");
        m.put("CREDIT_REPAY", "credit.repay");
        m.put("PAYMENT_CREATE", "payments.create");
        m.put("WALLET_WITHDRAW", "wallet.withdraw");
        m.put("QUICKSCAN_PAY", "wallet.pay");
        // Supplier side
        m.put("CATALOG_VIEW", "catalog.view");
        m.put("CATALOG_EDIT", "catalog.edit");
        m.put("CATALOG_IMPORT", "catalog.import");
        m.put("ORDER_ACCEPT", "orders.accept");
        m.put("ORDER_PARTIAL_ACCEPT", "orders.partial_accept");
        m.put("ORDER_REJECT", "orders.reject");
        m.put("ORDER_PREPARE", "orders.prepare");
        m.put("ORDER_READY", "orders.ready");
        m.put("CREDIT_REQUEST_VIEW", "credit_requests.view");
        m.put("CREDIT_APPROVE", "credit.approve");
        m.put("CREDIT_REJECT", "credit.reject");
        m.put("CREDIT_MODIFY", "credit.edit");
        m.put("CREDIT_COLLECT", "credit.collect");
        m.put("CREDIT_WRITE_OFF", "credit.write_off");
        m.put("DISPUTE_RESPOND", "disputes.respond");
        m.put("DISPUTE_REFUND_DECIDE", "disputes.refund");
        m.put("SETTLEMENT_VIEW", "settlements.view");
        m.put("PERFORMANCE_VIEW", "performance.view");
        // Both sides. A restaurant holds these at an outlet and a supplier at a store, so one code serves both.
        m.put("CHAT_VIEW", "chat.view");
        m.put("CHAT_SEND", "chat.send");
        m.put("CHAT_VIEW_SUPPLIER", "chat.view");
        m.put("CHAT_SEND_SUPPLIER", "chat.send");
        m.put("ORDER_VIEW", "orders.view");
        m.put("CREDIT_VIEW", "credit.view");
        m.put("NOTIFICATION_VIEW", "notifications.view");
        // Costonomy staff (platform)
        m.put("SUPPLIER_VERIFY", "suppliers.verify");
        m.put("SUPPLIER_SUSPEND", "suppliers.suspend");
        m.put("SUPPLIER_INSPECT", "suppliers.inspect");
        m.put("CATALOG_MODERATE", "catalog.moderate");
        m.put("ORDER_SUPPORT", "orders.support");
        m.put("ORDER_INSPECT", "orders.inspect");
        m.put("DISPUTE_MODERATE", "disputes.moderate");
        m.put("DISPUTE_INSPECT", "disputes.inspect");
        m.put("RATING_MODERATE", "ratings.moderate");
        m.put("DELIVERY_OPERATE", "delivery.operate");
        m.put("DELIVERY_INSPECT", "delivery.inspect");
        m.put("DELIVERY_FINANCE_INSPECT", "delivery.finance_inspect");
        m.put("DELIVERY_FINANCE_OPERATE", "delivery.finance_operate");
        m.put("PAYMENT_RECONCILE", "payments.reconcile");
        m.put("PAYMENT_INSPECT", "payments.inspect");
        m.put("REFUND_DECIDE", "refunds.decide");
        m.put("REFUND_OPERATE", "refunds.operate");
        m.put("CREDIT_AUDIT", "credit.audit");
        m.put("SETTLEMENT_OPERATE", "settlements.operate");
        m.put("AUDIT_VIEW", "mp_audit.view");
        m.put("CONFIG_VIEW", "config.view");
        m.put("CONFIG_MANAGE", "config.edit");
        return Map.copyOf(m);
    }

    private static Map<String, String> modules() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("requirements", "Requirements");
        m.put("procurement", "Procurement");
        m.put("orders", "Orders");
        m.put("disputes", "Disputes");
        m.put("ratings", "Ratings");
        m.put("credit", "Credit");
        m.put("credit_requests", "Credit requests");
        m.put("payments", "Payments");
        m.put("wallet", "Wallet");
        m.put("catalog", "Catalog");
        m.put("settlements", "Settlements");
        m.put("performance", "Performance");
        m.put("chat", "Chat");
        m.put("notifications", "Notifications");
        m.put("suppliers", "Platform: suppliers");
        m.put("delivery", "Platform: delivery");
        m.put("refunds", "Platform: refunds");
        m.put("mp_audit", "Platform: audit");
        m.put("config", "Platform: configuration");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The access-model code for an old Marketplace code, or null when it has none. */
    public static String of(String oldCode) {
        return BY_OLD_CODE.get(oldCode);
    }

    public static String module(String code) {
        return code.substring(0, code.indexOf('.'));
    }
}
