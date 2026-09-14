package com.costonomy.mp.procurement.web.dto;

import com.costonomy.mp.procurement.domain.ApprovalStatus;
import com.costonomy.mp.procurement.domain.ProcurementStatus;
import com.costonomy.mp.procurement.domain.RequirementStatus;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProcurementDtos {

    private ProcurementDtos() {
    }

    // ── Requirements ─────────────────────────────────────────────────────

    public record CreateRequirementRequest(
            @NotEmpty(message = "Add at least one product")
            @Valid List<RequirementItemRequest> items,
            @Size(max = 1000) String notes,
            Instant neededBy,
            /** MANUAL or RECOMMENDED. */
            @Pattern(regexp = "MANUAL|RECOMMENDED") String source) {
    }

    public record RequirementItemRequest(
            @NotNull(message = "Choose a product") Long canonicalProductId,
            @NotNull(message = "Enter a quantity")
            @DecimalMin(value = "0.0001", message = "Quantity must be greater than zero")
            BigDecimal quantity,
            @NotBlank(message = "Enter a unit") @Size(max = 32) String unit,
            @Size(max = 500) String notes) {
    }

    public record RequirementResponse(
            Long id,
            Long outletId,
            RequirementStatus status,
            String source,
            String notes,
            Instant neededBy,
            Instant createdAt,
            List<RequirementItemResponse> items) {
    }

    /**
     * @param remainingQuantity what is still needed. §23A.14 requires this to be
     *                          explicit, and guardrail 14 requires it to survive a
     *                          supplier rejecting, timing out or partially accepting.
     */
    public record RequirementItemResponse(
            Long id,
            Long canonicalProductId,
            String productName,
            BigDecimal requestedQuantity,
            BigDecimal fulfilledQuantity,
            BigDecimal remainingQuantity,
            String unit,
            String status,
            String notes) {
    }

    // ── Cart ─────────────────────────────────────────────────────────────

    public record AddCartItemRequest(
            @NotNull(message = "Choose an offer") Long supplierOfferId,
            @NotNull(message = "Enter a quantity")
            @DecimalMin(value = "0.0001", message = "Quantity must be greater than zero")
            BigDecimal quantity,
            /** Links this line to the need it serves, so a shortfall can be credited back. */
            Long requirementItemId) {
    }

    public record UpdateCartItemRequest(
            @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity) {
    }

    public record SetPaymentMethodRequest(
            @Pattern(regexp = "PREPAID|CREDIT", message = "Payment method must be PREPAID or CREDIT")
            @NotBlank String paymentMethod) {
    }

    // ── Procurement ──────────────────────────────────────────────────────

    /**
     * A cart or an order, depending on its status. Doc 05 §11: the restaurant sees
     * one thing even though the backend splits it by supplier.
     *
     * @param priceChanges lines whose live price no longer matches the snapshot.
     *                     Non-empty means checkout is blocked until the restaurant
     *                     confirms — §23A.16 and guardrail 13.
     * @param blockers     everything else preventing submission, each with a reason.
     */
    public record ProcurementResponse(
            Long id,
            Long outletId,
            Long requirementId,
            ProcurementStatus status,
            ApprovalStatus approvalStatus,
            String approvalReason,
            List<String> approverRoles,
            String paymentMethod,
            BigDecimal totalItemValue,
            BigDecimal totalGst,
            BigDecimal totalDeliveryFee,
            BigDecimal totalAmount,
            Instant validatedAt,
            boolean validationStale,
            boolean submittable,
            List<SupplierGroup> supplierGroups,
            List<PriceChange> priceChanges,
            List<Blocker> blockers) {
    }

    /** Cart lines grouped by supplier, as §23A.16 renders them. */
    public record SupplierGroup(
            Long supplierStoreId,
            String supplierName,
            String storeName,
            Integer responseSlaSeconds,
            BigDecimal itemValue,
            BigDecimal gst,
            BigDecimal total,
            List<ProcurementItemResponse> items) {
    }

    public record ProcurementItemResponse(
            Long id,
            Long canonicalProductId,
            String productName,
            Long supplierSkuId,
            String skuName,
            String brandName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal lineItemValue,
            BigDecimal lineGst,
            BigDecimal lineTotal,
            String availability) {
    }

    /**
     * A price that moved between adding to cart and checking out.
     *
     * <p>Both figures are returned. §23A.16: show the old and the new, and require
     * explicit confirmation — never absorb the difference.
     */
    public record PriceChange(
            Long procurementItemId,
            String productName,
            String supplierName,
            BigDecimal previousUnitPrice,
            BigDecimal newUnitPrice,
            BigDecimal previousLineTotal,
            BigDecimal newLineTotal) {
    }

    /** Something preventing submission, with a reason the restaurant can act on. */
    public record Blocker(Long procurementItemId, String code, String message) {
    }

    public record ValidateRequest(
            /**
             * Set true to accept the new prices found by a previous validation.
             * Required by §23A.16: a price change needs an explicit acceptance,
             * not a silent re-read.
             */
            boolean acceptPriceChanges) {
    }

    public record RejectRequest(@Size(max = 500) String reason) {
    }

    // ── Supplier orders ──────────────────────────────────────────────────

    public record SubmitResponse(
            Long procurementId,
            ProcurementStatus status,
            List<SupplierOrderResponse> supplierOrders) {
    }

    public record SupplierOrderResponse(
            Long id,
            String orderNumber,
            Long supplierStoreId,
            String supplierName,
            String storeName,
            SupplierOrderStatus status,
            /** The authoritative deadline. The client counts down to this, not to a local timer. */
            Instant acceptanceDeadline,
            Integer responseSlaSeconds,
            BigDecimal subtotal,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            BigDecimal acceptedAmount,
            String paymentMethod,
            String paymentStatus,
            List<SupplierOrderItemResponse> items) {
    }

    public record SupplierOrderItemResponse(
            Long id,
            Long canonicalProductId,
            String productName,
            String skuName,
            BigDecimal requestedQuantity,
            /** Null until the supplier answers; zero means they declined this line. */
            BigDecimal acceptedQuantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal lineTotal,
            String status) {
    }
}
