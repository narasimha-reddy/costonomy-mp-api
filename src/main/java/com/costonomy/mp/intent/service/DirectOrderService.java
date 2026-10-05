package com.costonomy.mp.intent.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.delivery.service.DeliveryDirectory;
import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentAcceptance;
import com.costonomy.mp.intent.domain.IntentAcceptanceItem;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentItem;
import com.costonomy.mp.intent.domain.IntentPolicy;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentAcceptanceItemRepository;
import com.costonomy.mp.intent.repository.IntentAcceptanceRepository;
import com.costonomy.mp.intent.repository.IntentItemRepository;
import com.costonomy.mp.intent.repository.IntentRepository;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.service.ProcurementDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ordering from a store that keeps stock, without asking it first. D-094.
 *
 * <p>The request round trip exists to answer one question: does this supplier
 * actually have the goods. A meat or vegetable wholesaler with a short list and
 * real inventory has already answered it by listing the line, and making a
 * kitchen wait out a response window to hear so is a delay that buys nothing.
 *
 * <p><b>It does not fork the order flow.</b> A direct order is the same order:
 * the same delivery choice, the same payment, the same
 * {@code intent_order_link}, the same commission base. What this does is give
 * the order the thing it is built on — a supplier's commitment to a quantity at
 * a price — from the store's own standing offer instead of from a reply typed
 * into a phone. The acceptance it writes is real and says who made it: nobody,
 * because the store's setting made it.
 *
 * <p><b>The price is the live offer, and a change is still shown.</b> A draft
 * carries the price from when the line was added; ordering directly re-reads the
 * offer and, if it moved, refuses and reports old and new for confirmation —
 * §23A.16 applies to a direct order exactly as it does to a sent request, and
 * this is the only place it could be enforced.
 *
 * <p><b>Stock is checked even here.</b> A supplier who turns this on is telling
 * us they hold the lines, but the offer still carries what they declared, and an
 * order that exceeds it would land on a supplier who cannot fill it. So a short
 * line refuses by name, with the figure they declared, and nothing is charged.
 */
@Service
@RequiredArgsConstructor
public class DirectOrderService {

    private final IntentRepository intents;
    private final IntentItemRepository intentItems;
    private final IntentAcceptanceRepository acceptances;
    private final IntentAcceptanceItemRepository acceptanceItems;
    private final SupplierOfferRepository offers;
    private final ProcurementDirectory stores;
    private final DeliveryDirectory deliveryPolicies;
    private final SkuDirectory skuDirectory;
    private final IntentPolicy policy;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final IntentMapper mapper;

    /**
     * Turn a draft into a request that is already answered, ready to order from.
     *
     * <p>Returns the intent when it worked and {@code held} when a price moved,
     * which is the same shape the basket already returns and the same sheet the
     * cart already shows. Re-calling with {@code acceptPriceChanges} agrees to
     * the new price and proceeds.
     */
    @Transactional
    public IntentDtos.DirectOrderResponse prepare(
            Long actorId, Long intentId, boolean acceptPriceChanges) {

        // Locked first (D-137): an add or removal in flight finishes before this reads the lines, and the status
        // check below sees a send that beat us.
        var intent = intents.lockById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, intent.getOutletId(), "Intent");

