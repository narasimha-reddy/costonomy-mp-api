package com.costonomy.mp.supplier.web.dto;

import com.costonomy.mp.supplier.domain.SupplierLifecycleStatus;
import com.costonomy.mp.supplier.domain.VerificationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SupplierDtos {

    /**
     * What counts as a contact number. D-097.
     *
     * <p>Deliberately loose: digits, spaces, dashes and brackets, optionally
     * led by a country code, seven to fifteen digits. This is a number an
     * operator dials, not an identity — the strict E.164 rule belongs on the
     * login phone, and applying it here would reject a landline with an STD
     * code, which is exactly what a warehouse counter has.
     */
    static final String CONTACT_PHONE = "^[+]?[0-9 ()\\-]{7,20}$";

    private SupplierDtos() {
    }

    public record CreateSupplierRequest(
            @NotBlank(message = "Enter the legal name") @Size(max = 250) String legalName,
            @NotBlank(message = "Enter the display name") @Size(max = 200) String displayName,
            @Size(max = 32) String gstin,
            /**
             * Who to ring about the business, and on what number. D-097.
             *
             * <p>Required at registration because the first store copies both,
             * and because a supplier nobody can reach is one an operator cannot
             * call when an order goes wrong.
             */
            @NotBlank(message = "Enter a contact name") @Size(max = 150) String contactName,
            @NotBlank(message = "Enter a contact number")
            @Pattern(regexp = CONTACT_PHONE, message = "That doesn't look like a phone number")
            @Size(max = 32) String contactPhone,
            @Size(max = 255) String contactEmail,
            @Valid CreateStoreRequest firstStore) {
    }

    public record UpdateSupplierRequest(
            @Size(max = 250) String legalName,
            @Size(max = 200) String displayName,
            @Size(max = 32) String gstin,
            @Size(max = 150) String contactName,
            @Pattern(regexp = CONTACT_PHONE, message = "That doesn't look like a phone number")
            @Size(max = 32) String contactPhone,
            @Size(max = 255) String contactEmail) {
    }

    public record SupplierResponse(
            Long id,
            String legalName,
            String displayName,
            String gstin,
            /**
             * Who to ring about the business. D-097.
             *
             * <p>Returned because the settings screen has to show what is
             * already saved: without them it opened with empty fields over a
             * stored contact, and saving from there wrote the blanks back.
             */
            String contactName,
            String contactPhone,
            String contactEmail,
            SupplierLifecycleStatus lifecycleStatus,
            VerificationStatus verificationStatus,
            /** Whether this supplier can currently receive orders. */
            boolean canTrade,
            List<StoreResponse> stores) {
    }

    /**
     * When a store trades. Day names and {@code HH:mm}, the store's local time.
     *
     * <p>Absent means the defaults — every day, 10:00 to 21:00 — never "closed".
     * A store that has never opened this screen must still be findable.
     */
    public record OperatingHoursPayload(
            List<String> days,
            String opensAt,
            String closesAt) {
    }

    public record CreateStoreRequest(
            @NotBlank(message = "Enter the store name") @Size(max = 200) String name,
            @NotBlank(message = "Enter the address") @Size(max = 250) String addressLine1,
            @Size(max = 250) String addressLine2,
            @NotBlank(message = "Enter the city") @Size(max = 120) String city,
            @NotBlank(message = "Enter the state") @Size(max = 120) String state,
            @Size(max = 16) String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            /**
             * Who to ring about this branch. D-097.
             *
             * <p>Required, and not the same question as the organisation's
             * contact: an order is filled by a branch, and on a supplier with
             * three of them that is three different people. Onboarding copies
             * the business contact in as the starting point.
             */
            @NotBlank(message = "Enter a contact name for this store")
            @Size(max = 150) String contactName,
            @NotBlank(message = "Enter a contact number for this store")
            @Pattern(regexp = CONTACT_PHONE, message = "That doesn't look like a phone number")
            @Size(max = 32) String contactPhone,
            @Valid OperatingHoursPayload operatingHours,
            @Min(value = 0, message = "Preparation time can't be negative")
            Integer preparationMinutes) {
    }

    public record UpdateStoreRequest(
            @Size(max = 200) String name,
            @Size(max = 250) String addressLine1,
            @Size(max = 250) String addressLine2,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 16) String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            /**
             * Who to ring about this branch. D-097.
             *
             * <p>Required, and not the same question as the organisation's
             * contact: an order is filled by a branch, and on a supplier with
             * three of them that is three different people. Onboarding copies
             * the business contact in as the starting point.
             */
            /**
             * Absent leaves it alone; blank is refused by the service. D-097.
             *
             * <p><b>Not `@NotBlank` here, deliberately.</b> This is a PATCH: a
             * client toggling direct ordering sends that one field and nothing
             * else, and a bean-validation constraint on an optional field
             * rejects every partial update — which it did, until a test caught
             * it.
             */
            @Size(max = 150) String contactName,
            @Pattern(regexp = CONTACT_PHONE, message = "That doesn't look like a phone number")
            @Size(max = 32) String contactPhone,
            @Valid OperatingHoursPayload operatingHours,
            @Min(0) Integer preparationMinutes,
            /**
             * Let restaurants order without asking first.
             *
             * <p>A supplier's own call, because it is a statement about their
             * inventory: "I hold these lines, order them". An operator can set it
             * too — a wholesaler on the phone should not have to find the screen —
             * and either way the change is audited with who made it.
             */
            Boolean directOrdersEnabled,
            @Pattern(regexp = "ACTIVE|OFFLINE", message = "Status must be ACTIVE or OFFLINE")
            String status) {
    }

    public record StoreResponse(
            Long id,
            Long supplierOrganizationId,
            String name,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone,
            OperatingHoursPayload operatingHours,
            /**
             * Read-only to a supplier.
             * <p>It is still returned, because the countdown a supplier sees is
             * measured against it and a number you are held to should be visible.
             * Changing it is an operations decision (doc 13): a supplier who could
             * set their own answer window could set it to an hour and never be
             * late again, and "responds quickly" would stop meaning anything
             * across the marketplace.
             */
            Integer responseSlaSeconds,
            Integer preparationMinutes,
            /** Restaurants may order from this store without sending a request first. */
            boolean directOrdersEnabled,
            String status) {
    }

    public record SubmitVerificationRequest(
            @NotBlank(message = "Choose a verification type") String verificationType,
            @NotBlank(message = "Enter the GSTIN")
            // 15 characters: 2 state + 10 PAN + 1 entity + 1 'Z' + 1 checksum.
            @Pattern(regexp = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]",
                     message = "That doesn't look like a valid GSTIN")
            String gstin,
            @NotBlank(message = "Enter the legal name") @Size(max = 250) String legalName,
            @Size(max = 1000) String evidenceUrl) {
    }

    public record VerificationResponse(
            Long id,
            Long supplierOrganizationId,
            String verificationType,
            VerificationStatus status,
            String verificationSource,
            String rejectionReason,
            Instant verifiedAt,
            Instant createdAt) {
    }

    public record AddSupplierUserRequest(
            @NotBlank(message = "Enter the mobile number") String phone,
            String country,
            @NotBlank(message = "Choose a role") String roleCode,
            @Size(max = 150) String name,
            /** Null grants at organisation scope, reaching every store. */
            Long storeId) {
    }

    public record SupplierUserResponse(
            Long userId,
            String phone,
            String name,
            String membershipStatus,
            List<String> permissions) {
    }
}
