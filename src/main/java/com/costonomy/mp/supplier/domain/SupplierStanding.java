package com.costonomy.mp.supplier.domain;

import com.costonomy.security.client.CommonApiClient.AppOrg;
import com.costonomy.security.client.CommonApiClient.AppOutlet;

/**
 * A supplier's Marketplace lifecycle and a store's state, read from costonomy-common-api (D-208) instead of stored:
 *
 * <ul>
 *   <li>INVITED: the business is unclaimed (a buyer added it; nobody has signed up for it).</li>
 *   <li>SUSPENDED: the business, or its Marketplace enrollment, is suspended.</li>
 *   <li>ACTIVE: verified and activated: Marketplace staff set the business-wide enrollment live.</li>
 *   <li>VERIFIED: the GSTIN is verified (common's verification queue) but not yet activated. Verification
 *       never activates on its own (doc 03 §2).</li>
 *   <li>VERIFICATION_PENDING: submitted and waiting for review.</li>
 *   <li>REGISTERED: everything else.</li>
 * </ul>
 *
 * A store takes orders when it is ACTIVE in common, online by its Marketplace setting, and its supplier is ACTIVE.
 */
public final class SupplierStanding {

    /** Marketplace's own store settings in common (MARKETPLACE outlet settings, common D-85). */
    public static final String ONLINE = "online";
    public static final String RESPONSE_SLA_SECONDS = "responseSlaSeconds";
    public static final String PREPARATION_MINUTES = "preparationMinutes";
    public static final String DIRECT_ORDERS_ENABLED = "directOrdersEnabled";
    public static final String CHAT_ENABLED = "chatEnabled";
    public static final String DELIVERY_INSTRUCTIONS = "deliveryInstructions";

    public static final int DEFAULT_RESPONSE_SLA_SECONDS = 60;
    public static final int DEFAULT_PREPARATION_MINUTES = 60;

    private SupplierStanding() {
    }

    public static SupplierLifecycleStatus lifecycle(String claimStatus, String orgStatus, String enrollmentStatus,
                                                    boolean live, String verificationStatus) {
        if ("UNCLAIMED".equals(claimStatus)) {
            return SupplierLifecycleStatus.INVITED;
        }
        if ("SUSPENDED".equals(orgStatus) || "CLOSED".equals(orgStatus) || "SUSPENDED".equals(enrollmentStatus)
                || "CANCELLED".equals(enrollmentStatus)) {
            return SupplierLifecycleStatus.SUSPENDED;
        }
        boolean verified = "VERIFIED".equals(verificationStatus);
        if (verified && live) {
            return SupplierLifecycleStatus.ACTIVE;
        }
        if (verified) {
            return SupplierLifecycleStatus.VERIFIED;
        }
        if ("PENDING".equals(verificationStatus) || "INFO_REQUESTED".equals(verificationStatus)) {
            return SupplierLifecycleStatus.VERIFICATION_PENDING;
        }
        return SupplierLifecycleStatus.REGISTERED;
    }

    public static SupplierLifecycleStatus of(AppOrg org) {
        return lifecycle(org.claimStatus(), org.status(), org.enrollmentStatus(), org.live(), org.verificationStatus());
    }

    /** The lifecycle of the store's supplier, from what the store row carries about its business. */
    public static SupplierLifecycleStatus of(AppOutlet store) {
        return lifecycle(store.orgClaimStatus(), store.orgStatus(), store.enrollmentStatus(), store.live(),
                store.orgVerificationStatus());
    }

    public static VerificationStatus verification(String commonStatus) {
        if (commonStatus == null) {
            return VerificationStatus.NOT_SUBMITTED;
        }
        return switch (commonStatus) {
            case "VERIFIED" -> VerificationStatus.VERIFIED;
            case "PENDING" -> VerificationStatus.PENDING;
            case "REJECTED" -> VerificationStatus.REJECTED;
            case "INFO_REQUESTED" -> VerificationStatus.INFORMATION_REQUESTED;
            default -> VerificationStatus.NOT_SUBMITTED;
        };
    }

    /** The supplier may trade. */
    public static boolean tradeable(AppOutlet store) {
        return of(store) == SupplierLifecycleStatus.ACTIVE;
    }

    /** The store itself is open for business: ACTIVE in common and online (the supplier did not take it offline). */
    public static boolean storeOperational(AppOutlet store) {
        return "ACTIVE".equals(store.status()) && store.flag(ONLINE, true);
    }

    /** The store takes orders: operational, and its supplier may trade. */
    public static boolean takesOrders(AppOutlet store) {
        return storeOperational(store) && tradeable(store);
    }

    /** ACTIVE, OFFLINE (the supplier's switch) or SUSPENDED / INACTIVE / CLOSED (common's status), as the app shows it. */
    public static String storeStatus(AppOutlet store) {
        if (!"ACTIVE".equals(store.status())) {
            return store.status();
        }
        return store.flag(ONLINE, true) ? "ACTIVE" : "OFFLINE";
    }

    public static int responseSlaSeconds(AppOutlet store) {
        return store.number(RESPONSE_SLA_SECONDS, DEFAULT_RESPONSE_SLA_SECONDS);
    }

    public static int preparationMinutes(AppOutlet store) {
        return store.number(PREPARATION_MINUTES, DEFAULT_PREPARATION_MINUTES);
    }

    public static boolean directOrdersEnabled(AppOutlet store) {
        return store.flag(DIRECT_ORDERS_ENABLED, false);
    }

    public static boolean chatEnabled(AppOutlet outletOrStore) {
        return outletOrStore.flag(CHAT_ENABLED, true);
    }

    public static OperatingHours hours(AppOutlet store) {
        return OperatingHoursCodec.fromCommon(store.operatingHours());
    }
}
