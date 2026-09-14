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

    private SupplierDtos() {
    }

    public record CreateSupplierRequest(
            @NotBlank(message = "Enter the legal name") @Size(max = 250) String legalName,
            @NotBlank(message = "Enter the display name") @Size(max = 200) String displayName,
            @Size(max = 32) String gstin,
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 255) String contactEmail,
            @Valid CreateStoreRequest firstStore) {
    }

    public record UpdateSupplierRequest(
            @Size(max = 250) String legalName,
            @Size(max = 200) String displayName,
            @Size(max = 32) String gstin,
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 255) String contactEmail) {
    }

    public record SupplierResponse(
            Long id,
            String legalName,
            String displayName,
            String gstin,
            SupplierLifecycleStatus lifecycleStatus,
            VerificationStatus verificationStatus,
            /** Whether this supplier can currently receive orders. */
            boolean canTrade,
            List<StoreResponse> stores) {
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
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            /** Defaults to the platform's 60s. Configurable per store (doc 13). */
            @Min(value = 10, message = "The response window must be at least 10 seconds")
            Integer responseSlaSeconds,
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
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            @Min(10) Integer responseSlaSeconds,
            @Min(0) Integer preparationMinutes,
            @Pattern(regexp = "ACTIVE|OFFLINE", message = "Status must be ACTIVE or OFFLINE")
            String status) {
    }

    public record StoreResponse(
            Long id,
            Long supplierOrganizationId,
            String name,
            String addressLine1,
            String city,
            String state,
            String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone,
            Integer responseSlaSeconds,
            Integer preparationMinutes,
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
