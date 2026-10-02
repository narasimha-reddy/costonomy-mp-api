package com.costonomy.mp.procurement.subscription;

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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    @PostMapping("/outlets/{outletId}/subscriptions")
    @Operation(summary = "Create a recurring replenishment subscription for an outlet")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> create(
            @PathVariable Long outletId,
            @Valid @RequestBody SubscriptionDtos.CreateSubscriptionRequest request) {
        return ApiResponse.ok(subscriptions.createSubscription(ActorContext.requireUserId(), outletId, request));
    }

    @GetMapping("/outlets/{outletId}/subscriptions")
    @Operation(summary = "List all subscriptions for an outlet")
    public ApiResponse<List<SubscriptionDtos.SubscriptionResponse>> listByOutlet(@PathVariable Long outletId) {
        return ApiResponse.ok(subscriptions.listByOutlet(ActorContext.requireUserId(), outletId));
    }

    @GetMapping("/subscriptions/{id}")
    @Operation(summary = "Get subscription details")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(subscriptions.getSubscription(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/subscriptions/{id}/pause")
    @Operation(summary = "Pause an active subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> pause(@PathVariable Long id) {
        return ApiResponse.ok(subscriptions.pauseSubscription(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/subscriptions/{id}/resume")
    @Operation(summary = "Resume a paused subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> resume(@PathVariable Long id) {
        return ApiResponse.ok(subscriptions.resumeSubscription(ActorContext.requireUserId(), id));
    }

    @DeleteMapping("/subscriptions/{id}")
    @Operation(summary = "Cancel a subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> cancel(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ApiResponse.ok(subscriptions.cancelSubscription(ActorContext.requireUserId(), id, reason));
    }

    @PostMapping("/subscriptions/{id}/skip-dates")
    @Operation(summary = "Add a skip date for a subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> addSkipDate(
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionDtos.AddSkipDateRequest request) {
        return ApiResponse.ok(subscriptions.addSkipDate(ActorContext.requireUserId(), id, request));
    }

    @DeleteMapping("/subscriptions/{id}/skip-dates/{date}")
    @Operation(summary = "Remove a skip date from a subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionResponse> removeSkipDate(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(subscriptions.removeSkipDate(ActorContext.requireUserId(), id, date));
    }

    @GetMapping("/supplier-stores/{storeId}/subscriptions")
    @Operation(summary = "List all subscriptions fulfilled by a supplier store")
    public ApiResponse<List<SubscriptionDtos.SubscriptionResponse>> listByStore(@PathVariable Long storeId) {
        return ApiResponse.ok(subscriptions.listBySupplierStore(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/supplier-stores/{storeId}/subscriptions/manifest")
    @Operation(summary = "Get daily operational manifest with aggregated SKU volume requirements and order deliveries")
    public ApiResponse<SubscriptionDtos.SubscriptionManifestResponse> manifest(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(subscriptions.getManifest(ActorContext.requireUserId(), storeId, date));
    }

    @PostMapping("/supplier-stores/{storeId}/subscriptions/generate-orders")
    @Operation(summary = "Trigger generation of daily replenishment orders for a target date")
    public ApiResponse<SubscriptionDtos.GenerateOrdersResponse> generateOrders(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(subscriptions.generateDailyOrders(ActorContext.requireUserId(), storeId, date));
    }
}
