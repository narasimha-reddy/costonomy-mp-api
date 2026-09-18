package com.costonomy.mp.intent.service;

import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentAcceptance;
import com.costonomy.mp.intent.domain.IntentAcceptanceItem;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.intent.domain.IntentItem;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.domain.IntentOrderLink;
import com.costonomy.mp.intent.repository.IntentAcceptanceItemRepository;
import com.costonomy.mp.intent.repository.IntentAcceptanceRepository;
import com.costonomy.mp.intent.repository.IntentItemRepository;
import com.costonomy.mp.intent.repository.IntentOrderLinkRepository;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.service.ProcurementDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Intents into responses, in a fixed number of queries however many there are.
 *
 * <p>Written batch-first — {@link #toResponses} is the real method and
 * {@link #toResponse} delegates to it — because both list screens are lists. The
 * supplier's request carousel and the restaurant's filtered list would otherwise
 * issue five queries per row, and a supplier with ten open requests would pay
 * fifty queries to draw one screen.
 */
@Component
@RequiredArgsConstructor
public class IntentMapper {

    private final IntentItemRepository items;
    private final IntentAcceptanceRepository acceptances;
    private final IntentAcceptanceItemRepository acceptanceItems;
    private final IntentOrderLinkRepository links;
    private final SkuDirectory skuDirectory;
    private final ProcurementDirectory stores;
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOfferRepository offers;

    public IntentDtos.IntentResponse toResponse(Intent intent) {
        return toResponses(List.of(intent)).get(0);
    }

    public List<IntentDtos.IntentResponse> toResponses(List<Intent> intents) {
        if (intents.isEmpty()) {
            return List.of();
        }
        Instant serverTime = Instant.now();
        var intentIds = intents.stream().map(Intent::getId).toList();

        var itemsByIntent = items.findByIntentIdIn(intentIds).stream()
                .collect(Collectors.groupingBy(IntentItem::getIntentId));

        var acceptanceByIntent = acceptances.findByIntentIdIn(intentIds).stream()
                .collect(Collectors.toMap(IntentAcceptance::getIntentId, Function.identity()));

        // Only submitted answers carry offered quantities onto the lines. A
        // supplier's half-typed draft is theirs alone; projecting it would show
        // the restaurant an offer that had not been made.
        var submittedAcceptanceIds = acceptanceByIntent.values().stream()
                .filter(acceptance -> acceptance.getStatus() == IntentAcceptanceStatus.SUBMITTED)
                .map(IntentAcceptance::getId)
                .toList();

        Map<Long, IntentAcceptanceItem> answerByItem = submittedAcceptanceIds.isEmpty()
                ? Map.of()
                : acceptanceItems.findByIntentAcceptanceIdIn(submittedAcceptanceIds).stream()
                        .collect(Collectors.toMap(
                                IntentAcceptanceItem::getIntentItemId, Function.identity(),
                                (first, second) -> first));

        var linkByIntent = links.findByIntentIdIn(intentIds).stream()
                .collect(Collectors.toMap(IntentOrderLink::getIntentId, Function.identity()));

        var orderNumbers = orderNumbers(linkByIntent.values());

        var labels = skuDirectory.describe(itemsByIntent.values().stream()
                .flatMap(List::stream).map(IntentItem::getSupplierSkuId).distinct().toList());

        var storeInfo = stores.stores(intents.stream()
                .map(Intent::getSupplierStoreId).distinct().toList());

        // Live offers, for drafts only. A draft tracks the current price so the
        // basket shows something real and can spot a reprice; once sent, the
        // line's own snapshot is the price and the catalogue is irrelevant to it.
        var draftIds = intents.stream()
                .filter(intent -> intent.getStatus() == IntentStatus.DRAFT)
                .map(Intent::getId)
                .collect(Collectors.toSet());
        var draftSkuIds = itemsByIntent.entrySet().stream()
                .filter(entry -> draftIds.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .map(IntentItem::getSupplierSkuId)
                .distinct()
                .toList();
        Map<Long, SupplierOffer> liveOffers = draftSkuIds.isEmpty()
                ? Map.of()
                : offers.findBySupplierSkuIdInAndStatus(draftSkuIds, "ACTIVE").stream()
                        .filter(SupplierOffer::isPurchasable)
                        .collect(Collectors.toMap(SupplierOffer::getSupplierSkuId,
                                Function.identity(), (first, next) -> first));

        List<IntentDtos.IntentResponse> responses = new ArrayList<>(intents.size());
        for (Intent intent : intents) {
            var store = storeInfo.get(intent.getSupplierStoreId());
            var acceptance = acceptanceByIntent.get(intent.getId());
            var link = linkByIntent.get(intent.getId());

            var lines = new ArrayList<IntentDtos.IntentItemResponse>();
            var lineFulfilments = new ArrayList<IntentFulfilment>();
            boolean draft = intent.getStatus() == IntentStatus.DRAFT;
            BigDecimal agreedValue = BigDecimal.ZERO;
            BigDecimal agreedGst = BigDecimal.ZERO;
            boolean pricedComplete = true;
            boolean anyPriceChanged = false;

            for (IntentItem item : itemsByIntent.getOrDefault(intent.getId(), List.of())) {
                var label = labels.get(item.getSupplierSkuId());
                var answer = answerByItem.get(item.getId());

                // A draft asks at today's price; a sent request asks at the price
                // locked when it went out.
                var offer = draft ? liveOffers.get(item.getSupplierSkuId()) : null;
                BigDecimal askPrice = draft
                        ? (offer == null ? null : Pricing.money(offer.getSellingPrice()))
                        : item.getUnitPriceSnapshot();
                BigDecimal askGstRate = draft
                        ? (offer == null ? null : offer.getGstRate())
                        : item.getGstRateSnapshot();

                BigDecimal lineValue = null;
                BigDecimal lineGstAmount = null;
                BigDecimal lineTotalAmount = null;
                boolean changed = false;
                BigDecimal previousUnitPrice = null;

                if (askPrice != null && askGstRate != null) {
                    lineValue = Pricing.lineItemValue(askPrice, item.getRequestedQuantity());
                    lineGstAmount = Pricing.lineGst(lineValue, askGstRate);
                    lineTotalAmount = Pricing.lineTotal(lineValue, lineGstAmount);
                    agreedValue = agreedValue.add(lineValue);
                    agreedGst = agreedGst.add(lineGstAmount);

                    // Only meaningful on a draft: on a sent request the snapshot
                    // *is* the price, so it cannot differ from itself.
                    if (draft && item.getUnitPriceSnapshot() != null
                            && Pricing.differs(askPrice, item.getUnitPriceSnapshot())) {
                        changed = true;
                        anyPriceChanged = true;
                        previousUnitPrice = item.getUnitPriceSnapshot();
                    }
                } else {
                    // One unpriceable line makes the whole total short of the
                    // truth, and a total that is quietly missing an item is worse
                    // than one the screen admits is incomplete.
                    pricedComplete = false;
                }
                var fulfilment = IntentFulfilment.of(item.getRequestedQuantity(),
                        answer == null ? null : answer.getOfferedQuantity());
                lineFulfilments.add(fulfilment);

                lines.add(new IntentDtos.IntentItemResponse(
                        item.getId(),
                        item.getSupplierSkuId(),
                        item.getCanonicalProductId(),
                        label,
                        item.getRequestedQuantity(),
                        item.getUnit(),
                        item.getNotes(),
                        item.getStatus(),
                        fulfilment,
                        answer == null ? null : answer.getOfferedQuantity(),
                        answer == null ? null : answer.getAvailability(),
                        answer == null ? null : answer.getUnitPrice(),
                        answer == null ? null : answer.getLineValue(),
                        answer == null ? null : answer.getLineGst(),
                        answer == null ? null : answer.getLineTotal(),
                        answer == null ? null : answer.getGstRate(),
                        answer == null ? null : answer.getNotes(),
                        askPrice,
                        askGstRate,
                        lineValue,
                        lineGstAmount,
                        lineTotalAmount,
                        changed,
                        previousUnitPrice));
            }

            responses.add(new IntentDtos.IntentResponse(
                    intent.getId(),
                    intent.getReference(),
                    intent.getOutletId(),
                    intent.getSupplierStoreId(),
                    store == null ? null : store.storeName(),
                    store == null ? null : store.supplierName(),
                    intent.getStatus(),
                    IntentFulfilment.roll(lineFulfilments),
                    intent.getSource(),
                    intent.getClonedFromId(),
                    intent.getRequestedDeliveryTime(),
                    intent.getNotes(),
                    intent.getSentAt(),
                    intent.getResponseDeadline(),
                    intent.getResponseWindowSeconds(),
                    intent.getAcceptedAt(),
                    intent.getOrderCreationDeadline(),
                    intent.getAcceptedOrderCreationWindowSeconds(),
                    intent.getCancelledAt(),
                    intent.getExpiredAt(),
                    intent.getCreatedAt(),
                    serverTime,
                    intent.getStatus().isEditable(),
                    intent.withinOrderWindow(serverTime),
                    lines,
                    Pricing.money(agreedValue),
                    Pricing.money(agreedGst),
                    Pricing.money(agreedValue.add(agreedGst)),
                    pricedComplete,
                    anyPriceChanged,
                    toAcceptance(acceptance),
                    link == null ? null : link.getSupplierOrderId(),
                    link == null ? null : orderNumbers.get(link.getSupplierOrderId())));
        }
        return responses;
    }

    /**
     * The supplier's answer, or null before there is one.
     *
     * <p>A draft answer is reported to its own author — the supplier reopening the
     * response screen must find what they had typed — so the status rides along
     * and the restaurant's controller is what keeps a draft out of view.
     */
    private IntentDtos.AcceptanceResponse toAcceptance(IntentAcceptance acceptance) {
        if (acceptance == null) {
            return null;
        }
        return new IntentDtos.AcceptanceResponse(
                acceptance.getId(),
                acceptance.getStatus(),
                acceptance.getOfferedValue(),
                acceptance.getOfferedGst(),
                acceptance.getOfferedTotal(),
                acceptance.getDeliveryFee(),
                acceptance.getEtaMinutes(),
                acceptance.getDeliveryMode(),
                acceptance.getNotes(),
                acceptance.getSubmittedAt(),
                acceptance.getExpiresAt());
    }

    private Map<Long, String> orderNumbers(Collection<IntentOrderLink> orderLinks) {
        if (orderLinks.isEmpty()) {
            return Map.of();
        }
        var ids = orderLinks.stream().map(IntentOrderLink::getSupplierOrderId).distinct().toList();
        Map<Long, String> numbers = new HashMap<>();
        supplierOrders.findAllById(ids)
                .forEach(order -> numbers.put(order.getId(), order.getOrderNumber()));
        return numbers;
    }

    /**
     * Roll drafts up into a basket.
     *
     * <p>The basket total is summed here rather than on the client for the reason
     * {@code BasketResponse} gives: money arithmetic belongs on the server, and a
     * client-side sum of rounded per-supplier totals is exactly the kind that
     * disagrees with this one by a paisa.
     */
    public IntentDtos.BasketResponse toBasket(List<Intent> drafts) {
        var requests = toResponses(drafts);

        BigDecimal value = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;
        boolean complete = true;
        int itemCount = 0;

        boolean changed = false;

        for (var request : requests) {
            value = value.add(request.agreedValue() == null
                    ? BigDecimal.ZERO : request.agreedValue());
            gst = gst.add(request.agreedGst() == null
                    ? BigDecimal.ZERO : request.agreedGst());
            complete = complete && request.pricedComplete();
            changed = changed || request.priceChanged();
            itemCount += request.items().size();
        }

        return new IntentDtos.BasketResponse(
                requests, requests.size(), itemCount,
                Pricing.money(value), Pricing.money(gst),
                Pricing.money(value.add(gst)), complete, changed);
    }
}
