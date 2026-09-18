package com.costonomy.mp.intent.web.dto;

import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.intent.domain.IntentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The request/response contract for intents. §7.
 *
 * <p>Two things about these shapes are deliberate and load-bearing.
 *
 * <p><b>The client never sends a price.</b> Not when building a request, not when
 * the supplier answers, not when the order is created. Guardrail 3: money is the
 * server's, computed through {@code Pricing} from the store's live offer. A
 * supplier changes a price by superseding an offer in their catalog (D-012), not
 * by typing a different number into a response — otherwise the price a restaurant
 * compared on the product screen and the price it is charged could differ, with
 * nothing to reconcile them.
 *
 * <p><b>An intent line names a SKU, not an offer.</b> An offer id would pin a
 * price onto a non-financial record, and the price at request time is not the
 * price that gets charged — the supplier's answer decides that. So the basket
 * holds what was asked for, and nothing about what it costs.
 */
public final class IntentDtos {

    private IntentDtos() {
    }

    // ── Restaurant: building the request ─────────────────────────────────

    /**
     * Add a supplier's pack to a request.
     *
     * <p>No intent id: the SKU determines the supplier, and the supplier
     * determines which draft this belongs in. One draft per outlet per store, so
     * adding paneer and then rice from the same store builds one request rather
     * than two.
     */
    public record AddItemRequest(
            @NotNull Long supplierSkuId,
            @NotNull @DecimalMin(value = "0.0001", message = "Quantity must be more than zero.")
            BigDecimal quantity,
            @Size(max = 500) String notes) {
    }

    /**
     * Change a line's quantity.
     *
     * <p>Zero removes the line, which is what a stepper counted down to zero
     * means. Rejecting it would leave the app holding a line the person has
     * plainly finished with.
     */
    public record UpdateItemRequest(
            @NotNull @DecimalMin(value = "0", message = "Quantity cannot be negative.")
            BigDecimal quantity) {
    }

    /** Send a draft to the supplier. */
    public record SendRequest(
            Instant requestedDeliveryTime,
            @Size(max = 1000) String notes) {
    }

    // ── Supplier: answering ──────────────────────────────────────────────

    /**
     * What this store will supply.
     *
     * <p>Every line must be answered — see {@code IntentAcceptanceService} for
     * why an omitted line is rejected rather than read as a zero.
     */
    public record RespondRequest(
            @NotEmpty @Valid List<RespondLine> lines,
            Integer etaMinutes,
            @Size(max = 32) String deliveryMode,
            @Size(max = 1000) String notes) {
    }

    /**
     * One line of an answer.
     *
     * <p>{@code offeredQuantity} may be zero — "not this one" — but never more
     * than was asked for. A supplier who wants to sell more is making an offer
     * nobody asked for, and the restaurant would be charged for it.
     */
    public record RespondLine(
            @NotNull Long intentItemId,
            @NotNull @DecimalMin(value = "0", message = "Offered quantity cannot be negative.")
            BigDecimal offeredQuantity,
            @Size(max = 500) String notes) {
    }

    // ── Restaurant: turning an answer into an order ──────────────────────

    /**
     * Create the order. This is the financial step, and the first one.
     *
     * <p>{@code lines} is optional: absent means "everything the supplier
     * offered", which is the common case and saves the client echoing back
     * figures it did not choose. Present, each quantity must be between zero and
     * what was offered — the restaurant may take less than is on the table
     * (§12), never more.
     */
    public record CreateOrderRequest(
            @Valid List<OrderLine> lines,
            @Size(max = 32) String paymentMethod) {
    }

    public record OrderLine(
            @NotNull Long intentItemId,
            @NotNull @DecimalMin(value = "0", message = "Quantity cannot be negative.")
            BigDecimal quantity) {
    }

    // ── Responses ────────────────────────────────────────────────────────