        if (intent.getStatus() != IntentStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    intent.getStatus() == IntentStatus.ORDERED
                            ? "An order has already been created from this."
                            : "This request has already been sent.");
        }

        var lines = intentItems.lockByIntentId(intentId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Add something before ordering.");
        }

        var store = stores.stores(List.of(intent.getSupplierStoreId()))
                .get(intent.getSupplierStoreId());
        if (store == null || !store.tradeable()) {
            throw new BusinessException(ErrorCode.SUPPLIER_OFFLINE,
                    store != null && !store.openNow()
                            ? "%s is closed. They open at %s.".formatted(
                                    store.supplierName(), store.opensAt())
                            : "This supplier isn't taking orders right now.");
        }
        // Re-checked here and not only in the app: the setting is the supplier's
        // consent to be ordered from without being asked, and a client that has
        // not refreshed since they turned it off must not be able to spend it.
        if (!store.directOrdersEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "%s asks for a request first, so they can confirm what they have."
                            .formatted(store.storeName()));
        }

        var live = liveOffers(lines);

        var changes = repricedLines(lines, live);
        if (!changes.isEmpty() && !acceptPriceChanges) {
            return new IntentDtos.DirectOrderResponse(null, List.of(
                    new IntentDtos.HeldRequest(intent.getId(), intent.getReference(),
                            store.storeName(), changes)));
        }

        requireInStock(lines, live);

        Instant now = Instant.now();
        int windowSeconds = policy.orderCreationWindowSeconds();
        Instant deadline = policy.orderCreationDeadline(now, windowSeconds);

        var acceptance = new IntentAcceptance();
        acceptance.setIntentId(intent.getId());
        acceptance.setSupplierStoreId(intent.getSupplierStoreId());
        // Nobody typed this. Leaving it null is the honest record of an
        // acceptance that came from a standing setting rather than a person, and
        // it is what tells a later reader which kind of answer this was.
        acceptance.setRespondedBy(null);
        acceptance.setStatus(IntentAcceptanceStatus.SUBMITTED);
        acceptance.setSubmittedAt(now);
        acceptance.setExpiresAt(deadline);
        if ("PICKUP".equals(intent.getDeliveryPreference())) {
            // The restaurant will collect it (D-143): no delivery is offered.
            acceptance.setDeliveryModes("PICKUP");
            acceptance.setDeliveryOffer("NONE");
        } else {
            acceptance.setDeliveryModes(modesFor(intent.getSupplierStoreId()));
        }
        // A courier's fee is quoted when asked for (Doc 06 §4). The store's own fee is known, so a buyer is shown it
        // rather than "Free" (D-141).
        var ownPolicy = deliveryPolicies.deliveryPolicy(intent.getSupplierStoreId());
        acceptance.setDeliveryFee("PICKUP".equals(intent.getDeliveryPreference()) || !ownPolicy.ownDeliveryEnabled()
                ? null : ownPolicy.ownDeliveryFee());
        acceptances.saveAndFlush(acceptance);

        BigDecimal value = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;

        for (IntentItem line : lines) {
            SupplierOffer offer = live.get(line.getSupplierSkuId());
            // Locked: the line now carries the price the order will be built on,
            // which is the price that was just shown and agreed to.
            line.setSupplierOfferId(offer.getId());
            line.setUnitPriceSnapshot(Pricing.money(offer.getSellingPrice()));
            line.setGstRateSnapshot(offer.getGstRate());
            intentItems.save(line);

            var lineValue = Pricing.lineItemValue(
                    Pricing.money(offer.getSellingPrice()), line.getRequestedQuantity());
            var lineGst = Pricing.lineGst(lineValue, offer.getGstRate());

            var item = new IntentAcceptanceItem();
            item.setIntentAcceptanceId(acceptance.getId());
            item.setIntentItemId(line.getId());
            // Everything asked for, because there is nobody here to offer less.
            item.setOfferedQuantity(line.getRequestedQuantity());
            item.setUnitPrice(Pricing.money(offer.getSellingPrice()));
            item.setGstRate(offer.getGstRate());
            item.setLineValue(lineValue);
            item.setLineGst(lineGst);
            item.setLineTotal(Pricing.lineTotal(lineValue, lineGst));
            acceptanceItems.save(item);

            value = value.add(lineValue);
            gst = gst.add(lineGst);
        }

        acceptance.setOfferedValue(Pricing.money(value));
        acceptance.setOfferedGst(Pricing.money(gst));
        acceptance.setOfferedTotal(Pricing.money(value.add(gst)));
        acceptances.save(acceptance);

        // Both transitions, in order, so the audit trail reads the way every
        // other request does rather than skipping a state nothing else skips.
        transition(intent, IntentStatus.OPEN, actorId);
        intent.setSentAt(now);
        intent.setResponseWindowSeconds(0);
        intent.setResponseDeadline(now);
        transition(intent, IntentStatus.RESPONSES_RECEIVED, actorId);
        intent.setAcceptedAt(now);
        intent.setAcceptedOrderCreationWindowSeconds(windowSeconds);
        intent.setOrderCreationDeadline(deadline);
        intents.save(intent);

        outbox.publish("IntentDirectOrderPrepared", "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId(),
                        "itemCount", lines.size(),
                        "orderCreationDeadline", deadline.toString()),
                actorId, now);

        return new IntentDtos.DirectOrderResponse(
                mapper.toResponse(intents.save(intent)), List.of());
    }

    /**
     * The live offer behind every line, or a refusal naming the one without.
     *
     * <p>A delisted line is not a price change and must not be reported as one:
     * there is no new price to show, and "the price went up" would be a lie
     * about what happened.
     */
    private Map<Long, SupplierOffer> liveOffers(List<IntentItem> lines) {
        var labels = skuDirectory.describe(lines.stream()
                .map(IntentItem::getSupplierSkuId).toList());
        var found = new java.util.HashMap<Long, SupplierOffer>();

        for (IntentItem line : lines) {
            var offer = offers.findBySupplierSkuIdAndStatus(line.getSupplierSkuId(), "ACTIVE")
                    .orElse(null);
            if (offer == null) {
                var label = labels.get(line.getSupplierSkuId());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "%s is no longer listed by this supplier. Remove it to order the rest."
                                .formatted(label == null ? "An item" : label.productName()));
            }
            found.put(line.getSupplierSkuId(), offer);
        }
        return found;
    }

    /**
     * Refuse a line the supplier cannot fill, and say which.
     *
     * <p>Refusing rather than quietly reducing: the quantity is the restaurant's
     * decision, and an order for four when they asked for six is a different
     * order. They may want to take four, or to buy the six elsewhere, and only
     * they can say which.
     */
    private void requireInStock(List<IntentItem> lines, Map<Long, SupplierOffer> live) {
        var labels = skuDirectory.describe(lines.stream()
                .map(IntentItem::getSupplierSkuId).toList());

        for (IntentItem line : lines) {
            var offer = live.get(line.getSupplierSkuId());
            var label = labels.get(line.getSupplierSkuId());
            String name = label == null ? "An item" : label.productName();

            if (!offer.isPurchasable()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "%s is out of stock with this supplier right now.".formatted(name));
            }
            // Null means the supplier does not track a figure, which is not the
            // same as zero and must not be read as it.
            if (offer.getAvailableQuantity() != null
                    && offer.getAvailableQuantity().compareTo(line.getRequestedQuantity()) < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "%s — this supplier has %s %s, and you asked for %s.".formatted(
                                name,
                                offer.getAvailableQuantity().stripTrailingZeros().toPlainString(),
                                line.getUnit(),
                                line.getRequestedQuantity().stripTrailingZeros().toPlainString()));
            }
        }
    }

    /** Lines whose supplier has repriced since they were added. */
    private List<IntentDtos.PriceChange> repricedLines(
            List<IntentItem> lines, Map<Long, SupplierOffer> live) {

        var labels = skuDirectory.describe(lines.stream()
                .map(IntentItem::getSupplierSkuId).toList());
        var changes = new ArrayList<IntentDtos.PriceChange>();

        for (IntentItem line : lines) {
            var offer = live.get(line.getSupplierSkuId());
            if (line.getUnitPriceSnapshot() == null) {
                continue;
            }
            if (!Pricing.differs(offer.getSellingPrice(), line.getUnitPriceSnapshot())
                    && !Pricing.differs(offer.getGstRate(), line.getGstRateSnapshot())) {
                continue;
            }
            var label = labels.get(line.getSupplierSkuId());
            changes.add(new IntentDtos.PriceChange(
                    line.getId(),
                    label == null ? null : label.productName(),
                    line.getUnitPriceSnapshot(),
                    Pricing.money(offer.getSellingPrice()),
                    lineTotal(line.getUnitPriceSnapshot(), line.getGstRateSnapshot(),
                            line.getRequestedQuantity()),
                    lineTotal(Pricing.money(offer.getSellingPrice()), offer.getGstRate(),
                            line.getRequestedQuantity())));
        }
        return changes;
    }

    /**
     * Which ways the goods can travel, from the store's own delivery policy.
     *
     * <p>The same source the order creation will check against, so the screen
     * offering the choice and the server accepting it cannot disagree. Pickup is
     * always available: it needs nothing of the supplier but the goods.
     */
    private String modesFor(Long storeId) {
        var deliveryPolicy = deliveryPolicies.deliveryPolicy(storeId);
        var modes = new ArrayList<String>();
        modes.add("PICKUP");
        if (deliveryPolicy.ownDeliveryEnabled()) {
            modes.add("SUPPLIER_DELIVERY");
        }
        if (deliveryPolicy.costonomyDeliveryEnabled()) {
            modes.add("COSTONOMY_DELIVERY");
        }
        return String.join(",", modes);
    }

    private BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal gstRate, BigDecimal quantity) {
        if (unitPrice == null || gstRate == null) {
            return null;
        }
        var value = Pricing.lineItemValue(unitPrice, quantity);
        return Pricing.lineTotal(value, Pricing.lineGst(value, gstRate));
    }

    private void transition(Intent intent, IntentStatus target, Long actorId) {
        var from = intent.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A request at %s cannot move to %s.".formatted(from, target));
        }
        intent.setStatus(target);
        auditService.record(actorId, null, "INTENT_" + target.name() + "_DIRECT", "INTENT",
                intent.getId(), from.name(), target.name(), intent.getReference(), "API");
    }
}
