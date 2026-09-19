package com.costonomy.mp.intent.web.dto;

import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.procurement.domain.DeliveryMode;
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
            /**
             * <b>Not asked for, and not shown.</b> A supplier says what they can
             * supply, not when it lands: delivery is quoted when a courier is
             * assigned (doc 06 §4), so an estimate typed here would be a promise
             * made by somebody who does not control it — and the restaurant would
             * read it as one.
             *
             * <p>Kept on the contract because own-delivery suppliers may state
             * their own timing later, and because a client still sending it does
             * no harm.
             */
            /**
             * The revision the supplier was looking at when they decided.
             *
             * <p>Optional, because an older client may not send it — but when it
             * is present and stale the response is refused rather than applied.
             * A supplier accepting 4 KG of something the restaurant cut to 2 an
             * instant earlier would be committing stock nobody asked for, and
             * would find out at delivery.
             */
            Long expectedRevision,
            Integer etaMinutes,
            /**
             * Which modes this store can serve, comma separated. D-091.
             *
             * <p>The supplier says what is possible; the restaurant picks, because
             * the restaurant pays the delivery fee.
             */
            @Size(max = 120) String deliveryModes,
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
            @Size(max = 32) String paymentMethod,
            /**
             * How the goods should travel. D-091.
             *
             * <p>The restaurant's choice, made here because the restaurant pays
             * the delivery fee and the fee is part of what is charged — so it has
             * to be settled before the payment intent exists.
             *
             * <p><b>Not {@code @NotNull}</b>, because this record is also the
             * preview's request and a preview is about the goods: requiring a
             * mode there would make the restaurant choose how something travels
             * before being shown what it costs. {@code create} requires it.
             */
            DeliveryMode deliveryMode,
            /**
             * The quote being spent, for {@code COSTONOMY_DELIVERY}.
             *
             * <p>Required for that mode and ignored for the others. The fee comes
             * from the stored quote rather than being recomputed: a figure
             * recalculated between the screen that showed it and the charge that
             * collected it is a silent reprice (§23A.16).
             */
            @Size(max = 64) String deliveryQuoteReference) {
    }

    /** What a Costonomy delivery would cost for this request. D-091. */
    public record DeliveryQuoteResponse(
            String quoteReference,
            BigDecimal fee,
            String currency,
            Integer etaMinutes,
            Double distanceKm,
            Instant expiresAt) {
    }

    public record OrderLine(
            @NotNull Long intentItemId,
            @NotNull @DecimalMin(value = "0", message = "Quantity cannot be negative.")
            BigDecimal quantity) {
    }

    // ── Responses ────────────────────────────────────────────────────────

    /**
     * The whole basket: every unsent request, and what the lot would come to.
     *
     * <p>A wrapper rather than a bare list because the basket-wide total has to
     * come from the server. Summing the per-supplier totals on the client would
     * be arithmetic on money, which is the one thing guardrail 3 forbids — and it
     * is exactly the kind of sum that quietly disagrees with the server's own
     * rounding.
     */
    public record BasketResponse(
            List<IntentResponse> requests,
            int supplierCount,
            int itemCount,
            BigDecimal agreedValue,
            BigDecimal agreedGst,
            BigDecimal agreedTotal,
            /** False when any line anywhere in the basket could not be priced. */
            boolean pricedComplete,
            /** True when any line anywhere has been repriced since it was added. */
            boolean priceChanged) {
    }

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
            /**
             * Who is asking, for the supplier's side of the card.
             *
             * <p>A supplier deciding whether to hold stock is deciding for a
             * particular kitchen in a particular place — the same question the
             * order card answers with party and distance, one step earlier.
             */
            String outletName,
            String restaurantName,
            String outletLocality,
            String outletCity,
            /** Store to outlet, straight line, one decimal. Null without coordinates. */
            BigDecimal distanceKm,
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
            /**
             * Whether a line's quantity may still be changed — true through
             * {@code OPEN}, where {@code editable} is already false.
             *
             * <p>Separate from {@code editable} because the two differ by a
             * state: the shape of a sent request is fixed, its quantities are
             * not, until the supplier answers. A client showing an edit control
             * reads this one.
             */
            boolean quantityEditable,
            /**
             * What revision of this request the reader is looking at.
             *
             * <p>The intent's own {@code @Version}, which a quantity edit or a
             * line removal force-bumps precisely so it tracks the *content* and
             * not just the row. A supplier sends it back when they accept, and
             * the server refuses if it has moved — so nobody commits stock
             * against a list that changed while they were reading it.
             */
            long revision,
            /** True only while an order may still be created from this. */
            boolean withinOrderWindow,
            List<IntentItemResponse> items,
            /**
             * What this request comes to at the price it is asked at.
             *
             * <p>{@code pricedComplete} false means at least one line has no
             * price, so the total is short of the whole and the screen must say
             * so rather than presenting a confident number that is quietly
             * missing an item. {@code priceChanged} means at least one line has
             * been repriced since it was added, and sending will stop to ask.
             */
            BigDecimal agreedValue,
            BigDecimal agreedGst,
            BigDecimal agreedTotal,
            boolean pricedComplete,
            boolean priceChanged,
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
            /**
             * How the pack is described, in the one shape every screen uses.
             *
             * <p>Was a loose {@code skuName} plus a pre-joined pack label, which
             * left each screen to decide what a pack looks like — and they
             * disagreed. {@code SkuDirectory} owns the shape now.
             */
            SkuDirectory.SkuDescriptor sku,
            BigDecimal requestedQuantity,
            String unit,
            String notes,
            // No per-line status. It was a constant 'REQUESTED' every client
            // received and none could act on; the line's answer is its offered
            // quantity and the fulfilment derived from it. Dropped in V29.
            IntentFulfilment fulfilment,
            /** Null until the supplier answers. Zero means they declined this line. */
            BigDecimal offeredQuantity,
            String availability,
            BigDecimal unitPrice,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal,
            BigDecimal gstRate,
            String supplierNotes,
            /**
             * The price this line is asked at, and what it comes to.
             *
             * <p>Real, not an estimate. While the request is a draft this is the
             * supplier's current listed price; sending the request locks it, and
             * the supplier's reply then confirms that price or declines the line.
             * The order is created on the same figure, so it is the one number
             * that runs from basket to invoice.
             *
             * <p>Null when the SKU has no live offer — out of stock, or delisted
             * since it was added. Absent rather than zero, because a missing
             * price is not a free product.
             */
            BigDecimal agreedUnitPrice,
            /**
             * The same price with its GST added — what a unit actually costs.
             *
             * <p>Computed here rather than in the app, which is not allowed to do
             * money arithmetic, and which would otherwise be multiplying by
             * {@code 1 + rate/100} and landing a paisa off the line totals.
             */
            BigDecimal agreedUnitPriceInclusiveGst,
            BigDecimal agreedGstRate,
            BigDecimal agreedLineValue,
            BigDecimal agreedLineGst,
            BigDecimal agreedLineTotal,
            /**
             * The supplier has repriced since this line was added.
             *
             * <p>Draft-only, and the reason the send flow stops to ask: a figure
             * somebody put in a basket yesterday must not quietly become a
             * different one when they send it.
             */
            boolean priceChanged,
            BigDecimal previousUnitPrice) {
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
            String deliveryModes,
            String notes,
            Instant submittedAt,
            Instant expiresAt) {
    }

    /**
     * What a reply would come to, before sending it.
     *
     * <p>Exists because the supplier moves a stepper and the total has to follow,
     * and the app is not allowed to multiply a price by a quantity itself
     * (guardrail 3). So the arithmetic round-trips — the same trade the order
     * flow's partial-acceptance preview made.
     *
     * <p>Writes nothing. Quantities above what was asked for are clamped rather
     * than refused: this is a figure being explored, and an error in place of a
     * number would leave the supplier with nothing to read.
     */
    public record RespondPreviewResponse(
            Long intentId,
            List<RespondPreviewLine> lines,
            BigDecimal offeredValue,
            BigDecimal offeredGst,
            BigDecimal offeredTotal) {
    }

    public record RespondPreviewLine(
            Long intentItemId,
            String productName,
            BigDecimal requestedQuantity,
            BigDecimal offeredQuantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal) {
    }

    /** Send every draft in the basket, one request per supplier. */
    public record SendBasketRequest(
            /**
             * Confirmation that the caller has seen the repricing.
             *
             * <p>Absent or false, a repriced request is held back rather than
             * sent — §23A.16: a price that moved is shown, old and new, and
             * requires the user's agreement. Never absorbed.
             */
            boolean acceptPriceChanges,
            Instant requestedDeliveryTime,
            @Size(max = 1000) String notes) {
    }

    /**
     * What went, and what is waiting on a decision.
     *
     * <p>Requests with no price change are sent immediately and the repriced
     * ones are held, so one supplier's overnight price rise does not stall the
     * other two. {@code held} is empty on the happy path.
     */
    public record SendBasketResponse(
            List<IntentResponse> sent,
            List<HeldRequest> held) {
    }

    public record HeldRequest(
            Long intentId,
            String reference,
            String storeName,
            List<PriceChange> changes) {
    }

    /** Old and new, both, so §23A.16 can show what moved. */
    public record PriceChange(
            Long intentItemId,
            String productName,
            BigDecimal previousUnitPrice,
            BigDecimal currentUnitPrice,
            BigDecimal previousLineTotal,
            BigDecimal currentLineTotal) {
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
