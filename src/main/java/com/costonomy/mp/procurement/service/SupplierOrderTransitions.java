package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The transactional half of supplier responses.
 *
 * <p>Separate bean, so the {@code @Transactional} boundaries are real when called
 * through the idempotency wrapper — see D-016 and the three other stores that
 * exist for the same reason.
 *
 * <p><b>How the race is settled.</b> A supplier accepting and the timeout job
 * expiring can both read an order in {@code PENDING_ACCEPTANCE}. Both write. The
 * {@code @Version} column on {@code SupplierOrder} means the second write throws,
 * so exactly one outcome lands — doc 03 §5's requirement, doc 10 §2's mandatory
 * test.
 *
 * <p>Losing that race is then <b>reported for what it is</b>. A supplier whose
 * acceptance lost is told {@code SUPPLIER_ORDER_EXPIRED}, not
 * {@code CONCURRENT_MODIFICATION}. The latter is technically accurate and tells
 * them nothing they can act on; the former is what actually happened to their
 * order, and is what §23A.34's expired state renders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierOrderTransitions {

    private final SupplierOrderRepository orders;
    private final SupplierOrderItemRepository orderItems;
    private final SupplierOrderMapper mapper;
    private final RequirementService requirementService;
    private final OrderFunding funding;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final ProcurementDirectory directory;

    /**
     * The supplier's display name, for the notification a restaurant reads.
     *
     * <p>Every event below carries `supplierStoreId`, which is what downstream
     * modules key on — but doc 08's templates say "{supplierName} accepted order
     * {orderNumber}", and an id cannot fill that. Without the name the renderer
     * drops the placeholder and the restaurant is told " accepted order MP-…",
     * which reads as a bug because it is one.
     *
     * <p>Empty rather than a guess when the store has vanished: the renderer
     * already collapses an unresolved placeholder, and inventing "Your supplier"
     * here would put words in a real company's mouth.
     */
    private String supplierNameOf(Long storeId) {
        var info = directory.stores(java.util.List.of(storeId)).get(storeId);
        return info == null || info.supplierName() == null ? "" : info.supplierName();
    }

    // ── Acceptance ───────────────────────────────────────────────────────

    @Transactional
    public ProcurementDtos.SupplierOrderResponse accept(Long actorId, Long orderId) {
        var order = loadForSupplier(actorId, orderId, Permissions.ORDER_ACCEPT);

        // A retry of an acceptance that already landed. Returning the order is the
        // true answer to "did my acceptance go through?" — an error would not be.
        if (order.getStatus() == SupplierOrderStatus.CONFIRMED) {
            return mapper.toResponse(order);
        }

        assertRespondable(order);

        var items = orderItems.findBySupplierOrderId(orderId);
        items.forEach(item -> {
            item.setAcceptedQuantity(item.getRequestedQuantity());
            item.setStatus(OrderItemStatus.ACCEPTED.name());
        });
        orderItems.saveAll(items);

        order.setStatus(SupplierOrderStatus.CONFIRMED);
        order.setAcceptedAt(Instant.now());
        order.setAcceptedAmount(order.getTotalAmount());

        return finishAcceptance(order, items, actorId, "SupplierOrderAccepted");
    }

    @Transactional
    public ProcurementDtos.SupplierOrderResponse partialAccept(
            Long actorId, Long orderId, ProcurementDtos.PartialAcceptRequest request) {

        var order = loadForSupplier(actorId, orderId, Permissions.ORDER_PARTIAL_ACCEPT);

        if (order.getStatus() == SupplierOrderStatus.PARTIALLY_ACCEPTED
                || order.getStatus() == SupplierOrderStatus.CONFIRMED) {
            return mapper.toResponse(order);
        }

        assertRespondable(order);

        var items = orderItems.findBySupplierOrderId(orderId);
        Map<Long, BigDecimal> answers = new HashMap<>();
        Map<Long, String> reasons = new HashMap<>();
        request.items().forEach(answer -> {
            answers.put(answer.supplierOrderItemId(), answer.acceptedQuantity());
            reasons.put(answer.supplierOrderItemId(), answer.reason());
        });

        // Every line must be answered. An unanswered line is ambiguous between
        // "declined" and "missed", and the restaurant needs to know which.
        for (SupplierOrderItem item : items) {
            if (!answers.containsKey(item.getId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Answer every line — enter 0 for anything you can't supply.");
            }
        }

        BigDecimal acceptedValue = BigDecimal.ZERO;
        BigDecimal acceptedGst = BigDecimal.ZERO;
        boolean anyAccepted = false;
        boolean anyReduced = false;

        for (SupplierOrderItem item : items) {
            BigDecimal accepted = answers.get(item.getId());

            if (accepted.signum() < 0 || accepted.compareTo(item.getRequestedQuantity()) > 0) {
                // Doc 04 §11 and doc 10 §3: accepted ≤ requested. A supplier
                // "accepting" more than was ordered is not generosity, it is an
                // order the restaurant never placed.
                throw new BusinessException(ErrorCode.ACCEPTED_QUANTITY_EXCEEDS_REQUESTED);
            }

            item.setAcceptedQuantity(accepted);
            item.setRejectionReason(reasons.get(item.getId()));

            if (accepted.signum() == 0) {
                item.setStatus(OrderItemStatus.REJECTED.name());
            } else if (accepted.compareTo(item.getRequestedQuantity()) == 0) {
                item.setStatus(OrderItemStatus.ACCEPTED.name());
                anyAccepted = true;
            } else {
                item.setStatus(OrderItemStatus.PARTIALLY_ACCEPTED.name());
                anyAccepted = true;
                anyReduced = true;
            }

            // Recomputed from the accepted quantity at the snapshotted price.
            // Doc 01 §14: the restaurant pays only for what was accepted, so this
            // figure — not the ordered total — is what will be captured.
            var lineValue = Pricing.lineItemValue(item.getUnitPriceSnapshot(), accepted);
            var lineGst = Pricing.lineGst(lineValue, item.getGstRateSnapshot());
            acceptedValue = acceptedValue.add(lineValue);
            acceptedGst = acceptedGst.add(lineGst);
        }

        orderItems.saveAll(items);

        if (!anyAccepted) {
            // Zero on every line is a rejection, whatever the endpoint was called.
            // Recording it as a partial acceptance of nothing would corrupt both the
            // supplier's acceptance rate and the restaurant's view of what happened.
            return rejectInternal(order, RejectionReason.OUT_OF_STOCK.name(), request.note(), actorId);
        }

        order.setStatus(anyReduced || hasDeclinedLine(items)
                ? SupplierOrderStatus.PARTIALLY_ACCEPTED : SupplierOrderStatus.CONFIRMED);
        order.setAcceptedAt(Instant.now());
        order.setAcceptedAmount(Pricing.money(acceptedValue.add(acceptedGst)));

        return finishAcceptance(order, items, actorId,
                order.getStatus() == SupplierOrderStatus.PARTIALLY_ACCEPTED
                        ? "SupplierOrderPartiallyAccepted" : "SupplierOrderAccepted");
    }

    /**
     * Persist an acceptance, credit the requirement, and emit the event.
     *
     * <p>The credit happens here and nowhere else. D-015: quantity moves onto the
     * requirement only when a supplier commits to it, which is what makes a
     * rejection or a timeout need no compensation at all.
     */
    private ProcurementDtos.SupplierOrderResponse finishAcceptance(
            SupplierOrder order, List<SupplierOrderItem> items, Long actorId, String eventType) {

        save(order, SupplierOrderStatus.PENDING_ACCEPTANCE);

        Map<Long, BigDecimal> creditByRequirementItem = new HashMap<>();
        for (SupplierOrderItem item : items) {
            if (item.getRequirementItemId() == null || item.getAcceptedQuantity() == null
                    || item.getAcceptedQuantity().signum() <= 0) {
                continue;
            }
            creditByRequirementItem.merge(item.getRequirementItemId(),
                    item.getAcceptedQuantity(), BigDecimal::add);
        }
        requirementService.creditAcceptedQuantities(creditByRequirementItem, actorId);

        // Take only what the supplier committed to (doc 01 §14). This marks the
        // payment; the provider call happens after this transaction commits, so a
        // slow gateway cannot hold it open and a gateway failure cannot roll back
        // an acceptance that really happened.
        funding.onOrderAccepted(order.getId(), order.getAcceptedAmount());

        auditService.record(actorId, null, "SUPPLIER_ORDER_" + order.getStatus().name(),
                "SUPPLIER_ORDER", order.getId(),
                SupplierOrderStatus.PENDING_ACCEPTANCE.name(), order.getStatus().name(),
                null, "API");

        outbox.publish(eventType, "SUPPLIER_ORDER", order.getId(),
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "outletId", order.getOutletId(),
                        "acceptedAmount", order.getAcceptedAmount().toPlainString()),
                actorId, order.getAcceptedAt());

        return mapper.toResponse(order);
    }

    // ── Rejection ────────────────────────────────────────────────────────

    @Transactional
    public ProcurementDtos.SupplierOrderResponse reject(
            Long actorId, Long orderId, ProcurementDtos.RejectOrderRequest request) {

        var order = loadForSupplier(actorId, orderId, Permissions.ORDER_REJECT);

        if (order.getStatus() == SupplierOrderStatus.REJECTED) {
            return mapper.toResponse(order);
        }
        assertRespondable(order);

        return rejectInternal(order, request.reason(), request.note(), actorId);
    }

    private ProcurementDtos.SupplierOrderResponse rejectInternal(
            SupplierOrder order, String reason, String note, Long actorId) {

        var items = orderItems.findBySupplierOrderId(order.getId());
        items.forEach(item -> {
            // Zero, explicitly — the supplier answered, and the answer was none.
            item.setAcceptedQuantity(BigDecimal.ZERO);
            item.setStatus(OrderItemStatus.REJECTED.name());
        });
        orderItems.saveAll(items);

        order.setStatus(SupplierOrderStatus.REJECTED);
        order.setRejectedAt(Instant.now());
        order.setRejectionReason(note == null || note.isBlank() ? reason : reason + ": " + note);
        // Stays zero. Doc 01 §14: nothing is captured for an order nobody accepted.
        order.setAcceptedAmount(BigDecimal.ZERO);

        save(order, SupplierOrderStatus.PENDING_ACCEPTANCE);

        auditService.record(actorId, null, "SUPPLIER_ORDER_REJECTED", "SUPPLIER_ORDER",
                order.getId(), SupplierOrderStatus.PENDING_ACCEPTANCE.name(),
                SupplierOrderStatus.REJECTED.name(), reason, "API");

        // No requirement compensation is needed: nothing was ever credited, so the
        // shortfall is still sitting on the requirement, sourceable (D-015).
        //
        // The money is another matter: the customer's authorisation is released, so
        // a supplier who declines costs them nothing (doc 01 §14).
        funding.onOrderUnfulfilled(order.getId(), "Supplier rejected: " + reason);

        outbox.publish("SupplierOrderRejected", "SUPPLIER_ORDER", order.getId(),
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "outletId", order.getOutletId(),
                        "reason", reason),
                actorId, order.getRejectedAt());

        return mapper.toResponse(order);
    }

    // ── Expiry ───────────────────────────────────────────────────────────

    /**
     * Expire one order whose window closed. Called by the timeout job.
     *
     * <p>Returns false when the order was already resolved — the supplier answered
     * between the job selecting candidates and reaching this one, which is the
     * normal case rather than an error.
     *
     * <p>Does <b>not</b> catch {@link OptimisticLockingFailureException}: if the
     * supplier's acceptance commits first, this transaction must roll back
     * entirely, and the job treats that as the supplier having won.
     */
    @Transactional
    public boolean expire(Long orderId) {
        var order = orders.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != SupplierOrderStatus.PENDING_ACCEPTANCE) {
            return false;
        }

        var items = orderItems.findBySupplierOrderId(orderId);
        items.forEach(item -> {
            // Left null, deliberately. Null is "never answered", which is exactly
            // what happened — writing zero would claim the supplier declined.
            item.setStatus(OrderItemStatus.PENDING.name());
        });

        order.setStatus(SupplierOrderStatus.EXPIRED);
        order.setExpiredAt(Instant.now());
        order.setAcceptedAmount(BigDecimal.ZERO);
        orders.save(order);

        auditService.record(null, null, "SUPPLIER_ORDER_EXPIRED", "SUPPLIER_ORDER",
                order.getId(), SupplierOrderStatus.PENDING_ACCEPTANCE.name(),
                SupplierOrderStatus.EXPIRED.name(),
                "No response within %ds".formatted(order.getResponseSlaSeconds()), "JOB");

        // Nobody is charged for an order nobody answered.
        funding.onOrderUnfulfilled(order.getId(), "No supplier response within SLA");

        // A distinct event from rejection. Doc 01 §12 rule 11: they are different
        // business outcomes and downstream — notifications, performance, analytics
        // — must be able to tell them apart.
        outbox.publish("SupplierOrderExpired", "SUPPLIER_ORDER", order.getId(),
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "outletId", order.getOutletId()),
                null, order.getExpiredAt());

        return true;
    }

    // ── Progress ─────────────────────────────────────────────────────────

    @Transactional
    public ProcurementDtos.SupplierOrderResponse advance(
            Long actorId, Long orderId, SupplierOrderStatus target, String permission) {

        var order = loadForSupplier(actorId, orderId, permission);

        if (order.getStatus() == target) {
            return mapper.toResponse(order);
        }

        var current = order.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can't move from %s to %s.".formatted(current, target));
        }

        order.setStatus(target);
        save(order, current);

        auditService.record(actorId, null, "SUPPLIER_ORDER_" + target.name(), "SUPPLIER_ORDER",
                orderId, current.name(), target.name(), null, "API");

        outbox.publish(target == SupplierOrderStatus.PREPARING
                        ? "SupplierOrderPreparing" : "SupplierOrderReady",
                "SUPPLIER_ORDER", orderId,
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "outletId", order.getOutletId()),
                actorId);

        return mapper.toResponse(order);
    }

    @Transactional
    public ProcurementDtos.SupplierOrderResponse cancel(Long actorId, Long orderId, String reason) {
        var order = orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        accessControl.requireScoped(actorId, Permissions.ORDER_CANCEL,
                ScopeType.OUTLET, order.getOutletId(), "SupplierOrder");

        if (order.getStatus() == SupplierOrderStatus.CANCELLED) {
            return mapper.toResponse(order);
        }

        var current = order.getStatus();
        if (!current.canTransitionTo(SupplierOrderStatus.CANCELLED)) {
            // Doc 01 §13: once the goods have left, the path is return or dispute.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can no longer be cancelled.");
        }

        order.setStatus(SupplierOrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setRejectionReason(reason);
        save(order, current);

        funding.onOrderUnfulfilled(order.getId(), "Cancelled: " + reason);

        auditService.record(actorId, null, "SUPPLIER_ORDER_CANCELLED", "SUPPLIER_ORDER",
                orderId, current.name(), SupplierOrderStatus.CANCELLED.name(), reason, "API");

        return mapper.toResponse(order);
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Persist, translating a lost race into the outcome that actually occurred.
     *
     * <p>A supplier who accepted a moment too late needs to hear that their order
     * expired — that is what happened, and it tells them to expect the restaurant
     * to source elsewhere. {@code CONCURRENT_MODIFICATION} would be true and
     * useless.
     */
    private void save(SupplierOrder order, SupplierOrderStatus expectedPrevious) {
        try {
            orders.saveAndFlush(order);
        } catch (OptimisticLockingFailureException ex) {
            Long id = order.getId();
            log.info("Lost the race on supplier order {} — reporting the winning outcome", id);

            // Read the winner in a fresh state. The entity in hand is stale by
            // definition, so nothing on it can be trusted here.
            var winner = orders.findById(id).orElseThrow(
                    () -> new NotFoundException("SupplierOrder", id));

            throw switch (winner.getStatus()) {
                case EXPIRED -> new BusinessException(ErrorCode.SUPPLIER_ORDER_EXPIRED);
                case CONFIRMED, PARTIALLY_ACCEPTED ->
                        new BusinessException(ErrorCode.SUPPLIER_ORDER_ALREADY_ACCEPTED);
                case CANCELLED -> new BusinessException(ErrorCode.SUPPLIER_ORDER_ALREADY_RESOLVED,
                        "This order was cancelled by the restaurant.");
                default -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
            };
        }
    }

    /**
     * Whether the supplier may still respond.
     *
     * <p>Two separate refusals. An order that has already been answered is
     * {@code ALREADY_RESOLVED}; one whose window closed is {@code EXPIRED}. Doc 13
     * requires that a supplier cannot accept an expired order, and the deadline
     * check here is what enforces it before the job has even run — the job is
     * housekeeping, not the authority.
     */
    private void assertRespondable(SupplierOrder order) {
        if (order.getStatus() == SupplierOrderStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.SUPPLIER_ORDER_EXPIRED);
        }
        if (!order.getStatus().isAwaitingResponse()) {
            throw new BusinessException(ErrorCode.SUPPLIER_ORDER_ALREADY_RESOLVED);
        }
        if (order.isPastDeadline(Instant.now())) {
            // The window closed and the job has not swept yet. Refusing here rather
            // than waiting for the job is what makes "a supplier cannot accept an
            // expired order" true at every instant, not eventually.
            throw new BusinessException(ErrorCode.SUPPLIER_ORDER_EXPIRED);
        }
    }

    private SupplierOrder loadForSupplier(Long actorId, Long orderId, String permission) {
        var order = orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        accessControl.requireScoped(actorId, permission,
                ScopeType.SUPPLIER_STORE, order.getSupplierStoreId(), "SupplierOrder");
        return order;
    }

    private static boolean hasDeclinedLine(List<SupplierOrderItem> items) {
        return items.stream().anyMatch(item ->
                item.getAcceptedQuantity() != null && item.getAcceptedQuantity().signum() == 0);
    }
}
