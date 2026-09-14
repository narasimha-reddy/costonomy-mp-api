package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.RequirementItemRepository;
import com.costonomy.mp.procurement.repository.RequirementRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirements — what an outlet needs. Doc 03 §3, doc 11.
 *
 * <p>The class that makes guardrail 14 real. A requirement is not consumed by
 * being ordered: quantities are credited against it only when a supplier actually
 * accepts, so a rejection, a timeout or a partial acceptance leaves the shortfall
 * sitting there, still sourceable, with nothing for the restaurant to retype.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequirementService {

    private final RequirementRepository requirements;
    private final RequirementItemRepository requirementItems;
    private final CanonicalProductRepository products;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public ProcurementDtos.RequirementResponse create(
            Long actorId, Long outletId, ProcurementDtos.CreateRequirementRequest request) {

        accessControl.requireScoped(actorId, Permissions.REQUIREMENT_CREATE,
                ScopeType.OUTLET, outletId, "Outlet");

        var requirement = new Requirement();
        requirement.setOutletId(outletId);
        requirement.setCreatedBy(actorId);
        requirement.setStatus(RequirementStatus.OPEN);
        requirement.setSource(request.source() == null ? "MANUAL" : request.source());
        requirement.setNotes(request.notes());
        requirement.setNeededBy(request.neededBy());
        requirements.saveAndFlush(requirement);

        for (var item : request.items()) {
            if (!products.existsById(item.canonicalProductId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "One of those products isn't in the Mandi catalog.");
            }
            var requirementItem = new RequirementItem();
            requirementItem.setRequirementId(requirement.getId());
            requirementItem.setCanonicalProductId(item.canonicalProductId());
            requirementItem.setRequestedQuantity(item.quantity());
            requirementItem.setUnit(item.unit());
            requirementItem.setNotes(item.notes());
            requirementItems.save(requirementItem);
        }

        auditService.record(actorId, null, "REQUIREMENT_CREATED", "REQUIREMENT",
                requirement.getId(), null, RequirementStatus.OPEN.name(), null, "API");
        outbox.publish("RequirementCreated", "REQUIREMENT", requirement.getId(),
                Map.of("outletId", outletId, "itemCount", request.items().size()), actorId);

        return toResponse(requirement);
    }

    @Transactional(readOnly = true)
    public ProcurementDtos.RequirementResponse get(Long actorId, Long requirementId) {
        var requirement = load(requirementId);
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, requirement.getOutletId(), "Requirement");
        return toResponse(requirement);
    }

    @Transactional(readOnly = true)
    public List<ProcurementDtos.RequirementResponse> listForOutlet(
            Long actorId, Long outletId, boolean openOnly) {

        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        var found = openOnly
                ? requirements.findByOutletIdAndStatusInOrderByCreatedAtDesc(outletId,
                        List.of(RequirementStatus.OPEN, RequirementStatus.SOURCING,
                                RequirementStatus.PARTIALLY_FULFILLED))
                : requirements.findByOutletIdOrderByCreatedAtDesc(outletId);

        return found.stream().map(this::toResponse).toList();
    }

    @Transactional
    public ProcurementDtos.RequirementResponse cancel(Long actorId, Long requirementId) {
        var requirement = load(requirementId);
        accessControl.requireScoped(actorId, Permissions.REQUIREMENT_EDIT,
                ScopeType.OUTLET, requirement.getOutletId(), "Requirement");

        transition(requirement, RequirementStatus.CANCELLED, actorId);
        requirement.setCancelledAt(Instant.now());
        requirements.save(requirement);

        return toResponse(requirement);
    }

    // ── Fulfilment accounting ────────────────────────────────────────────

    /**
     * Credit accepted quantities back to the requirement.
     *
     * <p>Called when a supplier <b>accepts</b>, never when an order is placed. That
     * distinction is the whole of guardrail 14: placing an order is a hope, and
     * decrementing on hope would make a rejected order look fulfilled and quietly
     * lose the need. A rejection or timeout therefore requires no compensation —
     * nothing was ever credited.
     *
     * @param acceptedByRequirementItem requirement item id → quantity actually accepted
     */
    @Transactional
    public void creditAcceptedQuantities(Map<Long, BigDecimal> acceptedByRequirementItem, Long actorId) {
        if (acceptedByRequirementItem.isEmpty()) {
            return;
        }

        var items = requirementItems.findAllById(acceptedByRequirementItem.keySet());
        var touchedRequirements = new java.util.HashSet<Long>();

        for (RequirementItem item : items) {
            BigDecimal accepted = acceptedByRequirementItem.get(item.getId());
            if (accepted == null || accepted.signum() <= 0) {
                continue;
            }

            // Clamped, because the database check constraint would otherwise reject
            // the write. It should be unreachable — an order line cannot exceed the
            // requirement it came from — so reaching it means a bug upstream, and
            // failing the acceptance would be the wrong response to that.
            BigDecimal updated = item.getFulfilledQuantity().add(accepted);
            if (updated.compareTo(item.getRequestedQuantity()) > 0) {
                log.error("Accepted quantity {} exceeds requirement item {} remaining — clamping",
                        accepted, item.getId());
                updated = item.getRequestedQuantity();
            }

            item.setFulfilledQuantity(updated);
            item.setStatus(item.isFullyFulfilled() ? "FULFILLED" : "PARTIALLY_FULFILLED");
            touchedRequirements.add(item.getRequirementId());
        }

        requirementItems.saveAll(items);
        touchedRequirements.forEach(id -> recomputeStatus(id, actorId));
    }

    /**
     * Recompute a requirement's status from its items.
     *
     * <p>Derived rather than set by whoever last touched it, so the header can never
     * disagree with the lines beneath it.
     */
    @Transactional
    public void recomputeStatus(Long requirementId, Long actorId) {
        var requirement = requirements.findById(requirementId).orElse(null);
        if (requirement == null || requirement.getStatus().isTerminal()) {
            return;
        }

        var items = requirementItems.findByRequirementId(requirementId);
        if (items.isEmpty()) {
            return;
        }

        boolean allFulfilled = items.stream().allMatch(RequirementItem::isFullyFulfilled);
        boolean anyProgress = items.stream()
                .anyMatch(item -> item.getFulfilledQuantity().signum() > 0);

        RequirementStatus target = allFulfilled ? RequirementStatus.FULFILLED
                : anyProgress ? RequirementStatus.PARTIALLY_FULFILLED
                : requirement.getStatus();

        if (target != requirement.getStatus()) {
            transition(requirement, target, actorId);
            if (target == RequirementStatus.FULFILLED) {
                requirement.setFulfilledAt(Instant.now());
                outbox.publish("RequirementFulfilled", "REQUIREMENT", requirementId,
                        Map.of("requirementId", requirementId), actorId);
            }
            requirements.save(requirement);
        }
    }

    /**
     * Move the requirements behind these items into SOURCING.
     *
     * <p>Takes requirement <em>item</em> ids because that is what an order line
     * carries: a cart is built one offer at a time, each line linked to the need it
     * serves, and one cart can serve several requirements.
     */
    @Transactional
    public void markSourcingForItems(List<Long> requirementItemIds, Long actorId) {
        requirementItems.findAllById(requirementItemIds).stream()
                .map(RequirementItem::getRequirementId)
                .distinct()
                .forEach(id -> markSourcing(id, actorId));
    }

    /** Mark a requirement as being sourced, when an order goes out against it. */
    @Transactional
    public void markSourcing(Long requirementId, Long actorId) {
        var requirement = requirements.findById(requirementId).orElse(null);
        if (requirement == null) {
            return;
        }
        if (requirement.getStatus().canTransitionTo(RequirementStatus.SOURCING)) {
            transition(requirement, RequirementStatus.SOURCING, actorId);
            requirements.save(requirement);
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private void transition(Requirement requirement, RequirementStatus target, Long actorId) {
        var current = requirement.getStatus();
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This requirement can't move from %s to %s.".formatted(current, target));
        }
        requirement.setStatus(target);
        auditService.record(actorId, null, "REQUIREMENT_STATUS_CHANGED", "REQUIREMENT",
                requirement.getId(), current.name(), target.name(), null, "API");
    }

    private Requirement load(Long requirementId) {
        return requirements.findById(requirementId)
                .orElseThrow(() -> new NotFoundException("Requirement", requirementId));
    }

    ProcurementDtos.RequirementResponse toResponse(Requirement requirement) {
        var items = requirementItems.findByRequirementId(requirement.getId());

        Map<Long, String> productNames = new HashMap<>();
        products.findAllById(items.stream().map(RequirementItem::getCanonicalProductId).toList())
                .forEach(product -> productNames.put(product.getId(), product.getName()));

        return new ProcurementDtos.RequirementResponse(
                requirement.getId(), requirement.getOutletId(), requirement.getStatus(),
                requirement.getSource(), requirement.getNotes(), requirement.getNeededBy(),
                requirement.getCreatedAt(),
                items.stream()
                        .map(item -> new ProcurementDtos.RequirementItemResponse(
                                item.getId(), item.getCanonicalProductId(),
                                productNames.get(item.getCanonicalProductId()),
                                item.getRequestedQuantity(), item.getFulfilledQuantity(),
                                item.remainingQuantity(), item.getUnit(),
                                item.getStatus(), item.getNotes()))
                        .toList());
    }
}
