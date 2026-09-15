package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.*;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The cart, its validation, and the approval gate. Doc 01 §11, doc 03 §4, doc 12.
 *
 * <p>Three rules this class exists to hold.
 *
 * <p><b>The server computes every figure.</b> Guardrail 3. The client sends a
 * quantity and an offer id; prices, GST and totals come from the catalog and are
 * recomputed on every validation. There is no field a client could put a total in.
 *
 * <p><b>A price change is surfaced, never absorbed.</b> §23A.16 and guardrail 13.
 * Each line snapshots the price it was added at; validation compares that to the
 * live offer and reports the difference. Submission is blocked until the
 * restaurant explicitly accepts the new prices — an implicit re-read would be a
 * silent reprice wearing a validation's clothes.
 *
 * <p><b>Approval is decided by policy, not by the client.</b> Doc 03 §15. The
 * requester cannot skip it, request it, or nominate who decides.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcurementService {

    private final ProcurementRepository procurements;
    private final ProcurementItemRepository procurementItems;
    private final SupplierOfferRepository offers;
    private final SupplierSkuRepository skus;
    private final CanonicalProductRepository products;
    private final ApprovalPolicyEvaluator approvalPolicy;
    private final ProcurementDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    /**
     * How long a validation stays good.
     *
     * <p>Short, because it is a claim about live prices and offers move. Doc 01 §11
     * requires revalidation at checkout; this is what makes "checkout" and "the
     * moment you validated" the same thing in practice rather than in principle.
     */
    @Value("${costonomy.mp.procurement.validation-ttl:5m}")
    private Duration validationTtl;

    // ── Cart ─────────────────────────────────────────────────────────────

    /**
     * The outlet's open cart, created on first use.
     *
     * <p>One DRAFT per outlet, so "add to cart" from search, from a product page or
     * from a recommendation all land in the same place. A cart per screen would be
     * a cart the restaurant loses.
     */
    @Transactional
    public Procurement openCart(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, outletId, "Outlet");

        return procurements
                .findFirstByOutletIdAndStatusOrderByCreatedAtDesc(outletId, ProcurementStatus.DRAFT)
                .orElseGet(() -> {
                    var cart = new Procurement();
                    cart.setOutletId(outletId);
                    cart.setCreatedBy(actorId);
                    cart.setStatus(ProcurementStatus.DRAFT);
                    return procurements.save(cart);
                });
    }

    @Transactional
    public ProcurementDtos.ProcurementResponse addItem(
            Long actorId, Long outletId, ProcurementDtos.AddCartItemRequest request) {

        var cart = openCart(actorId, outletId);
        requireEditable(cart);

        var offer = offers.findById(request.supplierOfferId())
                .orElseThrow(() -> new NotFoundException("SupplierOffer", request.supplierOfferId()));

        // Checked at add time as a courtesy, and again at validation as the rule.
        // Between the two, anything can change — which is exactly why validation
        // exists and why this check is not the one that matters.
        if (!offer.isPurchasable()) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }

        var sku = skus.findById(offer.getSupplierSkuId())
                .orElseThrow(() -> new NotFoundException("SupplierSku", offer.getSupplierSkuId()));

        var existing = procurementItems
                .findByProcurementIdAndSupplierSkuId(cart.getId(), sku.getId());

        if (existing.isPresent()) {
            // Adding the same SKU again increases its quantity. Two lines for one
            // SKU would leave the restaurant reconciling their own cart by eye.
            var item = existing.get();
            item.setRequestedQuantity(item.getRequestedQuantity().add(request.quantity()));
            item.setStatus("ACTIVE");
            priceLine(item, offer);
            procurementItems.save(item);
        } else {
            var item = new ProcurementItem();
            item.setProcurementId(cart.getId());
            item.setRequirementItemId(request.requirementItemId());
            item.setCanonicalProductId(offer.getCanonicalProductId());
            item.setSupplierStoreId(offer.getSupplierStoreId());
            item.setSupplierSkuId(sku.getId());
            item.setSupplierOfferId(offer.getId());
            item.setRequestedQuantity(request.quantity());
            item.setUnit(sku.getPackUnit());
            priceLine(item, offer);
            procurementItems.save(item);
        }

        // Any edit invalidates the previous validation — the totals just moved.
        markDirty(cart);
        return recalculateAndRespond(cart, List.of(), List.of());
    }

    @Transactional
    public ProcurementDtos.ProcurementResponse updateItemQuantity(
            Long actorId, Long itemId, BigDecimal quantity) {

        var item = procurementItems.findById(itemId)
                .orElseThrow(() -> new NotFoundException("ProcurementItem", itemId));
        var cart = loadForWrite(actorId, item.getProcurementId());
        requireEditable(cart);

        var offer = offers.findById(item.getSupplierOfferId())
                .orElseThrow(() -> new NotFoundException("SupplierOffer", item.getSupplierOfferId()));

        item.setRequestedQuantity(quantity);
        priceLine(item, offer);
        procurementItems.save(item);

        markDirty(cart);
        return recalculateAndRespond(cart, List.of(), List.of());
    }

    @Transactional
    public ProcurementDtos.ProcurementResponse removeItem(Long actorId, Long itemId) {
        var item = procurementItems.findById(itemId)
                .orElseThrow(() -> new NotFoundException("ProcurementItem", itemId));
        var cart = loadForWrite(actorId, item.getProcurementId());
        requireEditable(cart);

        // Soft-removed rather than deleted: the line may already be referenced by a
        // supplier order, and doc 02 §12 forbids hard-deleting transactional rows.
        item.setStatus("REMOVED");
        procurementItems.save(item);

        markDirty(cart);
        return recalculateAndRespond(cart, List.of(), List.of());
    }

    @Transactional
    public ProcurementDtos.ProcurementResponse setPaymentMethod(
            Long actorId, Long procurementId, String paymentMethod) {

        var cart = loadForWrite(actorId, procurementId);
        requireEditable(cart);

        cart.setPaymentMethod(paymentMethod);
        // The payment method is an approval-policy input (doc 28), so changing it
        // can change whether the order needs approval. Revalidate.
        markDirty(cart);
        procurements.save(cart);

        return recalculateAndRespond(cart, List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public ProcurementDtos.ProcurementResponse get(Long actorId, Long procurementId) {
        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");

        return respond(procurement, List.of(), List.of());
    }

    // ── Validation ───────────────────────────────────────────────────────

    /**
     * Re-check every line against live offers, and reprice. Doc 01 §11, doc 12.
     *
     * <p>Checks what doc 01 §11 lists: the SKU still exists and is active, the offer
     * is still current and available, the store is still tradeable, and the price
     * and GST have not moved.
     *
     * <p>A moved price is reported, not applied — unless {@code acceptPriceChanges}
     * is set, which is the restaurant explicitly saying yes to the new figure
     * (§23A.16). Until they do, the cart keeps the old snapshot and is not
     * submittable, so there is no window in which a changed price could be charged
     * without anyone agreeing to it.
     */
    @Transactional
    public ProcurementDtos.ProcurementResponse validate(
            Long actorId, Long procurementId, boolean acceptPriceChanges) {

        var procurement = loadForWrite(actorId, procurementId);

        if (procurement.getStatus() == ProcurementStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order has already been placed.");
        }

        var items = procurementItems.findByProcurementIdAndStatus(procurement.getId(), "ACTIVE");
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Your cart is empty.");
        }

        // Doc 03 §4 puts VALIDATING between DRAFT and READY, and it earns its
        // place: a crash part-way through leaves a procurement visibly mid-check
        // rather than looking like an untouched draft.
        transition(procurement, ProcurementStatus.VALIDATING, actorId);
        procurements.saveAndFlush(procurement);

        List<ProcurementDtos.PriceChange> priceChanges = new ArrayList<>();
        List<ProcurementDtos.Blocker> blockers = new ArrayList<>();

        var stores = directory.stores(items.stream()
                .map(ProcurementItem::getSupplierStoreId).distinct().toList());
        var productNames = productNames(items);

        for (ProcurementItem item : items) {
            var sku = skus.findById(item.getSupplierSkuId()).orElse(null);
            if (sku == null || !"ACTIVE".equals(sku.getStatus())) {
                blockers.add(blocker(item, productNames, ErrorCode.SKU_UNAVAILABLE.name(),
                        "This product is no longer listed by the supplier."));
                continue;
            }

            var store = stores.get(item.getSupplierStoreId());
            if (store == null || !store.tradeable()) {
                blockers.add(blocker(item, productNames, ErrorCode.SUPPLIER_OFFLINE.name(),
                        "%s isn't accepting orders right now.".formatted(
                                store == null ? "This supplier" : store.supplierName())));
                continue;
            }

            // The *current* offer for this SKU, which may be a different row from
            // the one the line was added against — a price change supersedes rather
            // than updates (D-012), so the id moves.
            var current = offers.findBySupplierSkuIdAndStatus(sku.getId(), "ACTIVE").orElse(null);
            if (current == null || !current.isPurchasable()) {
                blockers.add(blocker(item, productNames, ErrorCode.SKU_UNAVAILABLE.name(),
                        "This product is out of stock."));
                continue;
            }

            boolean priceMoved = Pricing.differs(current.getSellingPrice(), item.getUnitPriceSnapshot())
                    || Pricing.differs(current.getGstRate(), item.getGstRateSnapshot());

            if (priceMoved) {
                var previousLineTotal = item.getLineTotal();
                var previousUnitPrice = item.getUnitPriceSnapshot();

                if (acceptPriceChanges) {
                    item.setSupplierOfferId(current.getId());
                    priceLine(item, current);
                    procurementItems.save(item);
                } else {
                    // Reported with both figures so §23A.16 can show old and new.
                    // The snapshot is left alone: the cart still holds the price the
                    // restaurant agreed to, and cannot be submitted until they
                    // agree to the new one.
                    priceChanges.add(new ProcurementDtos.PriceChange(
                            item.getId(), productNames.get(item.getCanonicalProductId()),
                            store.supplierName(), previousUnitPrice, current.getSellingPrice(),
                            previousLineTotal,
                            Pricing.lineTotal(
                                    Pricing.lineItemValue(current.getSellingPrice(),
                                            item.getRequestedQuantity()),
                                    Pricing.lineGst(
                                            Pricing.lineItemValue(current.getSellingPrice(),
                                                    item.getRequestedQuantity()),
                                            current.getGstRate()))));
                }
            } else if (!current.getId().equals(item.getSupplierOfferId())) {
                // Same money, different offer row — availability changed and back,
                // say. Re-point the line so it references the live offer.
                item.setSupplierOfferId(current.getId());
                procurementItems.save(item);
            }
        }

        recalculateTotals(procurement, procurementItems.findByProcurementIdAndStatus(
                procurement.getId(), "ACTIVE"));

        if (priceChanges.isEmpty() && blockers.isEmpty()) {
            var decision = evaluateApproval(procurement, actorId, items);
            applyApproval(procurement, decision, actorId);
            procurement.setValidatedAt(Instant.now());

            // An order already approved stays approved when it is revalidated —
            // otherwise a cart going stale would send it back to its approver every
            // few minutes, and approvers would learn to stop reading them.
            ProcurementStatus target =
                    procurement.getApprovalStatus() == ApprovalStatus.APPROVED
                            ? ProcurementStatus.APPROVED
                            : decision.required()
                            ? ProcurementStatus.PENDING_APPROVAL
                            : ProcurementStatus.READY;

            transition(procurement, target, actorId);
        } else {
            // Not validated: the timestamp is what submission trusts, and a cart
            // with unresolved changes must not look freshly checked.
            procurement.setValidatedAt(null);
            transition(procurement, ProcurementStatus.DRAFT, actorId);
        }

        procurements.save(procurement);
        return respond(procurement, priceChanges, blockers);
    }

    // ── Approval ─────────────────────────────────────────────────────────

    @Transactional
    public ProcurementDtos.ProcurementResponse approve(Long actorId, Long procurementId) {
        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_APPROVE,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");

        if (procurement.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_PENDING);
        }

        procurement.setApprovalStatus(ApprovalStatus.APPROVED);
        procurement.setApprovedBy(actorId);
        procurement.setApprovedAt(Instant.now());
        transition(procurement, ProcurementStatus.APPROVED, actorId);
        procurements.save(procurement);

        auditService.record(actorId, null, "PROCUREMENT_APPROVED", "PROCUREMENT",
                procurementId, ApprovalStatus.PENDING.name(), ApprovalStatus.APPROVED.name(),
                null, "API");

        outbox.publish("ProcurementApproved", "PROCUREMENT", procurementId,
                Map.of("outletId", procurement.getOutletId(),
                        "totalAmount", procurement.getTotalAmount().toPlainString()),
                actorId);

        return respond(procurement, List.of(), List.of());
    }

    @Transactional
    public ProcurementDtos.ProcurementResponse reject(Long actorId, Long procurementId, String reason) {
        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_APPROVE,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");

        if (procurement.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_PENDING);
        }

        procurement.setApprovalStatus(ApprovalStatus.REJECTED);
        procurement.setRejectedBy(actorId);
        procurement.setRejectedAt(Instant.now());
        procurement.setRejectionReason(reason);
        // Back to DRAFT rather than a terminal rejection, so the requester can
        // adjust and resubmit instead of rebuilding the cart from nothing.
        transition(procurement, ProcurementStatus.DRAFT, actorId);
        procurements.save(procurement);

        auditService.record(actorId, null, "PROCUREMENT_REJECTED", "PROCUREMENT",
                procurementId, ApprovalStatus.PENDING.name(), ApprovalStatus.REJECTED.name(),
                reason, "API");

        outbox.publish("ProcurementRejected", "PROCUREMENT", procurementId,
                Map.of("outletId", procurement.getOutletId(),
                        "reason", reason == null ? "" : reason),
                actorId);

        return respond(procurement, List.of(), List.of());
    }

    @Transactional
    public void cancel(Long actorId, Long procurementId) {
        var procurement = loadForWrite(actorId, procurementId);
        transition(procurement, ProcurementStatus.CANCELLED, actorId);
        procurement.setCancelledAt(Instant.now());
        procurements.save(procurement);
    }

    // ── internals ────────────────────────────────────────────────────────

    ApprovalPolicyEvaluator.Decision evaluateApproval(
            Procurement procurement, Long actorId, List<ProcurementItem> items) {

        Long restaurantId = directory.restaurantIdOfOutlet(procurement.getOutletId());
        if (restaurantId == null) {
            return ApprovalPolicyEvaluator.Decision.notRequired();
        }

        var storeIds = items.stream().map(ProcurementItem::getSupplierStoreId).distinct().toList();
        var categoryIds = directory.categoryIdsOfProducts(
                items.stream().map(ProcurementItem::getCanonicalProductId).distinct().toList());
        String requesterRole = directory.primaryRoleOf(actorId, procurement.getOutletId());

        return approvalPolicy.evaluate(procurement, restaurantId, requesterRole, storeIds, categoryIds);
    }

    private void applyApproval(Procurement procurement, ApprovalPolicyEvaluator.Decision decision,
                               Long actorId) {
        if (!decision.required()) {
            procurement.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
            procurement.setApprovalPolicyId(null);
            procurement.setApprovalPolicyVersion(null);
            return;
        }

        // Already approved and nothing material changed — do not re-hold it, or an
        // approver would have to sign off again every time the cart is revalidated.
        if (procurement.getApprovalStatus() == ApprovalStatus.APPROVED) {
            return;
        }

        procurement.setApprovalStatus(ApprovalStatus.PENDING);
        procurement.setApprovalPolicyId(decision.policyId());
        procurement.setApprovalPolicyVersion(decision.policyVersion());

        auditService.record(actorId, null, "PROCUREMENT_APPROVAL_REQUIRED", "PROCUREMENT",
                procurement.getId(), null, ApprovalStatus.PENDING.name(),
                decision.reason(), "API");

        // Doc 08 §1 and §4: an approval request is a critical notification. Without
        // this event, a cart waits for an approver who was never told — the failure
        // is silent on both sides, and the restaurant simply does not get its order.
        outbox.publish("ProcurementApprovalRequested", "PROCUREMENT", procurement.getId(),
                Map.of("outletId", procurement.getOutletId(),
                        "totalAmount", procurement.getTotalAmount().toPlainString(),
                        "reason", decision.reason() == null ? "" : decision.reason()),
                actorId);
    }

    /** Price a line from an offer. The single path by which a cart line gets its money. */
    private void priceLine(ProcurementItem item, SupplierOffer offer) {
        item.setUnitPriceSnapshot(offer.getSellingPrice());
        item.setGstRateSnapshot(offer.getGstRate());
        item.setSupplierOfferId(offer.getId());

        var itemValue = Pricing.lineItemValue(offer.getSellingPrice(), item.getRequestedQuantity());
        var gst = Pricing.lineGst(itemValue, offer.getGstRate());

        item.setLineItemValue(itemValue);
        item.setLineGst(gst);
        item.setLineTotal(Pricing.lineTotal(itemValue, gst));
    }

    void recalculateTotals(Procurement procurement, List<ProcurementItem> items) {
        BigDecimal itemValue = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;

        for (ProcurementItem item : items) {
            itemValue = itemValue.add(item.getLineItemValue());
            gst = gst.add(item.getLineGst());
        }

        procurement.setTotalItemValue(Pricing.money(itemValue));
        procurement.setTotalGst(Pricing.money(gst));
        // Delivery is quoted after Ready for Pickup (doc 06 §6), so it is zero here
        // rather than estimated. A guessed fee inside a total the restaurant is
        // asked to approve would be a made-up commercial value.
        procurement.setTotalDeliveryFee(BigDecimal.ZERO);
        procurement.setTotalAmount(Pricing.money(itemValue.add(gst)));
    }

    private ProcurementDtos.ProcurementResponse recalculateAndRespond(
            Procurement cart, List<ProcurementDtos.PriceChange> changes,
            List<ProcurementDtos.Blocker> blockers) {

        recalculateTotals(cart, procurementItems.findByProcurementIdAndStatus(cart.getId(), "ACTIVE"));
        procurements.save(cart);
        return respond(cart, changes, blockers);
    }

    /** Any edit invalidates the last validation and returns the cart to DRAFT. */
    private void markDirty(Procurement cart) {
        cart.setValidatedAt(null);
        if (cart.getStatus() == ProcurementStatus.READY
                || cart.getStatus() == ProcurementStatus.PENDING_APPROVAL
                || cart.getStatus() == ProcurementStatus.APPROVED) {
            // An approved order whose contents changed is not an approved order.
            cart.setStatus(ProcurementStatus.DRAFT);
            cart.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
            cart.setApprovedBy(null);
            cart.setApprovedAt(null);
        }
    }

    private void requireEditable(Procurement procurement) {
        if (!procurement.getStatus().isEditable()
                && procurement.getStatus() != ProcurementStatus.PENDING_APPROVAL
                && procurement.getStatus() != ProcurementStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can no longer be changed.");
        }
    }

    Procurement loadForWrite(Long actorId, Long procurementId) {
        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");
        return procurement;
    }

    void transition(Procurement procurement, ProcurementStatus target, Long actorId) {
        var current = procurement.getStatus();
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can't move from %s to %s.".formatted(current, target));
        }
        procurement.setStatus(target);
        auditService.record(actorId, null, "PROCUREMENT_STATUS_CHANGED", "PROCUREMENT",
                procurement.getId(), current.name(), target.name(), null, "API");
    }

    private ProcurementDtos.Blocker blocker(ProcurementItem item, Map<Long, String> names,
                                            String code, String message) {
        return new ProcurementDtos.Blocker(item.getId(), code, message);
    }

    private Map<Long, String> productNames(List<ProcurementItem> items) {
        Map<Long, String> names = new HashMap<>();
        products.findAllById(items.stream()
                        .map(ProcurementItem::getCanonicalProductId).distinct().toList())
                .forEach(product -> names.put(product.getId(), product.getName()));
        return names;
    }

    ProcurementDtos.ProcurementResponse respond(
            Procurement procurement, List<ProcurementDtos.PriceChange> priceChanges,
            List<ProcurementDtos.Blocker> blockers) {

        var items = procurementItems.findByProcurementIdAndStatus(procurement.getId(), "ACTIVE");
        var productNames = productNames(items);
        var stores = directory.stores(items.stream()
                .map(ProcurementItem::getSupplierStoreId).distinct().toList());

        Map<Long, SupplierSku> skuById = new HashMap<>();
        skus.findAllById(items.stream().map(ProcurementItem::getSupplierSkuId).toList())
                .forEach(sku -> skuById.put(sku.getId(), sku));

        // LinkedHashMap so supplier groups keep a stable order between calls —
        // §23A.16 renders them as sections, and sections that reshuffle on refresh
        // make a cart feel unreliable.
        Map<Long, List<ProcurementDtos.ProcurementItemResponse>> byStore = new LinkedHashMap<>();
        Map<Long, BigDecimal[]> storeTotals = new LinkedHashMap<>();

        for (ProcurementItem item : items) {
            var sku = skuById.get(item.getSupplierSkuId());
            byStore.computeIfAbsent(item.getSupplierStoreId(), key -> new ArrayList<>())
                    .add(new ProcurementDtos.ProcurementItemResponse(
                            item.getId(), item.getCanonicalProductId(),
                            productNames.get(item.getCanonicalProductId()),
                            item.getSupplierSkuId(),
                            sku == null ? null : sku.getName(), null,
                            sku == null ? null : sku.getPackSize(),
                            sku == null ? null : sku.getPackUnit(),
                            item.getRequestedQuantity(), item.getUnit(),
                            item.getUnitPriceSnapshot(), item.getGstRateSnapshot(),
                            item.getLineItemValue(), item.getLineGst(), item.getLineTotal(),
                            null));

            var totals = storeTotals.computeIfAbsent(item.getSupplierStoreId(),
                    key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(item.getLineItemValue());
            totals[1] = totals[1].add(item.getLineGst());
        }

        List<ProcurementDtos.SupplierGroup> groups = byStore.entrySet().stream()
                .map(entry -> {
                    var store = stores.get(entry.getKey());
                    var totals = storeTotals.get(entry.getKey());
                    return new ProcurementDtos.SupplierGroup(
                            entry.getKey(),
                            store == null ? null : store.supplierName(),
                            store == null ? null : store.storeName(),
                            store == null ? null : store.responseSlaSeconds(),
                            Pricing.money(totals[0]), Pricing.money(totals[1]),
                            Pricing.money(totals[0].add(totals[1])),
                            entry.getValue());
                })
                .toList();

        boolean stale = procurement.isValidationStale(validationTtl);
        // Every condition, spelled out. Submission re-checks all of it rather than
        // trusting this flag — the flag is what the UI disables a button with, and
        // guardrail 4 says a client-side state is never the authority.
        boolean submittable = priceChanges.isEmpty()
                && blockers.isEmpty()
                && !items.isEmpty()
                && !stale
                && procurement.isApprovalSatisfied()
                && (procurement.getStatus() == ProcurementStatus.READY
                    || procurement.getStatus() == ProcurementStatus.APPROVED);

        return new ProcurementDtos.ProcurementResponse(
                procurement.getId(), procurement.getOutletId(), procurement.getRequirementId(),
                procurement.getStatus(), procurement.getApprovalStatus(),
                null, List.of(),
                procurement.getPaymentMethod(),
                procurement.getTotalItemValue(), procurement.getTotalGst(),
                procurement.getTotalDeliveryFee(), procurement.getTotalAmount(),
                procurement.getValidatedAt(), stale, submittable,
                groups, priceChanges, blockers);
    }

    Duration validationTtl() {
        return validationTtl;
    }
}
