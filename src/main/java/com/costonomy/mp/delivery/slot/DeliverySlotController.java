package com.costonomy.mp.delivery.slot;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/supplier-stores/{storeId}")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Delivery Slots")
public class DeliverySlotController {

    private final DeliverySlotService slotService;

    @GetMapping("/delivery-slots")
    @Operation(summary = "List all delivery slots configured for a supplier store")
    public ApiResponse<List<DeliverySlotDtos.DeliverySlotResponse>> listSlots(@PathVariable Long storeId) {
        return ApiResponse.ok(slotService.listSlots(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/available-slots")
    @Operation(summary = "Get available delivery slots for a target date with capacity and cutoff checks")
    public ApiResponse<List<DeliverySlotDtos.AvailableSlotResponse>> availableSlots(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(slotService.getAvailableSlots(storeId, date));
    }

    @PostMapping("/delivery-slots")
    @Operation(summary = "Create a new delivery slot for a supplier store")
    public ApiResponse<DeliverySlotDtos.DeliverySlotResponse> createSlot(
            @PathVariable Long storeId,
            @Valid @RequestBody DeliverySlotDtos.CreateDeliverySlotRequest request) {
        return ApiResponse.ok(slotService.createSlot(ActorContext.requireUserId(), storeId, request));
    }

    @PutMapping("/delivery-slots/{slotId}")
    @Operation(summary = "Update an existing delivery slot")
    public ApiResponse<DeliverySlotDtos.DeliverySlotResponse> updateSlot(
            @PathVariable Long storeId,
            @PathVariable Long slotId,
            @Valid @RequestBody DeliverySlotDtos.UpdateDeliverySlotRequest request) {
        return ApiResponse.ok(slotService.updateSlot(ActorContext.requireUserId(), storeId, slotId, request));
    }

    @DeleteMapping("/delivery-slots/{slotId}")
    @Operation(summary = "Deactivate a delivery slot")
    public ApiResponse<Void> deleteSlot(
            @PathVariable Long storeId,
            @PathVariable Long slotId) {
        slotService.deleteSlot(ActorContext.requireUserId(), storeId, slotId);
        return ApiResponse.ok(null);
    }
}