    /**
     * An intent, whichever side is looking at it.
     *
     * <p>{@code status} is where it is in its life; {@code fulfilment} is how much
     * of it the supplier agreed to. Both are here because they answer different
     * questions and a screen needs both — an intent can be {@code ORDERED} and
     * {@code PARTIALLY_FULFILLED}, and the restaurant's filter is asking the
     * second question (§4).
     *
     * <p>{@code serverTime} is the instant this response was built, so a client
     * can count down to either deadline against the clock that will actually
     * enforce it rather than against the phone's.
     *
     * <p>Two deadlines, and they belong to different people.
     * {@code responseDeadline} is the supplier's — how long they have to answer.
     * {@code orderCreationDeadline} is the restaurant's — how long they have to
     * order once answered. Only one is ever live at a time.
     */
    public record IntentResponse(
            Long id,
            String reference,
            Long outletId,
            Long supplierStoreId,
            String storeName,
            String supplierName,
            IntentStatus status,
            IntentFulfilment fulfilment,
            String source,
            Long clonedFromId,
            Instant requestedDeliveryTime,
            String notes,
            Instant sentAt,
            /**
             * When the supplier's chance to answer runs out, and the window it
             * came from. Both are the store's own promise, frozen at send.
             */
            Instant responseDeadline,
            Integer responseWindowSeconds,
            Instant acceptedAt,
            Instant orderCreationDeadline,
            Integer orderCreationWindowSeconds,
            Instant cancelledAt,
            Instant expiredAt,
            Instant createdAt,
            Instant serverTime,
            boolean editable,
            /** True only while an order may still be created from this. */
            boolean withinOrderWindow,
            List<IntentItemResponse> items,
            AcceptanceResponse acceptance,
            /** The order this became, if it became one. */
            Long supplierOrderId,
            String supplierOrderNumber) {
    }

    /**
     * A line, with the answer to it folded in.
     *
     * <p>Requested and offered sit side by side rather than one replacing the
     * other: a shortfall is a fact about the request, and a screen showing only
     * the offered figure cannot tell anybody what they asked for.
     */
    public record IntentItemResponse(
            Long id,
            Long supplierSkuId,
            Long canonicalProductId,
            String productName,
            String skuName,
            String packLabel,
            String imageUrl,
            BigDecimal requestedQuantity,
            String unit,
            String notes,
            String status,
            IntentFulfilment fulfilment,
            /** Null until the supplier answers. Zero means they declined this line. */
            BigDecimal offeredQuantity,
            String availability,
            BigDecimal unitPrice,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal,
            BigDecimal gstRate,
            String supplierNotes) {
    }

    /** The supplier's commercial statement. Non-financial until an order exists. */
    public record AcceptanceResponse(
            Long id,
            IntentAcceptanceStatus status,
            BigDecimal offeredValue,
            BigDecimal offeredGst,
            BigDecimal offeredTotal,
            BigDecimal deliveryFee,
            Integer etaMinutes,
            String deliveryMode,
            String notes,
            Instant submittedAt,
            Instant expiresAt) {
    }

    /**
     * What creating the order right now would cost, and what would stop it.
     *
     * <p>Separate from the create call because this is the screen the restaurant
     * pays from, and it must be able to see the figure before committing to it.
     *
     * <p><b>There is no price-change flow here, deliberately.</b> An acceptance is
     * a quote with a deadline, and within that deadline the quoted price holds —
     * that is what makes the deadline mean something to either party. The
     * supplier is protected by the window being short; the restaurant is protected
     * by the price not moving inside it. A supplier who repriced their catalogue
     * after committing does not get to reprice a live commitment, so there is
     * nothing for §23A.16 to show and nothing for the restaurant to re-confirm.
     * The old cart needed that flow because it collected money against prices
     * nobody had agreed to yet.
     *
     * <p>{@code blockers} therefore holds only things that make the order
     * impossible rather than merely more expensive.
     */
    public record OrderPreviewResponse(
            Long intentId,
            boolean creatable,
            Instant orderCreationDeadline,
            Instant serverTime,
            List<PreviewLine> lines,
            BigDecimal subtotal,
            BigDecimal gstAmount,
            BigDecimal total,
            List<Blocker> blockers) {
    }

    public record PreviewLine(
            Long intentItemId,
            Long supplierSkuId,
            String productName,
            String skuName,
            BigDecimal offeredQuantity,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal) {
    }

    public record Blocker(Long intentItemId, String productName, String code, String message) {
    }

    /** The order an intent became. */
    public record CreateOrderResponse(
            Long intentId,
            Long supplierOrderId,
            String orderNumber,
            BigDecimal totalAmount,
            String paymentMethod,
            String paymentStatus,
            PaymentIntent payment) {
    }

    /** What the client still has to complete before the supplier sees anything. */
    public record PaymentIntent(
            Long supplierOrderId,
            Long paymentId,
            String provider,
            String providerOrderId,
            BigDecimal amount,
            String currency,
            String publicKey) {
    }
}
