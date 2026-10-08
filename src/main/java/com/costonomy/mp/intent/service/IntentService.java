package com.costonomy.mp.intent.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.common.audit.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

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
import com.costonomy.mp.procurement.domain.Pricing;
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
 * <p><b>A sent request is frozen in shape, not in quantity.</b>
 * {@code isEditable()} is true only for {@code DRAFT}, and every structural
 * mutator — add, remove, send — checks it: once a supplier is pricing a request,
 * having lines appear and vanish underneath them would leave them committing
 * stock against a list that no longer exists.
 *
 * <p>{@link #updateItem} and {@link #removeItem} are the exceptions, and they
 * check the narrower
 * {@code isQuantityEditable()} instead, which also allows {@code OPEN}. Nothing
 * is committed while a request is open — no answer, no held stock, no price — so
 * a kitchen correcting two crates to four costs the supplier a re-read, where
 * the alternative costs them the whole request and their remaining clock. See
 * D-088 in DECISIONS.md. The supplier's deadline is deliberately not extended by
 * an edit; that would let a restaurant hold a supplier indefinitely.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentService {


    private final IntentRepository intents;
    private final IntentItemRepository intentItems;
    private final SupplierSkuRepository skus;
    private final SupplierOfferRepository offers;
    private final IntentMapper mapper;
    private final SkuDirectory skuDirectory;
    private final IntentPolicy policy;
    private final ProcurementDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final IntentDraftStore draftStore;

    /**
     * Only for {@link #updateItem}'s forced version bump on a sent request. A
     * quantity write touches the line, not the intent, so without this the
     * intent's {@code @Version} never moves and a supplier answering
     * concurrently never collides.
     */
    private final EntityManager entityManager;
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

        // Locking read: the draft is locked, so no one else is adding this SKU, and a plain read could miss a line the
        // last holder of the lock just committed (D-137).
        var existing = intentItems.lockByIntentIdAndSupplierSkuId(intent.getId(), sku.getId());
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
            // The price as it stands now. On a draft this is the baseline a
            // reprice is measured against; sending re-confirms it and locks it.
            item.setSupplierOfferId(offer.getId());
            item.setUnitPriceSnapshot(Pricing.money(offer.getSellingPrice()));
            item.setGstRateSnapshot(offer.getGstRate());
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
        // Intent first, then the line, as everywhere (D-137). Only the line's intent id is read before the lock, and
        // it never changes; the line itself is read under the lock so a concurrent removal or edit is seen.
        var intentId = intentItems.findIntentIdById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        var intent = loadForWrite(actorId, intentId, Permissions.PROCUREMENT_CREATE);
        var item = intentItems.lockById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        requireQuantityEditable(intent);

        boolean sent = intent.getStatus() == IntentStatus.OPEN;

        if (quantity.signum() <= 0) {
            if (sent) {
                // A stepper at zero means "remove the line", which is the right
                // reading in a basket. On a live request it would let a
                // restaurant empty a list a supplier is holding open, leaving a
                // request with nothing in it and a clock still running. Dropping
                // the whole request is what withdrawing is for, and it tells the
                // supplier so.
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A sent request must keep at least this line. "
                                + "Withdraw the request instead of emptying it.");
            }
            return removeLine(actorId, intent, item);
        }

        item.setRequestedQuantity(quantity);
        intentItems.save(item);

        if (sent) {
            // The quantity lives on the line, so writing it leaves the intent
            // itself untouched and its @Version unmoved — a supplier answering
            // in the same instant would read a stale list and still commit.
            // Forcing the increment puts both writers on one version, so
            // whichever loses gets CONCURRENT_MODIFICATION rather than silently
            // pricing quantities that changed underneath them. This is the
            // "already approved?" re-check, enforced by the database rather than
            // by a second read that a race can still slip between.
            entityManager.lock(intent, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            // The intent is already locked (D-137), so the bump is a pessimistic force-increment, which writes the new
            // version immediately: the response carries the revision after the caller's own edit, not the one before
            // it, or sending it back would be refused by the very check the edit triggered. (An optimistic force-
            // increment on an already pessimistically locked row does not bump at all.)
            return mapper.toResponse(intent);
        }
        return mapper.toResponse(intent);
    }

    /**
     * Whether the restaurant wants this supplier's request delivered or will collect it (D-143). Chosen per supplier,
     * on the draft, before it is sent; the supplier then answers knowing it (a pickup needs no delivery offer).
     */
    @Transactional
    public IntentDtos.IntentResponse setDeliveryPreference(Long actorId, Long intentId, String preference) {
        var intent = loadForWrite(actorId, intentId, Permissions.PROCUREMENT_CREATE);
        if (intent.getStatus() != IntentStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This request has already been sent. Withdraw it to change how it should reach you.");
        }
        intent.setDeliveryPreference(preference);
        intents.save(intent);
        return mapper.toResponse(intent);
    }

    @Transactional
    public IntentDtos.IntentResponse removeItem(Long actorId, Long itemId) {
        // Intent first, then the line, as everywhere (D-137). Only the line's intent id is read before the lock, and
        // it never changes; the line itself is read under the lock so a concurrent removal or edit is seen.
        var intentId = intentItems.findIntentIdById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        var intent = loadForWrite(actorId, intentId, Permissions.PROCUREMENT_CREATE);
        var item = intentItems.lockById(itemId)
                .orElseThrow(() -> new NotFoundException("IntentItem", itemId));
        requireQuantityEditable(intent);

        boolean sent = intent.getStatus() == IntentStatus.OPEN;
        if (sent) {
            // The same rule updateItem applies to a stepper at zero, and for the
            // same reason: a request may shrink while a supplier is reading it,
            // but it may not become empty. An empty request is a clock running
            // against nothing, and withdrawing is what says so to the supplier.
            if (intentItems.lockByIntentId(intent.getId()).size() <= 1) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A sent request must keep at least one line. "
                                + "Withdraw the request instead of emptying it.");
            }
            // Same forced bump as a quantity edit: removing a line touches the
            // line, not the intent, so without this a supplier answering
            // concurrently would never collide with the removal.
            entityManager.lock(intent, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            intentItems.delete(item);
            intentItems.flush();
            auditItemRemoved(actorId, intent, item);
            return mapper.toResponse(intent);
        }

        return removeLine(actorId, intent, item);
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
    private IntentDtos.IntentResponse removeLine(Long actorId, Intent intent, IntentItem item) {
        intentItems.delete(item);
        intentItems.flush();
        auditItemRemoved(actorId, intent, item);

        // Counted under the lock, from the latest committed lines: a line a concurrent add committed first must stop
        // this from deleting a draft that is no longer empty (D-137).
        if (intentItems.lockByIntentId(intent.getId()).isEmpty()) {
            var emptied = mapper.toResponse(intent);
            auditService.record(actorId, null, "INTENT_DRAFT_DELETED", "INTENT", intent.getId(),
                    intent.getStatus().name(), null, intent.getReference(), "API");
            intents.delete(intent);
            intents.flush();
            return emptied;
        }
        return mapper.toResponse(intent);
    }

    /** A removed line leaves no row behind, so the audit log is the only record that it existed. */
    private void auditItemRemoved(Long actorId, Intent intent, IntentItem item) {
        auditService.record(actorId, null, "INTENT_ITEM_REMOVED", "INTENT_ITEM", item.getId(),
                "sku %d x %s".formatted(item.getSupplierSkuId(), item.getRequestedQuantity().toPlainString()),
                null, intent.getReference(), "API");
    }

    /**
     * Find or open this outlet's draft for one store.
     *
     * <p>Not exposed on its own: a draft with no lines is an artefact, and every
     * caller here creates one only because it is about to put something on it.
     */
    private Intent openDraft(Long actorId, Long outletId, Long supplierStoreId) {
        // At most one draft exists per outlet and store (V72). Find its id without loading it, lock it, and if there
        // is none insert one in a transaction of its own: a loser of that race gets a duplicate-key error that does
        // not poison this transaction, and simply locks the winner's draft on the next pass. A draft sent or deleted
        // between the lookup and the lock is no longer a draft, so look again (D-137).
        for (int attempt = 0; attempt < 5; attempt++) {
            // The first look is plain and cheap. If what it found was sent or deleted since, this transaction's snapshot
            // would keep showing it, so every later pass reads the latest committed rows instead.
            var id = (attempt == 0
                    ? intents.findDraftId(outletId, supplierStoreId)
                    : draftStore.currentDraftId(outletId, supplierStoreId)).orElse(null);
            if (id == null) {
                try {
                    id = draftStore.insertDraft(actorId, outletId, supplierStoreId, "MANUAL", null);
                } catch (DataIntegrityViolationException raced) {
                    // Someone else's insert won; their row is committed, and a read in a fresh transaction sees it.
                    id = draftStore.currentDraftId(outletId, supplierStoreId).orElse(null);
                    if (id == null) {
                        continue;
                    }
                }
            }
            var locked = intents.lockById(id).orElse(null);
            if (locked != null && locked.getStatus() == IntentStatus.DRAFT) {
                return locked;
            }
        }
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                "Your basket was changed at the same moment. Try again.");
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

        var lines = intentItems.lockByIntentId(intentId);
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

        requireSensibleDeliveryDate(request.preferredDeliveryDate());

        // The same price rule as sending the basket (D-146): a line whose price moved since it was added is shown and
        // agreed to, never sent at a price nobody agreed to. Sending the basket does this itself and then passes
        // acceptance down; this route used to skip it, so a caller could send at a stale snapshot.
        if (!Boolean.TRUE.equals(request.acceptPriceChanges()) && !repricedLines(lines).isEmpty()) {
            throw new BusinessException(ErrorCode.PRICE_CHANGED);
        }
        lines.forEach(this::lockPrice);

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
        intent.setPreferredDeliveryDate(request.preferredDeliveryDate());
        if (request.notes() != null) {
            intent.setNotes(request.notes());
        }
        intents.save(intent);

        // Names who is asking, so the supplier's notification is not just "a restaurant" (flow review 6).
        // Wording only: a failed lookup must not undo sending the request, so it reads as "A restaurant".
        ProcurementDirectory.OutletSummary asking = null;
        try {
            asking = directory.outletSummary(intent.getOutletId());
        } catch (RuntimeException e) {
            log.warn("Could not look up who is asking for request {}", intent.getReference(), e);
        }
        var sent = new java.util.HashMap<String, Object>();
        sent.put("reference", intent.getReference());
        sent.put("outletId", intent.getOutletId());
        sent.put("supplierStoreId", intent.getSupplierStoreId());
        sent.put("itemCount", lines.size());
        sent.put("responseDeadline", intent.getResponseDeadline().toString());
        // Always a name; the outlet only when it adds something (an outlet called like its restaurant would read
        // "Spice Route (Spice Route)").
        String restaurant = asking == null || asking.restaurantName() == null || asking.restaurantName().isBlank()
                ? "A restaurant" : asking.restaurantName();
        sent.put("restaurantName", restaurant);
        if (asking != null && asking.outletName() != null && !asking.outletName().isBlank()
                && !asking.outletName().trim().equalsIgnoreCase(restaurant.trim())) {
            sent.put("outletName", asking.outletName());
        }
        outbox.publish("IntentSent", "INTENT", intent.getId(), sent, actorId, now);

        return mapper.toResponse(intent);
    }

    /**
     * Send the whole basket — one request per supplier, in one action. §7.
     *
     * <p><b>Repriced requests are held, not blocked.</b> One supplier's
     * overnight price rise should not stall the other two, so the unaffected
     * requests go immediately and the changed ones come back in {@code held}
     * with old and new for each line. §23A.16: a price that moved is shown and
     * agreed to, never absorbed — and never silently applied either, which is
     * what sending them anyway would amount to.
     *
     * <p>Accepting the changes re-snapshots each line at the current price and
     * sends. From that point the price is locked: the supplier's reply confirms
     * it or declines the line, and the order is created on the same figure.
     */
    @Transactional
    public IntentDtos.SendBasketResponse sendAll(
            Long actorId, Long outletId, IntentDtos.SendBasketRequest request) {

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, outletId, "Outlet");

        var draftIds = intents.findDraftIds(outletId);
        if (draftIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "There's nothing to send.");
        }
        // One supplier at a time, through the same path as all of them. The
        // outlet query is still the source, so a draft belonging to somebody
        // else's outlet is not found rather than refused — the id is not a way
        // to reach a request the caller could not otherwise see.
        if (request.intentId() != null) {
            draftIds = draftIds.stream()
                    .filter(id -> request.intentId().equals(id))
                    .toList();
            if (draftIds.isEmpty()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That request is no longer a draft.");
            }
        }

        var sent = new ArrayList<IntentDtos.IntentResponse>();
        var held = new ArrayList<IntentDtos.HeldRequest>();

        for (Long draftId : draftIds) {
            // Each draft is locked before it is read, and what was a draft a moment ago may not be now (sent from
            // another screen, or emptied). Lines are read under the lock so a concurrent add is priced too (D-137).
            var draft = intents.lockById(draftId).orElse(null);
            if (draft == null || draft.getStatus() != IntentStatus.DRAFT) {
                continue;
            }
            var lines = intentItems.lockByIntentId(draft.getId());
            if (lines.isEmpty()) {
                continue;
            }

            var changes = repricedLines(lines);
            if (!changes.isEmpty() && !request.acceptPriceChanges()) {
                var store = directory.stores(List.of(draft.getSupplierStoreId()))
                        .get(draft.getSupplierStoreId());
                held.add(new IntentDtos.HeldRequest(draft.getId(), draft.getReference(),
                        store == null ? null : store.storeName(), changes));
                continue;
            }

            // Agreed, so the line now carries the price it will be answered at.
            lines.forEach(this::lockPrice);
            sent.add(send(actorId, draft.getId(),
                    new IntentDtos.SendRequest(request.requestedDeliveryTime(),
                            request.preferredDeliveryDate(), request.notes(), true)));
        }

        return new IntentDtos.SendBasketResponse(sent, held);
    }

    /** Lines whose supplier has repriced since they were added. */
    private List<IntentDtos.PriceChange> repricedLines(List<IntentItem> lines) {
        var changes = new ArrayList<IntentDtos.PriceChange>();
        var labels = skuDirectory.describe(lines.stream()
                .map(IntentItem::getSupplierSkuId).toList());

        for (IntentItem line : lines) {
            var live = offers.findBySupplierSkuIdAndStatus(line.getSupplierSkuId(), "ACTIVE")
                    .orElse(null);
            if (live == null || line.getUnitPriceSnapshot() == null) {
                // No live offer is a different problem, reported at send time by
                // the store check rather than as a price change.
                continue;
            }
            if (!Pricing.differs(live.getSellingPrice(), line.getUnitPriceSnapshot())
                    && !Pricing.differs(live.getGstRate(), line.getGstRateSnapshot())) {
                continue;
            }

            var label = labels.get(line.getSupplierSkuId());
            changes.add(new IntentDtos.PriceChange(
                    line.getId(),
                    label == null ? null : label.productName(),
                    line.getUnitPriceSnapshot(),
                    Pricing.money(live.getSellingPrice()),
                    lineTotal(line.getUnitPriceSnapshot(), line.getGstRateSnapshot(),
                            line.getRequestedQuantity()),
                    lineTotal(Pricing.money(live.getSellingPrice()), live.getGstRate(),
                            line.getRequestedQuantity())));
        }
        return changes;
    }

    /** Re-snapshot a line at the price it is about to be sent at. */
    private void lockPrice(IntentItem line) {
        offers.findBySupplierSkuIdAndStatus(line.getSupplierSkuId(), "ACTIVE")
                .ifPresent(live -> {
                    line.setSupplierOfferId(live.getId());
                    line.setUnitPriceSnapshot(Pricing.money(live.getSellingPrice()));
                    line.setGstRateSnapshot(live.getGstRate());
                    intentItems.save(line);
                });
    }

    private BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal gstRate, BigDecimal quantity) {
        if (unitPrice == null || gstRate == null) {
            return null;
        }
        var value = Pricing.lineItemValue(unitPrice, quantity);
        return Pricing.lineTotal(value, Pricing.lineGst(value, gstRate));
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
                intents.findFilledDrafts(outletId));
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

    /**
     * The same response with the supplier's unsubmitted answer withheld.
     *
     * <p>Every field is restated because a record has no copy-with, and that is
     * exactly how this drifted: fields were added to {@code IntentResponse} and
     * this positional call was not updated, so it stopped compiling. Anything
     * added to the record has to be added here too.
     */
    private IntentDtos.IntentResponse withoutAcceptance(IntentDtos.IntentResponse source) {
        return new IntentDtos.IntentResponse(
                source.id(), source.reference(), source.outletId(),
                source.outletName(), source.restaurantName(), source.outletLocality(),
                source.outletCity(), source.distanceKm(),
                source.supplierStoreId(), source.storeName(), source.supplierName(),
                source.directOrdersEnabled(),
                source.status(), source.fulfilment(),
                source.source(), source.clonedFromId(), source.requestedDeliveryTime(),
                source.preferredDeliveryDate(), source.deliveryPreference(),
                source.notes(), source.sentAt(), source.responseDeadline(),
                source.responseWindowSeconds(), source.acceptedAt(),
                source.orderCreationDeadline(), source.orderCreationWindowSeconds(),
                source.cancelledAt(), source.expiredAt(), source.createdAt(), source.serverTime(),
                source.editable(), source.quantityEditable(), source.revision(),
                source.withinOrderWindow(), source.items(),
                source.agreedValue(), source.agreedGst(), source.agreedTotal(),
                source.pricedComplete(), source.priceChanged(),
                null, source.supplierOrderId(), source.supplierOrderNumber(),
                source.minOrderValue(), source.freeDeliveryThreshold());
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
                    .lockByIntentIdAndSupplierSkuId(target.getId(), line.getSupplierSkuId());
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
        // Locked, so two edits of one request take turns and the second sees what the first committed (D-137).
        var intent = intents.lockById(intentId)
                .orElseThrow(() -> new NotFoundException("Intent", intentId));
        accessControl.requireScoped(actorId, permission,
                ScopeType.OUTLET, intent.getOutletId(), "Intent");
        return intent;
    }

    /** India's calendar day, which is the day a kitchen means by "today". */
    private static final java.time.ZoneId BUSINESS_ZONE = java.time.ZoneId.of("Asia/Kolkata");
    private static final int MAX_DAYS_AHEAD = 30;

    /** A day wanted for delivery must be today or later, and not further out than a month (D-140). */
    private void requireSensibleDeliveryDate(java.time.LocalDate wanted) {
        if (wanted == null) {
            return;
        }
        var today = LocalDate.now(BUSINESS_ZONE);
        if (wanted.isBefore(today) || wanted.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose a delivery day from today to %d days ahead.".formatted(MAX_DAYS_AHEAD));
        }
    }

    private void requireEditable(Intent intent) {
        if (!intent.getStatus().isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This request has already been sent and can't be changed.");
        }
    }

    /**
     * Quantities stay changeable a state longer than the rest of a request —
     * see {@link IntentStatus#isQuantityEditable()}. The message names the state
     * the caller is actually in, because "can't be changed" on a request the
     * supplier answered thirty seconds ago reads as a bug to whoever hits it.
     */
    private void requireQuantityEditable(Intent intent) {
        if (!intent.getStatus().isQuantityEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    switch (intent.getStatus()) {
                        case RESPONSES_RECEIVED ->
                                "The supplier has already answered this request, "
                                        + "so its quantities are fixed.";
                        case ORDERED -> "An order has been created from this request.";
                        case CANCELLED -> "This request was withdrawn.";
                        case EXPIRED -> "This request expired before it was answered.";
                        default -> "This request can no longer be changed.";
                    });
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
