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

    @GetMapping("/history")
    @Operation(summary = "Every delivery funding line, in any state",
            description = "Pending, paid directly, taken from a settlement, waived and credited, newest first, with the order "
                    + "and delivery each belongs to and what to do next. Optional status filter; paged, at most 100 a page.")
    public ApiResponse<List<DeliveryFundingLine>> history(
            @PathVariable Long storeId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(paymentService.listHistory(ActorContext.requireUserId(), storeId, status, page, size));
    }

    @GetMapping("/summary")
    @Operation(summary = "What the store owes, has paid directly, has had taken from payouts, and has been credited",
            description = "Four separate buckets that do not overlap. None of it is a payment to a courier.")
    public ApiResponse<DeliveryFundingSummary> summary(@PathVariable Long storeId) {
        return ApiResponse.ok(paymentService.summary(ActorContext.requireUserId(), storeId));
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
