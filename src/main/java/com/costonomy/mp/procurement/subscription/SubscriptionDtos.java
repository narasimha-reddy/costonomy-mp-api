package com.costonomy.mp.procurement.subscription;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record SubscriptionResponse(
            Long id,
            Long outletId,
            String outletName,
            Long supplierStoreId,
            String storeName,
            Long canonicalProductId,
            String productName,
            String productImage,
            Long supplierSkuId,
            String skuDescription,
            BigDecimal quantity,
            String unit,
            SubscriptionFrequency frequency,
            Long preferredSlotId,
            String preferredSlotName,
            String deliveryMode,
            SubscriptionStatus status,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate nextDeliveryDate,
            List<LocalDate> skipDates,
            String notes) {
    }

    public record CreateSubscriptionRequest(
            @NotNull Long supplierStoreId,
            @NotNull Long supplierSkuId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @NotBlank String unit,
            @NotNull SubscriptionFrequency frequency,
            Long preferredSlotId,
            String deliveryMode,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 500) String notes) {
    }

    public record AddSkipDateRequest(
            @NotNull LocalDate skipDate,
            @Size(max = 255) String reason) {
    }

    public record ManifestItemSummary(
            Long supplierSkuId,
            Long canonicalProductId,
            String productName,
            BigDecimal totalQuantity,
            String unit) {
    }

    public record ManifestDeliveryOrder(
            Long subscriptionId,
            Long outletId,
            String outletName,
            String restaurantName,
            String outletAddress,
            String contactPhone,
            Long slotId,
            String slotName,
            Long supplierSkuId,
            String productName,
            BigDecimal quantity,
            String unit,
            String deliveryMode) {
    }

    public record SubscriptionManifestResponse(
            LocalDate date,
            Long supplierStoreId,
            List<ManifestItemSummary> aggregatedItems,
            List<ManifestDeliveryOrder> deliveries) {
    }

    public record GenerateOrdersResponse(
            LocalDate date,
            int ordersGenerated,
            List<Long> orderIds) {
    }
}
