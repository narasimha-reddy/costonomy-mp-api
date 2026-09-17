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

    /**
     * @param paymentIntents what the client needs to pay, one per supplier order.
     *                       The orders are in DRAFT and no supplier can see them
     *                       until each payment authorises (guardrail 16).
     */
    public record SubmitResponse(
            Long procurementId,
            ProcurementStatus status,
            List<SupplierOrderResponse> supplierOrders,
            List<PaymentIntentResponse> paymentIntents) {
    }

    public record PaymentIntentResponse(
            Long supplierOrderId,
            Long paymentId,
            String provider,
            /** The intent to open the provider's checkout against. */
            String providerOrderId,
            BigDecimal amount,
            String currency,
            /** Publishable key. Never a secret. */
            String publicKey) {
    }

    public record SupplierOrderResponse(
            Long id,
            String orderNumber,
            Long supplierStoreId,
            String supplierName,
            String storeName,
            /**
             * Where the order is going.
             * <p>The order number identifies the order to a system; the outlet and
             * the restaurant identify it to a person, which is what a card has to
             * do. Carried here as well as on {@link IncomingOrderResponse} because
             * a restaurant with several outlets reads its own orders by outlet too.
             */
            Long outletId,
            String outletName,
            String restaurantName,
            /** Landmark, else the street line. Where the van actually goes. */
            String outletLocality,
            String outletCity,
            /**
             * Kilometres from the supplier's store to the outlet, one decimal.
             * <p>Great-circle, so it is the leg as the crow flies rather than a
             * driving distance — enough to judge whether an order is worth taking
             * inside a sixty-second window, and honestly {@code null} when either
             * end has no coordinates rather than a fabricated zero (doc 06 §8).
             */
            BigDecimal distanceKm,
            SupplierOrderStatus status,
            /** The authoritative deadline. The client counts down to this, not to a local timer. */
            Instant acceptanceDeadline,
            Integer responseSlaSeconds,
            Instant createdAt,
            BigDecimal subtotal,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            BigDecimal acceptedAmount,
            String paymentMethod,
            String paymentStatus,
            List<SupplierOrderItemResponse> items) {
    }

    // ── Supplier response ────────────────────────────────────────────────

    /**
     * Accept an order in full.
     *
     * <p>Deliberately carries nothing. A supplier accepting is agreeing to the
     * order as sent; any change of quantity is a partial acceptance, which is a
     * different decision with different consequences for the restaurant.
     */
    public record AcceptOrderRequest() {
    }

    public record PartialAcceptRequest(
            @NotEmpty(message = "Answer every line")
            @Valid List<PartialAcceptItem> items,
            @Size(max = 500) String note) {
    }

    /**
     * @param acceptedQuantity 0 to the requested quantity. Zero declines the line
     *                         and must be sent explicitly — an omitted line is an
     *                         unanswered line, not a declined one (doc 04 §11).
     */
    public record PartialAcceptItem(
            @NotNull(message = "Which line?") Long supplierOrderItemId,
            @NotNull(message = "How much can you supply?")
            @DecimalMin(value = "0.0000", message = "Quantity can't be negative")
            BigDecimal acceptedQuantity,
            @Size(max = 500) String reason) {
    }

    /**
     * What a partial acceptance would come to, without making one.
     *
     * <p>The supplier reduces a line and needs to see the order's value follow.
     * That value is money, so guardrail 3 puts it here rather than in the app —
     * and it is computed by the same {@link com.costonomy.mp.procurement.domain.Pricing}
     * calls the acceptance itself uses, so the preview and the outcome cannot
     * disagree about the last paisa.
     *
     * @param anyAccepted false when every line is zero, which the real endpoint
     *                    records as a rejection rather than as a partial
     *                    acceptance of nothing — so the app can stop someone
     *                    declining an order from a button labelled "accept".
     */
    public record PartialAcceptPreview(
            List<PartialAcceptLine> lines,
            BigDecimal acceptedValue,
            BigDecimal acceptedGst,
            BigDecimal acceptedTotal,
            boolean anyAccepted) {
    }

    public record PartialAcceptLine(
            Long supplierOrderItemId,
            BigDecimal acceptedQuantity,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal) {
    }

    public record RejectOrderRequest(
            @NotBlank(message = "Choose a reason")
            String reason,
            @Size(max = 500) String note) {
    }

    /**
     * A supplier's view of an incoming order. Doc 05 §25, §23A.34.
     *
     * @param secondsRemaining computed server-side from the authoritative deadline.
     *                         The client still counts down against
     *                         {@code acceptanceDeadline} — this is the starting
     *                         point, not a substitute for it.
     */
    public record IncomingOrderResponse(
            Long id,
            String orderNumber,
            Long outletId,
            String outletName,
            String restaurantName,
            String outletLocality,
            String outletCity,
            /** Kilometres from this store to the outlet, or null when unknown. */
            BigDecimal distanceKm,
            SupplierOrderStatus status,
            Instant acceptanceDeadline,
            Integer responseSlaSeconds,
            long secondsRemaining,
            /** When the order reached this store. What a supplier means by "when". */
            Instant createdAt,
            BigDecimal subtotal,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            /**
             * What the supplier committed to, below {@code totalAmount} after a
             * partial acceptance and zero before any answer.
             * <p>Without it a client listing accepted orders can only show the
             * requested total, which overstates a partial acceptance — and a
             * supplier reading their own workload off that figure is reading a
             * number they never agreed to.
             */
            BigDecimal acceptedAmount,
            String paymentMethod,
            List<SupplierOrderItemResponse> items) {
    }

    /**
     * An alternative supplier for a requirement item a supplier failed to fulfil.
     * Doc 15, §23A.15.
     */
    public record AlternativesResponse(
            Long requirementId,
            List<RequirementAlternative> items) {
    }

    public record RequirementAlternative(
            Long requirementItemId,
            Long canonicalProductId,
            String productName,
            BigDecimal remainingQuantity,
            String unit,
            /** Ranked offers that can still serve the shortfall. Empty when none can. */
            List<Object> offers,
            String unservedReason) {
    }

    public record SupplierOrderItemResponse(
            Long id,
            Long canonicalProductId,
            String productName,
            /**
             * The canonical product's picture, or null when it has none.
             * <p>Platform-owned (doc 01 §7): the image belongs to the product every
             * supplier maps onto, not to any one supplier's SKU, so two suppliers'
             * paneer show the same paneer. Carried on the line because a client
             * rendering an order must not make one request per item to draw it.
             */
            String productImageUrl,
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
