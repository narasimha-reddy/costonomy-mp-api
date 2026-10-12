package com.costonomy.mp.identity.common;

import java.math.BigDecimal;

/**
 * The writes Marketplace makes in costonomy-common-api on the caller's behalf: businesses, their outlets and stores,
 * and team members. Common owns these records; Marketplace keeps copies under the same ids ({@link CommonSync}).
 *
 * <p>Callers check Marketplace's own permission first. Common then applies its own rules (owner and admin roles,
 * enrollment requirements) and its refusal is passed back as a validation error.
 */
public interface CommonDirectory {

    /** A restaurant ({@code RESTAURANT}) or supplier ({@code SUPPLIER}) business. */
    record Business(String orgType, String displayName, String legalName, String gstin, String contactName,
                    String contactPhone, String contactEmail) {
    }

    /** A restaurant outlet or a supplier store. {@code state} may be a name ("Telangana") or a code ("IN-TG"). */
    record Place(String name, String addressLine1, String addressLine2, String landmark, String city, String state,
                 String pincode, BigDecimal latitude, BigDecimal longitude, String googlePlaceId,
                 String formattedAddress, String contactName, String contactPhone) {
    }

    /** Who was invited. {@code userId} is null until the invitee has an account (they join at their first sign-in). */
    record Invited(Long userId, String phone, String name, String status) {
    }

    /** Creates the business, enrolled in MARKETPLACE; the caller becomes its owner. Returns the org id. */
    long createOrg(long actorId, Business business);

    /** Adds an outlet (restaurant) or store (supplier) to the business. Returns the outlet id. */
    long createOutlet(long actorId, long orgId, String orgType, Place place);

    /**
     * Gives {@code phone} the Marketplace role {@code roleCode} at the business ({@code outletId} null) or at one
     * outlet or store.
     */
    Invited invite(long actorId, long orgId, String phone, String country, String name, String roleCode, Long outletId);
}
