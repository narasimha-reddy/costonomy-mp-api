package com.costonomy.mp.delivery.slot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public final class DeliverySlotDtos {

    private DeliverySlotDtos() {
    }

    public record DeliverySlotResponse(
            Long id,
            Long supplierStoreId,
            String slotName,
            LocalTime startTime,
            LocalTime endTime,
            LocalTime orderCutoffTime,
            Integer maxOrdersPerDay,
            boolean active) {
    }

    public record AvailableSlotResponse(
            Long id,
            String slotName,
            LocalTime startTime,
            LocalTime endTime,
            LocalTime orderCutoffTime,
            Integer maxOrdersPerDay,
            int bookedOrders,
            int availableCapacity,
            boolean available,
            String unavailableReason) {
    }

    public record CreateDeliverySlotRequest(
            @NotBlank @Size(max = 64) String slotName,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull LocalTime orderCutoffTime,
            @NotNull @Positive Integer maxOrdersPerDay) {
    }

    public record UpdateDeliverySlotRequest(
            @NotBlank @Size(max = 64) String slotName,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull LocalTime orderCutoffTime,
            @NotNull @Positive Integer maxOrdersPerDay,
            Boolean active) {
    }
}
