package com.costonomy.mp.trust.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.trust.domain.*;
import com.costonomy.mp.trust.repository.*;
import com.costonomy.mp.trust.web.dto.TrustDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disputes. Doc 01 §23, doc 03 §12, doc 04 §16, §23A.26.
 *
 * <p><b>A dispute never touches the order.</b> Not its status, not its quantities,
 * not its payment. Doc 01 §22 says the order stays DELIVERED while a dispute runs,
 * and §23A.26 requires the app to tell the restaurant so — which is why the
 * response carries the order's status rather than leaving the client to assume.
 * The alternative makes a restaurant choose between having their delivery recorded
 * and complaining about it.
 *
 * <p><b>Mandi records, it does not adjudicate.</b> Doc 01 §23: disputes exist for
 * intelligence and audit. A resolution is what the two parties agreed, written
 * down — no money moves here, and nothing in this class issues a refund or a
 * credit note on anyone's behalf.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeService {

    private final DisputeRepository disputes;
    private final DisputeItemRepository disputeItems;
    private final DisputeMessageRepository messages;
    private final DisputeEvidenceRepository evidence;
    private final DisputeNumberGenerator disputeNumbers;
    private final TrustDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── The restaurant's side ────────────────────────────────────────────

    @Transactional
    public TrustDtos.DisputeResponse raise(Long actorId, Long supplierOrderId,
                                           TrustDtos.CreateDisputeRequest request) {

        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        accessControl.requireScoped(actorId, Permissions.DISPUTE_CREATE,
                ScopeType.OUTLET, order.outletId(), "SupplierOrder");

        if (!DisputeCategory.isValid(request.category())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose one of the listed dispute categories.");
        }

        var dispute = new Dispute();
        dispute.setDisputeNumber(disputeNumbers.next());
        dispute.setSupplierOrderId(supplierOrderId);
        dispute.setOutletId(order.outletId());
        dispute.setSupplierStoreId(order.supplierStoreId());
        dispute.setCategory(DisputeCategory.valueOf(request.category()));
        dispute.setDescription(request.description());
        dispute.setClaimedAmount(request.claimedAmount());
        dispute.setRaisedBy(actorId);
        disputes.save(dispute);

        if (request.items() != null) {
            var lines = new HashMap<Long, TrustDirectory.OrderLine>();
            directory.linesOf(supplierOrderId).forEach(line -> lines.put(line.itemId(), line));

            for (var line : request.items()) {
                if (!lines.containsKey(line.supplierOrderItemId())) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "That line isn't part of this order.");
                }
                var item = new DisputeItem();
                item.setDisputeId(dispute.getId());
                item.setSupplierOrderItemId(line.supplierOrderItemId());
                item.setDisputedQuantity(line.disputedQuantity());
                item.setReason(line.reason());
                disputeItems.save(item);
            }
        }

        var opening = addMessage(dispute, "RESTAURANT", actorId, request.description(), false);
        saveEvidence(dispute, opening.getId(), actorId, request.evidence());

        auditService.record(actorId, null, "DISPUTE_RAISED", "DISPUTE", dispute.getId(),
                null, DisputeStatus.OPEN.name(),
                "%s on order %s".formatted(request.category(), order.orderNumber()), "API");

        outbox.publish("DisputeCreated", "DISPUTE", dispute.getId(),
                Map.of("outletId", order.outletId(),
                        "supplierStoreId", order.supplierStoreId(),
                        "supplierOrderId", supplierOrderId,
                        "disputeNumber", dispute.getDisputeNumber(),
                        "category", request.category()),
                actorId);

        return toResponse(dispute, order);
    }

    /** The restaurant accepts the outcome, or gives up on it. */
    @Transactional
    public TrustDtos.DisputeResponse resolve(Long actorId, Long disputeId,
                                             TrustDtos.ResolveDisputeRequest request) {

        var dispute = loadForRestaurant(actorId, disputeId, Permissions.DISPUTE_CREATE);
        return close(dispute, actorId, "RESTAURANT", DisputeStatus.RESOLVED,
                request.resolutionType(), request.resolution(), request.message());
    }

    // ── The supplier's side ──────────────────────────────────────────────

    @Transactional
    public TrustDtos.DisputeResponse respond(Long actorId, Long disputeId,
                                             TrustDtos.RespondToDisputeRequest request) {

        var dispute = loadForSupplier(actorId, disputeId, Permissions.DISPUTE_RESPOND);

        if (dispute.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This dispute is already " + dispute.getStatus() + ".");
        }

        var message = addMessage(dispute, "SUPPLIER", actorId, request.message(), false);
        saveEvidence(dispute, message.getId(), actorId, request.evidence());

        dispute.setStatus(DisputeStatus.RESPONDED);
        dispute.setRespondedAt(Instant.now());
        if (request.resolutionType() != null) {
            // Proposed, not applied. Only the restaurant closes a dispute — a
            // supplier declaring their own answer final would end a conversation
            // the other party has not agreed to.
            dispute.setResolutionType(request.resolutionType());
            dispute.setResolution(request.resolution());
        }
        disputes.save(dispute);

        auditService.recordTransition(actorId, "DISPUTE_RESPONDED", "DISPUTE", disputeId,
                DisputeStatus.OPEN.name(), DisputeStatus.RESPONDED.name());

        outbox.publish("DisputeResponded", "DISPUTE", disputeId,
                Map.of("outletId", dispute.getOutletId(),
                        "supplierStoreId", dispute.getSupplierStoreId(),
                        "disputeNumber", dispute.getDisputeNumber()),
                actorId);

        return toResponse(dispute, directory.order(dispute.getSupplierOrderId()));
    }

    @Transactional
    public TrustDtos.DisputeResponse reject(Long actorId, Long disputeId,
                                            TrustDtos.ResolveDisputeRequest request) {

        var dispute = loadForSupplier(actorId, disputeId, Permissions.DISPUTE_RESPOND);
        return close(dispute, actorId, "SUPPLIER", DisputeStatus.REJECTED,
                request.resolutionType(), request.resolution(), request.message());
    }

    // ── Either side ──────────────────────────────────────────────────────

    @Transactional
    public TrustDtos.DisputeResponse comment(Long actorId, Long disputeId,
                                             TrustDtos.DisputeMessageRequest request) {

        var dispute = loadForEitherSide(actorId, disputeId);
        String side = accessControl.has(actorId, Permissions.DISPUTE_CREATE,
                ScopeType.OUTLET, dispute.getOutletId()) ? "RESTAURANT" : "SUPPLIER";

        if (dispute.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This dispute is closed.");
        }

        var message = addMessage(dispute, side, actorId, request.message(), false);
        saveEvidence(dispute, message.getId(), actorId, request.evidence());

        // A reply from either side reopens the conversation: a supplier's answer is
        // not the end if the restaurant has more to say.
        if (dispute.getStatus() == DisputeStatus.RESPONDED
                && dispute.getStatus().canTransitionTo(DisputeStatus.UNDER_REVIEW)) {
            dispute.setStatus(DisputeStatus.UNDER_REVIEW);
            disputes.save(dispute);
        }

        return toResponse(dispute, directory.order(dispute.getSupplierOrderId()));
    }

    @Transactional(readOnly = true)
    public TrustDtos.DisputeResponse get(Long actorId, Long disputeId) {
        var dispute = loadForEitherSide(actorId, disputeId);
        return toResponse(dispute, directory.order(dispute.getSupplierOrderId()));
    }

    @Transactional(readOnly = true)
    public List<TrustDtos.DisputeResponse> forOrder(Long actorId, Long supplierOrderId) {
        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        if (!accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, order.outletId())
                && !accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, order.supplierStoreId())) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        return disputes.findBySupplierOrderIdOrderByCreatedAtDesc(supplierOrderId).stream()
                .map(dispute -> toResponse(dispute, order))
                .toList();
    }

    // ── internals ────────────────────────────────────────────────────────

    private TrustDtos.DisputeResponse close(Dispute dispute, Long actorId, String side,
                                            DisputeStatus target, String resolutionType,
                                            String resolution, String message) {

        if (dispute.getStatus() == target) {
            return toResponse(dispute, directory.order(dispute.getSupplierOrderId()));
        }
        if (!dispute.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This dispute can't move from %s to %s."
                            .formatted(dispute.getStatus(), target));
        }

        var previous = dispute.getStatus();
        dispute.setStatus(target);
        dispute.setResolutionType(resolutionType);
        dispute.setResolution(resolution);
        dispute.setResolvedBy(actorId);
        dispute.setResolvedAt(Instant.now());
        disputes.save(dispute);

        if (message != null && !message.isBlank()) {
            addMessage(dispute, side, actorId, message, false);
        }

        auditService.record(actorId, null, "DISPUTE_" + target.name(), "DISPUTE",
                dispute.getId(), previous.name(), target.name(), resolutionType, "API");

        outbox.publish(target.eventName(), "DISPUTE", dispute.getId(),
                Map.of("outletId", dispute.getOutletId(),
                        "supplierStoreId", dispute.getSupplierStoreId(),
                        "disputeNumber", dispute.getDisputeNumber(),
                        "resolutionType", String.valueOf(resolutionType)),
                actorId);

        return toResponse(dispute, directory.order(dispute.getSupplierOrderId()));
    }

    private DisputeMessage addMessage(Dispute dispute, String side, Long actorId,
                                      String body, boolean internal) {
        var message = new DisputeMessage();
        message.setDisputeId(dispute.getId());
        message.setAuthorSide(side);
        message.setAuthorId(actorId);
        message.setMessage(body);
        message.setInternal(internal);
        return messages.save(message);
    }

    private void saveEvidence(Dispute dispute, Long messageId, Long actorId,
                              List<TrustDtos.EvidenceRequest> requests) {
        if (requests == null) {
            return;
        }
        requests.forEach(request -> {
            var item = new DisputeEvidence();
            item.setDisputeId(dispute.getId());
            item.setDisputeMessageId(messageId);
            item.setEvidenceType(request.evidenceType());
            item.setReference(request.reference());
            item.setCaption(request.caption());
            item.setUploadedBy(actorId);
            evidence.save(item);
        });
    }

    private Dispute loadForRestaurant(Long actorId, Long disputeId, String permission) {
        var dispute = disputes.findById(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute", disputeId));
        accessControl.requireScoped(actorId, permission, ScopeType.OUTLET,
                dispute.getOutletId(), "Dispute");
        return dispute;
    }

    private Dispute loadForSupplier(Long actorId, Long disputeId, String permission) {
        var dispute = disputes.findById(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute", disputeId));
        accessControl.requireScoped(actorId, permission, ScopeType.SUPPLIER_STORE,
                dispute.getSupplierStoreId(), "Dispute");
        return dispute;
    }

    private Dispute loadForEitherSide(Long actorId, Long disputeId) {
        var dispute = disputes.findById(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute", disputeId));

        if (accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, dispute.getOutletId())
                || accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, dispute.getSupplierStoreId())) {
            return dispute;
        }
        log.warn("Scope violation: user={} Dispute={} — reported as not found",
                actorId, disputeId);
        throw new NotFoundException("Dispute", disputeId);
    }

    private TrustDtos.DisputeResponse toResponse(Dispute dispute, TrustDirectory.OrderInfo order) {
        var lines = new HashMap<Long, TrustDirectory.OrderLine>();
        directory.linesOf(dispute.getSupplierOrderId())
                .forEach(line -> lines.put(line.itemId(), line));

        var items = disputeItems.findByDisputeIdOrderByIdAsc(dispute.getId()).stream()
                .map(item -> {
                    var line = lines.get(item.getSupplierOrderItemId());
                    return new TrustDtos.DisputeItemResponse(
                            item.getId(), item.getSupplierOrderItemId(),
                            line == null ? null : line.productName(),
                            item.getDisputedQuantity(), item.getReason());
                })
                .toList();

        var thread = messages.findByDisputeIdOrderByCreatedAtAscIdAsc(dispute.getId()).stream()
                // §23A.32: internal notes are between operators. Filtering here is
                // the only thing keeping them there, so it is never conditional.
                .filter(message -> !Boolean.TRUE.equals(message.getInternal()))
                .map(message -> new TrustDtos.DisputeMessageResponse(
                        message.getId(), message.getAuthorSide(), message.getMessage(),
                        message.getCreatedAt()))
                .toList();

        var attachments = evidence.findByDisputeIdOrderByIdAsc(dispute.getId()).stream()
                .map(item -> new TrustDtos.EvidenceResponse(
                        item.getId(), item.getEvidenceType(), item.getReference(),
                        item.getCaption(), item.getCreatedAt()))
                .toList();

        return new TrustDtos.DisputeResponse(
                dispute.getId(), dispute.getDisputeNumber(), dispute.getSupplierOrderId(),
                order == null ? null : order.orderNumber(),
                // §23A.26: the app says "this does not change your order's status",
                // and it can only say that if it is told the status is unchanged.
                order == null ? null : order.status(),
                dispute.getOutletId(), dispute.getSupplierStoreId(),
                dispute.getCategory().name(), dispute.getStatus(), dispute.getDescription(),
                dispute.getClaimedAmount(), dispute.getResolution(), dispute.getResolutionType(),
                dispute.getRespondedAt(), dispute.getResolvedAt(), dispute.getCreatedAt(),
                items, thread, attachments);
    }
}
