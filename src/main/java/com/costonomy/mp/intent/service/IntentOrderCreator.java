package com.costonomy.mp.intent.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentAcceptance;
import com.costonomy.mp.intent.domain.IntentAcceptanceItem;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentItem;
import com.costonomy.mp.intent.domain.IntentOrderLink;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentAcceptanceItemRepository;
import com.costonomy.mp.intent.repository.IntentAcceptanceRepository;
import com.costonomy.mp.intent.repository.IntentItemRepository;
import com.costonomy.mp.intent.repository.IntentOrderLinkRepository;
import com.costonomy.mp.intent.repository.IntentRepository;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import com.costonomy.mp.delivery.service.DeliveryDirectory;
import com.costonomy.mp.delivery.service.DeliveryFeeQuoteService;
import com.costonomy.mp.procurement.domain.DeliveryMode;
import com.costonomy.mp.procurement.domain.OrderItemStatus;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.OrderNumberGenerator;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.service.OrderFunding;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import com.costonomy.mp.procurement.service.ProcurementDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns an accepted intent into an order. §13.
 *
 * <p><b>This is where money starts, and the first place it appears.</b> Everything
 * before it — the request, the supplier's answer — is a conversation. The order
 * this creates carries the payment, the commission base and the settlement, and
 * it is created only after a supplier has committed to specific quantities at
 * specific prices. That inversion is the whole point of the architecture: the old
 * flow took the money first and let the supplier reduce the order afterwards, so
 * the order a restaurant paid for was not the order it received.
 *
 * <p><b>The order arrives already accepted.</b> Its lines carry an
 * {@code acceptedQuantity} equal to what is being ordered, and it is released to
 * {@code CONFIRMED} rather than {@code PENDING_ACCEPTANCE} — there is nothing left
 * to accept, and no acceptance countdown, because the supplier already answered.
 *
 * <p><b>A separate bean</b> from {@link IntentOrderService} for the proxy reason
 * documented on {@code ProcurementSubmitter}: a {@code @Transactional} method
 * called through {@code this}, including from an idempotency lambda, never reaches
 * the proxy and runs with no transaction at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentOrderCreator {

    private final IntentRepository intents;
    private final IntentItemRepository intentItems;
    private final IntentAcceptanceRepository acceptances;
    private final IntentAcceptanceItemRepository acceptanceItems;
    private final IntentOrderLinkRepository links;
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderItemRepository supplierOrderItems;
    private final OrderNumberGenerator orderNumbers;
    private final OrderFunding funding;
    private final OrderReleaseService orderRelease;
    private final SkuDirectory skuDirectory;
    private final ProcurementDirectory stores;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    /** What a Costonomy delivery costs, and what the store is willing to carry. */
    private final DeliveryFeeQuoteService deliveryQuotes;
    private final DeliveryDirectory deliveryPolicies;

    /** What the caller asked to order, resolved against what was offered. */
    private record Plan(
            Intent intent,
            IntentAcceptance acceptance,
            List<PlannedLine> lines,
            BigDecimal subtotal,
            BigDecimal gst,
            BigDecimal total,
            List<IntentDtos.Blocker> blockers) {

        boolean creatable() {
            return blockers.isEmpty() && total.signum() > 0;
        }
    }

    private record PlannedLine(
            IntentItem item,
            IntentAcceptanceItem offer,
            BigDecimal quantity,
            BigDecimal lineValue,
            BigDecimal lineGst,
            BigDecimal lineTotal) {
    }

    // ── Preview ──────────────────────────────────────────────────────────

    /**
     * What our delivery would cost for this request.
     *
     * <p>Quoted against the accepted lines' weight and the real distance between
     * the two addresses, both resolved here. Stored, so that creating the order
     * spends this figure rather than a freshly computed one — the restaurant is
     * charged the number they were shown.
     */
    @Transactional
    public IntentDtos.DeliveryQuoteResponse deliveryQuote(Long actorId, Long intentId) {
        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, intent.getOutletId(), "Intent");

        // The goods' value, for a provider that prices insurance on it. The
        // delivery fee itself is not in this figure -- that is what is being asked.
        var acceptance = acceptances.findByIntentId(intentId).orElse(null);
        BigDecimal orderValue = acceptance == null || acceptance.getOfferedTotal() == null
                ? BigDecimal.ZERO : acceptance.getOfferedTotal();

        var fee = deliveryQuotes.quote(intentId, intent.getOutletId(),
                intent.getSupplierStoreId(), orderValue);

        return new IntentDtos.DeliveryQuoteResponse(fee.quoteReference(), fee.amount(),
                fee.currency(), fee.etaMinutes(), fee.distanceKm(), fee.expiresAt());
    }


    @Transactional(readOnly = true)
    public IntentDtos.OrderPreviewResponse preview(
            Long actorId, Long intentId, IntentDtos.CreateOrderRequest request) {

        var plan = plan(actorId, intentId, request);
        var labels = skuDirectory.describe(plan.lines().stream()
                .map(line -> line.item().getSupplierSkuId()).toList());

        var lines = plan.lines().stream()
                .map(line -> {
                    var label = labels.get(line.item().getSupplierSkuId());
                    return new IntentDtos.PreviewLine(
                            line.item().getId(),
                            line.item().getSupplierSkuId(),
                            label == null ? null : label.productName(),
                            label == null ? null : label.skuName(),
                            line.offer().getOfferedQuantity(),
                            line.quantity(),
                            line.item().getUnit(),
                            line.offer().getUnitPrice(),
                            line.offer().getGstRate(),
                            line.lineValue(),
                            line.lineGst(),
                            line.lineTotal());
                })
                .toList();

        return new IntentDtos.OrderPreviewResponse(
                intentId,
                plan.creatable(),
                plan.intent().getOrderCreationDeadline(),
                Instant.now(),
                lines,
                plan.subtotal(),
                plan.gst(),
                plan.total(),
                plan.blockers());
    }

    // ── Create ───────────────────────────────────────────────────────────

    @Transactional
    public IntentDtos.CreateOrderResponse create(
            Long actorId, Long intentId, IntentDtos.CreateOrderRequest request) {

        // Already ordered. Return the order rather than failing — a retry whose
        // idempotency key was lost asks "did my order go through?", and that has a
        // true answer. uk_intent_order_link_intent is the backstop underneath,
        // because two requests may not carry the same key and the constraint does
        // not care.
        var existing = links.findByIntentId(intentId).orElse(null);
        if (existing != null) {
            var order = supplierOrders.findById(existing.getSupplierOrderId())
                    .orElseThrow(() -> new NotFoundException("SupplierOrder",
                            existing.getSupplierOrderId()));
            return response(intentId, order, List.of());
        }

        var plan = plan(actorId, intentId, request);

        if (!plan.blockers().isEmpty()) {
            var first = plan.blockers().get(0);
            throw new BusinessException(ErrorCode.valueOf(first.code()), first.message());
        }
        if (plan.total().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "There's nothing to order. Choose at least one item.");
        }

        var intent = plan.intent();
        String paymentMethod = request.paymentMethod() == null
                ? "PREPAID" : request.paymentMethod();
        if (!funding.supports(paymentMethod)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment method isn't available.");
        }

        var store = stores.stores(List.of(intent.getSupplierStoreId()))
                .get(intent.getSupplierStoreId());
        int slaSeconds = store == null || store.responseSlaSeconds() == null
                || store.responseSlaSeconds() <= 0 ? 60 : store.responseSlaSeconds();

        Instant now = Instant.now();

        // How the goods travel, and what that costs. D-091.
        //
        // Settled before the order exists because the fee is part of what is
        // charged: a payment intent raised for the goods alone would collect the
        // wrong amount, and adding the delivery afterwards would charge somebody
        // twice for one order.
        // Required here, unlike on the preview. Defaulting would pick how
        // somebody's goods travel on their behalf, and under two of the three
        // modes that is a charge they did not agree to.
        if (request.deliveryMode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose how this order should reach you.");
        }
        DeliveryMode mode = request.deliveryMode();
        BigDecimal deliveryFee = deliveryFeeFor(mode, intent, request, now);

        var order = new SupplierOrder();
        // No procurement: the intent was the basket. The link to where this came
        // from is intent_order_link, written below.
        order.setProcurementId(null);
        order.setSupplierStoreId(intent.getSupplierStoreId());
        order.setOutletId(intent.getOutletId());
        order.setOrderNumber(orderNumbers.next());
        // DRAFT until funded — guardrail 16 is unchanged by any of this. What
        // changes is where it goes next: CONFIRMED, not PENDING_ACCEPTANCE.
        order.setStatus(SupplierOrderStatus.DRAFT);
        order.setResponseSlaSeconds(slaSeconds);
        order.setAcceptanceDeadline(null);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentStatus("PENDING");
        order.setSubtotal(Pricing.money(plan.subtotal()));
        order.setGstAmount(Pricing.money(plan.gst()));
        order.setDeliveryMode(mode);
        order.setDeliveryFee(Pricing.money(deliveryFee));
        // The goods plus the carriage. What the restaurant pays is one figure, and
        // it is this one -- the fee cannot be collected later without charging
        // twice for a single order.
        order.setTotalAmount(Pricing.money(plan.total().add(deliveryFee)));
        // Equal to the total, not zero: the supplier already accepted these
        // quantities, so the accepted value is known at creation. In the old flow
        // this stayed zero until a supplier answered.
        order.setAcceptedAmount(Pricing.money(plan.total().add(deliveryFee)));
        supplierOrders.saveAndFlush(order);

        if (mode == DeliveryMode.COSTONOMY_DELIVERY) {
            // Marks the quote spent, now that there is an order to attach it to.
            deliveryQuotes.consume(request.deliveryQuoteReference(), intent.getId(),
                    intent.getOutletId(), intent.getSupplierStoreId(), order.getId(), now);
        }

        for (PlannedLine line : plan.lines()) {
            if (line.quantity().signum() == 0) {
                continue;
            }
            var item = new SupplierOrderItem();
            item.setSupplierOrderId(order.getId());
            item.setProcurementItemId(null);
            item.setRequirementItemId(null);
            item.setCanonicalProductId(line.item().getCanonicalProductId());
            item.setSupplierSkuId(line.item().getSupplierSkuId());
            item.setRequestedQuantity(line.quantity());
            // Requested and accepted are the same figure here, and that is the
            // architecture rather than a shortcut: the restaurant is ordering
            // from what the supplier already agreed to supply, so there is no
            // later moment at which these two could diverge.
            item.setAcceptedQuantity(line.quantity());
            item.setUnit(line.item().getUnit());
            item.setUnitPriceSnapshot(line.offer().getUnitPrice());
            item.setGstRateSnapshot(line.offer().getGstRate());
            item.setLineItemValue(line.lineValue());
            item.setLineGst(line.lineGst());
            item.setLineTotal(line.lineTotal());
            item.setStatus(OrderItemStatus.ACCEPTED);
            supplierOrderItems.save(item);
        }

        var link = new IntentOrderLink();
        link.setIntentId(intent.getId());
        link.setIntentAcceptanceId(plan.acceptance().getId());
        link.setSupplierOrderId(order.getId());
        links.saveAndFlush(link);

        var fundingIntents = funding.arrangeFunding(List.of(order));
        // Release whatever is already secured, rather than branching on the
        // payment method. For prepaid nothing is secured yet, so this is a no-op
        // and the order waits for the confirm call, the webhook or the
        // reconciliation sweep. For credit it is secured here, so it goes out now.
        //
        // It lands in CONFIRMED rather than PENDING_ACCEPTANCE, and OrderReleaseService
        // works that out from the order's null procurement rather than being told
        // — the later funding routes have no idea an intent was involved.
        orderRelease.releaseIfFunded(order.getId());

        var from = intent.getStatus();
        intent.setStatus(IntentStatus.ORDERED);
        intents.save(intent);

        auditService.record(actorId, null, "INTENT_ORDERED", "INTENT", intent.getId(),
                from.name(), IntentStatus.ORDERED.name(), order.getOrderNumber(), "API");

        outbox.publish("IntentOrdered", "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId(),
                        "supplierOrderId", order.getId(),
                        "orderNumber", order.getOrderNumber(),
                        "totalAmount", order.getTotalAmount().toPlainString()),
                actorId, now);

        // Re-read: releaseIfFunded may have moved the status and the payment state
        // in its own write, and the instance here would otherwise report the
        // figures from before it ran.
        var saved = supplierOrders.findById(order.getId()).orElse(order);
        return response(intent.getId(), saved, fundingIntents);
    }

    // ── Planning ─────────────────────────────────────────────────────────

    /**
     * Work out what would be ordered, and what stops it.
     *
     * <p>Shared by the preview and the create so the figure a restaurant agrees to
     * and the figure it is charged come from one calculation. Two implementations
     * would eventually differ, and the restaurant would be the one to notice.
     *
     * <p>Clamps nothing silently. A quantity above what was offered is an error,
     * not a value to trim: the client showed somebody a number, and quietly
     * ordering less than they asked for is how a restaurant ends up short without
     * being told.
     */
    private Plan plan(Long actorId, Long intentId, IntentDtos.CreateOrderRequest request) {
        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, intent.getOutletId(), "Intent");

        var acceptance = acceptances.findByIntentId(intentId)
                .filter(found -> found.getStatus() == IntentAcceptanceStatus.SUBMITTED)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This supplier hasn't answered yet."));

        var blockers = new ArrayList<IntentDtos.Blocker>();

        if (intent.getStatus() != IntentStatus.RESPONSES_RECEIVED) {
            blockers.add(new IntentDtos.Blocker(null, null,
                    ErrorCode.INVALID_STATE_TRANSITION.name(),
                    intent.getStatus() == IntentStatus.ORDERED
                            ? "An order has already been created from this request."
                            : "This request is no longer open for ordering."));
        } else if (!intent.withinOrderWindow(Instant.now())) {
            // The window, not the acceptance's expiry — they are the same instant
            // by construction, and the intent is the row the deadline belongs to.
            blockers.add(new IntentDtos.Blocker(null, null,
                    ErrorCode.SUPPLIER_ORDER_EXPIRED.name(),
                    "The time to order from this answer has passed. "
                            + "Send the request again to get a fresh answer."));
        }

        var store = stores.stores(List.of(intent.getSupplierStoreId()))
                .get(intent.getSupplierStoreId());
        if (store == null || !store.tradeable()) {
            blockers.add(new IntentDtos.Blocker(null, null, ErrorCode.SUPPLIER_OFFLINE.name(),
                    store != null && !store.openNow()
                            ? "%s is closed. They open at %s.".formatted(
                                    store.supplierName(), store.opensAt())
                            : "This supplier isn't taking orders right now."));
        }

        var items = intentItems.findByIntentIdOrderByIdAsc(intentId).stream()
                .collect(Collectors.toMap(IntentItem::getId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        var offersByItem = acceptanceItems.findByIntentAcceptanceId(acceptance.getId()).stream()
                .collect(Collectors.toMap(IntentAcceptanceItem::getIntentItemId,
                        Function.identity(), (first, second) -> first));

        // Absent means "everything offered", which is the common case and saves the
        // client echoing back figures it did not choose.
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        if (request != null && request.lines() != null && !request.lines().isEmpty()) {
            for (IntentDtos.OrderLine line : request.lines()) {
                if (!items.containsKey(line.intentItemId())) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Line %d isn't part of this request.".formatted(line.intentItemId()));
                }
                wanted.put(line.intentItemId(), line.quantity());
            }
        }

        var lines = new ArrayList<PlannedLine>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;

        for (IntentItem item : items.values()) {
            var offer = offersByItem.get(item.getId());
            if (offer == null) {
                // Every line is answered or the response is refused, so this means
                // the data is inconsistent rather than the supplier being silent.
                blockers.add(new IntentDtos.Blocker(item.getId(), null,
                        ErrorCode.INTERNAL_ERROR.name(),
                        "This line has no answer against it."));
                continue;
            }

            BigDecimal quantity = wanted.getOrDefault(item.getId(), offer.getOfferedQuantity());

            if (quantity.signum() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A quantity cannot be negative.");
            }
            if (quantity.compareTo(offer.getOfferedQuantity()) > 0) {
                throw new BusinessException(ErrorCode.ACCEPTED_QUANTITY_EXCEEDS_REQUESTED,
                        "The supplier offered %s %s. You can't order more than that."
                                .formatted(offer.getOfferedQuantity().toPlainString(),
                                        item.getUnit()));
            }

            var lineValue = Pricing.lineItemValue(offer.getUnitPrice(), quantity);
            var lineGst = Pricing.lineGst(lineValue, offer.getGstRate());
            var lineTotal = Pricing.lineTotal(lineValue, lineGst);

            lines.add(new PlannedLine(item, offer, quantity, lineValue, lineGst, lineTotal));
            subtotal = subtotal.add(lineValue);
            gst = gst.add(lineGst);
        }

        return new Plan(intent, acceptance, lines,
                Pricing.money(subtotal), Pricing.money(gst),
                Pricing.money(subtotal.add(gst)), blockers);
    }

    /**
     * What the chosen mode costs, and whether the store can serve it at all.
     *
     * <p>Three sources, one per mode, and none of them the client's:
     *
     * <ul>
     *   <li><b>Pickup</b> is free — the restaurant fetches it.</li>
     *   <li><b>Supplier delivery</b> is the store's own configured fee, subject to
     *       their minimum order value. A supplier who quotes their own delivery is
     *       quoting their own delivery; it never becomes the platform's figure.</li>
     *   <li><b>Costonomy delivery</b> is a stored quote, spent by reference.
     *       Recomputing it here would let the price move between the screen that
     *       showed it and the charge that collected it.</li>
     * </ul>
     */
    private BigDecimal deliveryFeeFor(DeliveryMode mode, com.costonomy.mp.intent.domain.Intent intent,
                                      IntentDtos.CreateOrderRequest request, Instant now) {

        var policy = deliveryPolicies.deliveryPolicy(intent.getSupplierStoreId());

        return switch (mode) {
            case PICKUP -> BigDecimal.ZERO;

            case SUPPLIER_DELIVERY -> {
                if (!policy.ownDeliveryEnabled()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "This supplier doesn't deliver. Choose pickup or our delivery.");
                }
                yield policy.ownDeliveryFee() == null ? BigDecimal.ZERO : policy.ownDeliveryFee();
            }

            case COSTONOMY_DELIVERY -> {
                if (!policy.costonomyDeliveryEnabled()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "We can't deliver from this supplier. Choose pickup, or ask "
                                    + "them to deliver.");
                }
                if (request.deliveryQuoteReference() == null) {
                    // Not defaulted to zero and not quoted on the fly: a delivery
                    // whose price nobody saw is a charge nobody agreed to.
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Check the delivery fee before ordering.");
                }
                // Read, not spent. The order does not exist yet, and a quote
                // spent here would be found already spent when the order it
                // paid for came to claim it.
                yield deliveryQuotes.priceFor(request.deliveryQuoteReference(), intent.getId(),
                        intent.getOutletId(), intent.getSupplierStoreId(), now);
            }
        };
    }

    private IntentDtos.CreateOrderResponse response(
            Long intentId, SupplierOrder order,
            List<com.costonomy.mp.procurement.service.OrderFundingPort.FundingIntent> fundingIntents) {

        var payment = fundingIntents.stream()
                .filter(intent -> intent.supplierOrderId().equals(order.getId()))
                .findFirst()
                .map(intent -> new IntentDtos.PaymentIntent(
                        intent.supplierOrderId(), intent.paymentId(), intent.provider(),
                        intent.providerOrderId(), intent.amount(), intent.currency(),
                        intent.publicKey()))
                .orElse(null);

        return new IntentDtos.CreateOrderResponse(
                intentId, order.getId(), order.getOrderNumber(), order.getTotalAmount(),
                order.getPaymentMethod(), order.getPaymentStatus(), payment);
    }
}
