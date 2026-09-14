package com.costonomy.mp.restaurant.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class RestaurantDtos {

    private RestaurantDtos() {
    }

    public record CreateRestaurantRequest(
            @NotBlank(message = "Enter the restaurant name") @Size(max = 200) String name,
            @Size(max = 250) String legalName,
            @Size(max = 32) String gstin,
            /** Optional first outlet, so setup (§23A.8) is one call rather than two. */
            @Valid CreateOutletRequest firstOutlet) {
    }

    public record UpdateRestaurantRequest(
            @Size(max = 200) String name,
            @Size(max = 250) String legalName,
            @Size(max = 32) String gstin) {
    }

    public record RestaurantResponse(
            Long id,
            String name,
            String legalName,
            String gstin,
            String status,
            List<OutletResponse> outlets) {
    }

    public record CreateOutletRequest(
            @NotBlank(message = "Enter the outlet name") @Size(max = 200) String name,
            @NotBlank(message = "Enter the address") @Size(max = 250) String addressLine1,
            @Size(max = 250) String addressLine2,
            @Size(max = 250) String landmark,
            @NotBlank(message = "Enter the city") @Size(max = 120) String city,
            @NotBlank(message = "Enter the state") @Size(max = 120) String state,
            @Size(max = 16) String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 255) String googlePlaceId,
            @Size(max = 500) String formattedAddress,
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 1000) String deliveryInstructions) {
    }

    public record UpdateOutletRequest(
            @Size(max = 200) String name,
            @Size(max = 250) String addressLine1,
            @Size(max = 250) String addressLine2,
            @Size(max = 250) String landmark,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 16) String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 255) String googlePlaceId,
            @Size(max = 500) String formattedAddress,
            @Size(max = 150) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 1000) String deliveryInstructions,
            @Size(max = 32) String status) {
    }

    public record OutletResponse(
            Long id,
            Long restaurantId,
            String name,
            String addressLine1,
            String addressLine2,
            String landmark,
            String city,
            String state,
            String pincode,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone,
            String deliveryInstructions,
            String status) {
    }

    /** Invite or assign a member. The role determines what they can actually do. */
    public record AddOutletUserRequest(
            @NotBlank(message = "Enter the mobile number") String phone,
            String country,
            @NotBlank(message = "Choose a role") String roleCode,
            @Size(max = 150) String name) {
    }

    public record OutletUserResponse(
            Long userId,
            String phone,
            String name,
            String membershipStatus,
            List<String> roles,
            List<String> permissions) {
    }
}
