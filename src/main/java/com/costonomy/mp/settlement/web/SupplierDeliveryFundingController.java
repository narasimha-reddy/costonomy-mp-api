package com.costonomy.mp.settlement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.settlement.service.SupplierDeliveryFundingPaymentService;
import com.costonomy.mp.settlement.web.dto.SettlementDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Direct payment collection for supplier-funded deliveries. Doc 05, D-203.
 *
 * <p>Enables suppliers to settle pending delivery funding fees directly via
 * payment gateway (UPI/Razorpay) when an order cannot be deducted from settlement
 * (e.g. credit orders) or when the supplier settles immediately.
 */
@RestController
@RequestMapping("/api/v1/supplier-stores/{storeId}/delivery-funding")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Delivery Funding")
public class SupplierDeliveryFundingController {

    private final SupplierDeliveryFundingPaymentService paymentService;

    @GetMapping("/pending")
    @Operation(summary = "Pending delivery funding deductions",
            description = "Lists pending delivery fees owed by the supplier that have not yet been settled or paid.")
    public ApiResponse<List<PendingDeliveryFundingResponse>> listPending(
            @PathVariable Long storeId) {
        return ApiResponse.ok(paymentService.listPending(ActorContext.requireUserId(), storeId));
    }

    @PostMapping("/{deductionId}/pay")
    @Operation(summary = "Initiate direct payment for delivery fee",
            description = "Opens a Razorpay checkout authorization intent with auto-capture for the deduction amount.")
    public ApiResponse<InitiateDeliveryFundingPaymentResponse> initiatePayment(
            @PathVariable Long storeId,
            @PathVariable Long deductionId,
            @RequestBody(required = false) InitiateDeliveryFundingPaymentRequest request) {
        String clientKey = request != null ? request.clientKey() : null;
        return ApiResponse.ok(paymentService.initiatePayment(
                ActorContext.requireUserId(), storeId, deductionId, clientKey));
    }

    @PostMapping("/{deductionId}/confirm")
    @Operation(summary = "Confirm direct payment for delivery fee",
            description = "Verifies checkout signature and payment capture with Razorpay, marks deduction PAID_DIRECTLY, and records settlement.")
    public ApiResponse<ConfirmDeliveryFundingPaymentResponse> confirmPayment(
            @PathVariable Long storeId,
            @PathVariable Long deductionId,
            @Valid @RequestBody ConfirmDeliveryFundingPaymentRequest request) {
        return ApiResponse.ok(paymentService.confirmPayment(
                ActorContext.requireUserId(), storeId, deductionId, request));
    }
}
