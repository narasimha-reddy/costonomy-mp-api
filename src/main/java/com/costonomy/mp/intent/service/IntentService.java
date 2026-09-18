package com.costonomy.mp.intent.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.intent.domain.IntentItem;
import com.costonomy.mp.intent.domain.IntentPolicy;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentItemRepository;
import com.costonomy.mp.intent.repository.IntentRepository;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import com.costonomy.mp.procurement.service.ProcurementDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The restaurant's side of a request: building it, sending it, cancelling it,
 * cloning it. §7, §8.
 *
 * <p><b>The request is the basket.</b> There is no separate cart any more. Adding
 * a pack finds or opens a {@code DRAFT} intent for that pack's store and puts the
 * line on it, so a restaurant shopping across three suppliers ends up with three
 * drafts and the cart screen is a card per supplier. That is what keeps the
 * intent → order boundary one-to-one without ever asking the restaurant to think
 * about it: the split happens while they shop, not at checkout.
 *
 * <p><b>Nothing here touches money.</b> No price is stored on a line, no total is
 * computed, no payment is arranged, no credit is reserved. A restaurant can send
 * twenty requests and owe nothing. Money starts in
 * {@link IntentOrderService} and only there.
 *
 * <p><b>A sent request is frozen.</b> {@code isEditable()} is true only for
 * {@code DRAFT}, and every mutator checks it. Once a supplier is pricing a
 * request, changing it underneath them would have them commit stock against a
 * list that no longer exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentService {

    private static final DateTimeFormatter REFERENCE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final IntentRepository intents;
    private final IntentItemRepository intentItems;
    private final SupplierSkuRepository skus;
    private final SupplierOfferRepository offers;
    private final IntentMapper mapper;
    private final IntentPolicy policy;
    private final ProcurementDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── Building ─────────────────────────────────────────────────────────

    /**
     * Put a pack on a request, creating the request if this is the first one.
     *
     * <p>The store is taken from the SKU rather than the caller. A client passing
     * both could pass a mismatched pair, and the resulting intent would name one
     * supplier while holding another's packs.
     */
    @Transactional
    public IntentDtos.IntentResponse addItem(
            Long actorId, Long outletId, IntentDtos.AddItemRequest request) {

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, outletId, "Outlet");

        var sku = skus.findById(request.supplierSkuId())
                .orElseThrow(() -> new NotFoundException("SupplierSku", request.supplierSkuId()));

        if (!"ACTIVE".equals(sku.getStatus())) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE,
                    "The supplier no longer lists this product.");
        }

        // A courtesy check, not the rule. Whether this can actually be bought is
        // settled when the supplier answers and again at order creation; between
        // now and then anything can change, which is why this is not the check
        // that matters.
        var offer = offers.findBySupplierSkuIdAndStatus(sku.getId(), "ACTIVE").orElse(null);
        if (offer == null || !offer.isPurchasable()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE,
                    "This product is out of stock.");
        }

        var intent = openDraft(actorId, outletId, sku.getSupplierStoreId());

        var existing = intentItems.findByIntentIdAndSupplierSkuId(intent.getId(), sku.getId());
        if (existing.isPresent()) {
            // Adding the same pack again increases the quantity. Two lines for one
            // SKU is two answers to one question, and the supplier would have to
            // guess which to price — which is why uk_intent_item_sku exists.
            var item = existing.get();
            item.setRequestedQuantity(item.getRequestedQuantity().add(request.quantity()));
            if (request.notes() != null) {
                item.setNotes(request.notes());
            }
            intentItems.save(item);
        } else {
            var item = new IntentItem();
            item.setIntentId(intent.getId());
            item.setSupplierSkuId(sku.getId());
            item.setCanonicalProductId(sku.getCanonicalProductId());
            item.setRequestedQuantity(request.quantity());
            item.setUnit(sku.getPackUnit());
            item.setNotes(request.notes());
            intentItems.save(item);
        }

        return mapper.toResponse(intent);
    }

    /**
     * Change a line's quantity, or remove it by setting zero.
     *
     * <p>Zero removes rather than being rejected: a stepper counted down to zero
     * means the person has finished with that line, and refusing would leave the
     * app holding a line they have visibly dismissed.
     */
    @Transactional
    public IntentDtos.IntentResponse updateItem(Long actorId, Long itemId, BigDecimal quantity) {
        var item = intentItems.findById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        var intent = loadForWrite(actorId, item.getIntentId(), Permissions.PROCUREMENT_CREATE);
        requireEditable(intent);

        if (quantity.signum() <= 0) {
            return removeLine(intent, item);
        }

        item.setRequestedQuantity(quantity);
        intentItems.save(item);
        return mapper.toResponse(intent);
    }

    @Transactional
    public IntentDtos.IntentResponse removeItem(Long actorId, Long itemId) {
        var item = intentItems.findById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        var intent = loadForWrite(actorId, item.getIntentId(), Permissions.PROCUREMENT_CREATE);
        requireEditable(intent);
        return removeLine(intent, item);
    }

    /**
     * Take a line off a draft.
     *
     * <p><b>Deleted, not flagged</b> — the one place in this system that hard-
     * deletes, and it is allowed because a draft line is not a transactional
     * record: no price, no order, no payment, nothing referencing it. A soft
     * delete would also break re-adding, because {@code uk_intent_item_sku} would
     * still see the removed row and the restaurant could never put that pack back.
     *
     * <p>Emptying a draft removes the draft too, so the basket does not keep a
     * card for a supplier the restaurant has cleared out.
     */
    private IntentDtos.IntentResponse removeLine(Intent intent, IntentItem item) {
        intentItems.delete(item);
        intentItems.flush();

        if (intentItems.countByIntentId(intent.getId()) == 0) {
            var emptied = mapper.toResponse(intent);
            intents.delete(intent);
            return emptied;
        }
        return mapper.toResponse(intent);
    }

    /**
     * Find or open this outlet's draft for one store.
     *
     * <p>Not exposed on its own: a draft with no lines is an artefact, and every
     * caller here creates one only because it is about to put something on it.
     */
    private Intent openDraft(Long actorId, Long outletId, Long supplierStoreId) {
        return intents
                .findByOutletIdAndSupplierStoreIdAndStatus(outletId, supplierStoreId,
                        IntentStatus.DRAFT)
                .orElseGet(() -> create(actorId, outletId, supplierStoreId, "MANUAL", null));
    }

    private Intent create(Long actorId, Long outletId, Long supplierStoreId,
                          String source, Long clonedFromId) {
        var intent = new Intent();
        intent.setOutletId(outletId);
        intent.setSupplierStoreId(supplierStoreId);
        intent.setCreatedBy(actorId);
        intent.setStatus(IntentStatus.DRAFT);
        intent.setSource(source);
        intent.setClonedFromId(clonedFromId);
        // Placeholder for one statement. The reference embeds the id, so it cannot
        // be known before the insert — and deriving it from the id is what makes it
        // collision-free without a second sequence table to keep in step.
        intent.setReference("PENDING");
        intents.saveAndFlush(intent);

        intent.setReference("RQ-%s-%06d".formatted(
                LocalDate.now(ZoneOffset.UTC).format(REFERENCE_DATE), intent.getId()));
        return intents.saveAndFlush(intent);
    }

    // ── Sending ──────────────────────────────────────────────────────────

    /**
     * Send a draft to its supplier.
     *
     * <p>This is the only transition the restaurant makes that another party can
     * see, and it still moves no money. The store is re-checked here because a
     * supplier who has closed or been suspended since the basket was built cannot
     * answer, and a request nobody can answer would sit there until it expired.
     */
    @Transactional
    public IntentDtos.IntentResponse send(
            Long actorId, Long intentId, IntentDtos.SendRequest request) {

        var intent = loadForWrite(actorId, intentId, Permissions.PROCUREMENT_SUBMIT);
        requireEditable(intent);

        var lines = intentItems.findByIntentIdOrderByIdAsc(intentId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Add something before sending this request.");
        }

        var store = directory.stores(List.of(intent.getSupplierStoreId()))
                .get(intent.getSupplierStoreId());
        if (store == null || !store.tradeable()) {
            throw new BusinessException(ErrorCode.SUPPLIER_OFFLINE,
                    store != null && !store.openNow()
                            ? "%s is closed. They open at %s.".formatted(
                                    store.supplierName(), store.opensAt())
                            : "This supplier isn't taking requests right now.");
        }

        Instant now = Instant.now();
        int windowSeconds = policy.responseWindowSecondsFor(store.responseSlaSeconds());

        transition(intent, IntentStatus.OPEN, actorId);
        intent.setSentAt(now);
        // The supplier's clock starts here, on their own promise. Frozen onto the
        // request so changing the store's SLA later cannot move a deadline that
        // both sides are already watching.
        intent.setResponseWindowSeconds(windowSeconds);
        intent.setResponseDeadline(policy.responseDeadline(now, windowSeconds));
        intent.setRequestedDeliveryTime(request.requestedDeliveryTime());
        if (request.notes() != null) {
            intent.setNotes(request.notes());
        }
        intents.save(intent);

        outbox.publish("IntentSent", "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId(),
                        "itemCount", lines.size(),
                        "responseDeadline", intent.getResponseDeadline().toString()),
                actorId, now);

        return mapper.toResponse(intent);
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /**
     * The basket: one draft per supplier this outlet has shopped from.
     *
     * <p>Ordered newest first, which puts the supplier just added at the top.
     *
     * <p>Returns a basket rather than a bare list so the total across every
     * supplier comes from the server. A kitchen sending three requests wants to
     * know what it is about to ask for in total, and the client must not add
     * money up itself.
     */
    @Transactional(readOnly = true)
    public IntentDtos.BasketResponse drafts(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, outletId, "Outlet");
        return mapper.toBasket(
                intents.findByOutletIdAndStatus(outletId, IntentStatus.DRAFT));
    }

    /**
     * This outlet's requests, optionally filtered by how much was offered.
     *
     * <p>The filter is on <b>fulfilment</b>, not status, because that is the
     * question being asked — "what didn't I get?" — and fulfilment is derived, so
     * it cannot be filtered in SQL. Drafts are excluded: an unsent basket is not a
     * request, and mixing the two would make the list a to-do list and a history
     * at the same time.
     */
    @Transactional(readOnly = true)
    public List<IntentDtos.IntentResponse> list(
            Long actorId, Long outletId, IntentFulfilment fulfilment) {

        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        var sent = intents.findByOutletIdOrderByCreatedAtDesc(outletId).stream()
                .filter(intent -> intent.getStatus() != IntentStatus.DRAFT)
                .toList();

        var responses = mapper.toResponses(sent);
        if (fulfilment == null) {
            return responses;
        }
        return responses.stream()
                .filter(response -> response.fulfilment() == fulfilment)
                .toList();
    }

    @Transactional(readOnly = true)
    public IntentDtos.IntentResponse get(Long actorId, Long intentId) {
        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        // Visible to the outlet that raised it and the store it was sent to, and
        // to nobody else. Either scope is sufficient; neither is a 403, because a
        // 403 on an id tells the caller the id exists (doc 09 §3).
        boolean restaurantSide = accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, intent.getOutletId());
        boolean supplierSide = accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, intent.getSupplierStoreId());
        if (!restaurantSide && !supplierSide) {
            throw new NotFoundException("Intent", intentId);
        }

        var response = mapper.toResponse(intent);

        // A supplier's unsubmitted draft answer is theirs until they send it. The
        // restaurant seeing a half-typed offer would be reading a commitment
        // nobody had made.
        if (restaurantSide && !supplierSide && response.acceptance() != null
                && response.acceptance().status() != com.costonomy.mp.intent.domain
                        .IntentAcceptanceStatus.SUBMITTED) {
            return withoutAcceptance(response);
        }
        return response;
    }

    private IntentDtos.IntentResponse withoutAcceptance(IntentDtos.IntentResponse source) {
        return new IntentDtos.IntentResponse(
                source.id(), source.reference(), source.outletId(), source.supplierStoreId(),
                source.storeName(), source.supplierName(), source.status(), source.fulfilment(),
                source.source(), source.clonedFromId(), source.requestedDeliveryTime(),
                source.notes(), source.sentAt(), source.responseDeadline(),
                source.responseWindowSeconds(), source.acceptedAt(),
                source.orderCreationDeadline(), source.orderCreationWindowSeconds(),
                source.cancelledAt(), source.expiredAt(), source.createdAt(), source.serverTime(),
                source.editable(), source.withinOrderWindow(), source.items(),
                null, source.supplierOrderId(), source.supplierOrderNumber());
    }

    // ── Ending and repeating ─────────────────────────────────────────────

    @Transactional
    public IntentDtos.IntentResponse cancel(Long actorId, Long intentId) {
        var intent = loadForWrite(actorId, intentId, Permissions.ORDER_CANCEL);

        if (!intent.getStatus().canTransitionTo(IntentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    intent.getStatus() == IntentStatus.ORDERED
                            ? "This request became an order. Cancel the order instead."
                            : "This request has already finished.");
        }

        Instant now = Instant.now();
        transition(intent, IntentStatus.CANCELLED, actorId);
        intent.setCancelledAt(now);
        intents.save(intent);

        outbox.publish("IntentCancelled", "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId()),
                actorId, now);

        return mapper.toResponse(intent);
    }

    /**
     * Copy a finished request into a fresh draft. §8.
     *
     * <p>This is how a request is repeated, and the reason {@code ORDERED} is
     * terminal: an intent records one conversation with one supplier, and reusing
     * it would overwrite what was asked and answered last time. Cloning keeps both.
     *
     * <p>The clone is a draft, so it can be edited before it goes out — prices,
     * stock and even the supplier's catalogue have moved since. Lines whose SKU is
     * no longer listed are dropped rather than carried: a request for a pack that
     * cannot be supplied wastes the supplier's time. The canonical product on each
     * line is what makes re-shopping elsewhere possible later.
     */
    @Transactional
    public IntentDtos.IntentResponse clone(Long actorId, Long intentId) {
        var source = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, source.getOutletId(), "Intent");

        if (source.getStatus() == IntentStatus.DRAFT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This request is still a draft. Edit it instead of copying it.");
        }

        var lines = intentItems.findByIntentIdOrderByIdAsc(intentId);
        var skuStates = skus.findAllById(lines.stream()
                .map(IntentItem::getSupplierSkuId).toList());

        var live = new ArrayList<Long>();
        skuStates.forEach(sku -> {
            if ("ACTIVE".equals(sku.getStatus())) {
                live.add(sku.getId());
            }
        });

        if (live.isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE,
                    "None of these products are listed by this supplier any more.");
        }

        // Into the existing draft for this store if there is one, so cloning twice
        // does not build two baskets for one supplier.
        var target = openDraft(actorId, source.getOutletId(), source.getSupplierStoreId());
        if (target.getClonedFromId() == null) {
            target.setClonedFromId(source.getId());
            target.setSource("CLONE");
            intents.save(target);
        }

        for (IntentItem line : lines) {
            if (!live.contains(line.getSupplierSkuId())) {
                continue;
            }
            var existing = intentItems
                    .findByIntentIdAndSupplierSkuId(target.getId(), line.getSupplierSkuId());
            if (existing.isPresent()) {
                var item = existing.get();
                item.setRequestedQuantity(
                        item.getRequestedQuantity().add(line.getRequestedQuantity()));
                intentItems.save(item);
            } else {
                var item = new IntentItem();
                item.setIntentId(target.getId());
                item.setSupplierSkuId(line.getSupplierSkuId());
                item.setCanonicalProductId(line.getCanonicalProductId());
                item.setRequestedQuantity(line.getRequestedQuantity());
                item.setUnit(line.getUnit());
                item.setNotes(line.getNotes());
                intentItems.save(item);
            }
        }

        auditService.record(actorId, null, "INTENT_CLONED", "INTENT", target.getId(),
                source.getReference(), target.getReference(), null, "API");

        return mapper.toResponse(target);
    }

    // ── Shared ───────────────────────────────────────────────────────────

    private Intent loadForWrite(Long actorId, Long intentId, String permission) {
        var intent = intents.findById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));
        accessControl.requireScoped(actorId, permission,
                ScopeType.OUTLET, intent.getOutletId(), "Intent");
        return intent;
    }

    private void requireEditable(Intent intent) {
        if (!intent.getStatus().isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This request has already been sent and can't be changed.");
        }
    }

    /**
     * Move the intent, refusing anything the lifecycle does not allow.
     *
     * <p>Centralised so no caller can set a status directly — the whole point of
     * {@code IntentStatus.allowedTransitions} is that the set of legal moves lives
     * in one place rather than being re-asserted at each call site.
     */
    private void transition(Intent intent, IntentStatus target, Long actorId) {
        var from = intent.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A request at %s cannot move to %s.".formatted(from, target));
        }
        intent.setStatus(target);
        auditService.record(actorId, null, "INTENT_" + target.name(), "INTENT",
                intent.getId(), from.name(), target.name(), intent.getReference(), "API");
    }
}
