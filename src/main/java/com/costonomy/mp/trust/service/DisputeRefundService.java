package com.costonomy.mp.trust.service;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.db.DeadlockRetry;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.service.OrderFunding;
import com.costonomy.mp.settlement.service.SupplierRefundLedger;
import com.costonomy.mp.trust.domain.Dispute;
import com.costonomy.mp.trust.domain.DisputeMessage;
import com.costonomy.mp.trust.domain.DisputeRefund;
import com.costonomy.mp.trust.domain.DisputeRefundStatus;
import com.costonomy.mp.trust.repository.DisputeMessageRepository;
import com.costonomy.mp.trust.repository.DisputeRefundRepository;
import com.costonomy.mp.trust.repository.DisputeRepository;
import com.costonomy.mp.trust.web.dto.TrustDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Refunds, asked for on a dispute (D-104).
 *
 * <p><b>The restaurant asks; the supplier decides; operations decides when the
 * supplier declines or does not answer within 48 hours.</b> A restaurant cannot
 * refund itself — that was the finding this replaces.
 *
 * <p><b>An approval moves money twice, in one transaction:</b> the supplier's
 * payout for the order is charged ({@link SupplierRefundLedger}), and the
 * restaurant's wallet is credited (through the order's funding method). Either
 * both happen or neither. Costonomy never funds a refund, so an approval the
 * payout cannot cover is refused rather than paid.
 *
 * <p>Lock order: the request, then the settlement, the wallet and the payment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeRefundService {

    /** How long the supplier alone decides. After it, operations may too. */
    public static final Duration SUPPLIER_WINDOW = Duration.ofHours(48);

    private final DisputeRepository disputes;
    private final DisputeRefundRepository requests;
    private final DisputeMessageRepository messages;
    private final AccessControlService accessControl;
    private final OrderFunding funding;
    private final SupplierRefundLedger ledger;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    // ── The restaurant ───────────────────────────────────────────────────

    /** How much could be asked for, or why nothing can. */
    @Transactional(readOnly = true)
    public TrustDtos.DisputeRefundLimitResponse limit(Long actorId, Long disputeId) {
        var dispute = loadForRestaurant(actorId, disputeId);
        try {
            return new TrustDtos.DisputeRefundLimitResponse(maxRefund(dispute), null);
        } catch (BusinessException refused) {
            return new TrustDtos.DisputeRefundLimitResponse(BigDecimal.ZERO, refused.getMessage());
        }
    }

    @Transactional
    public TrustDtos.DisputeRefundResponse request(Long actorId, Long disputeId,
                                                   TrustDtos.RequestDisputeRefundRequest request) {
        var dispute = loadForRestaurant(actorId, disputeId);
        if (dispute.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This dispute is closed. Raise a new one to ask for a refund.");
        }
        if (requests.findByDisputeId(disputeId).isPresent()) {
            throw new BusinessException(ErrorCode.REFUND_ALREADY_REQUESTED,
                    "A refund has already been asked for on this dispute.");
        }

        BigDecimal max = maxRefund(dispute);
        if (request.amount().compareTo(max) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most ₹%s can be refunded on this order.".formatted(Rupees.of(max)));
        }

        var refund = new DisputeRefund();
        refund.setDisputeId(disputeId);
        refund.setSupplierOrderId(dispute.getSupplierOrderId());
        refund.setOutletId(dispute.getOutletId());
        refund.setSupplierStoreId(dispute.getSupplierStoreId());
        refund.setAmount(request.amount());
        refund.setReason(request.reason());
        refund.setRequestedBy(actorId);
        requests.saveAndFlush(refund);

        say(dispute, "RESTAURANT", actorId, "Asked for a refund of ₹%s.%s".formatted(
                Rupees.of(request.amount()), suffix(request.reason())));
        auditService.record(actorId, null, "DISPUTE_REFUND_REQUESTED", "DISPUTE", disputeId,
                null, DisputeRefundStatus.REQUESTED.name(), request.amount().toPlainString(), "API");
        publish(refund, dispute, actorId, "RESTAURANT");
        log.info("Refund of {} requested on dispute {} (order {})", request.amount().toPlainString(),
                dispute.getDisputeNumber(), dispute.getSupplierOrderId());
        return toResponse(refund);
    }

    // ── The supplier ─────────────────────────────────────────────────────

    /**
     * The supplier approves. Its own transaction, run once more if the database rolls it back as the loser of a
     * deadlock: the whole approval (the supplier's charge and the wallet credit) is undone together, so running it
     * again is safe, and an approval should not fail on a collision with another outlet's refund.
     */
    public TrustDtos.DisputeRefundResponse supplierApprove(Long actorId, Long requestId, String note) {
        return DeadlockRetry.once(() -> txTemplate.execute(status -> approveAsSupplier(actorId, requestId, note)));
    }

    private TrustDtos.DisputeRefundResponse approveAsSupplier(Long actorId, Long requestId, String note) {
        var refund = lockForSupplier(actorId, requestId);
        if (refund.getStatus() == DisputeRefundStatus.APPROVED) {
            return toResponse(refund);
        }
        requireStatus(refund, DisputeRefundStatus.REQUESTED);

        pay(refund, actorId, note);
        refund.setStatus(DisputeRefundStatus.APPROVED);
        refund.setSupplierDecidedBy(actorId);
        refund.setSupplierDecidedAt(Instant.now());
        refund.setSupplierNote(note);
        requests.save(refund);
        return decided(refund, actorId, "SUPPLIER",
                "Approved the refund of ₹%s.%s".formatted(Rupees.of(refund.getAmount()), suffix(note)));
    }

    @Transactional
    public TrustDtos.DisputeRefundResponse supplierDecline(Long actorId, Long requestId, String note) {
        var refund = lockForSupplier(actorId, requestId);
        if (refund.getStatus() == DisputeRefundStatus.DECLINED) {
            return toResponse(refund);
        }
        requireStatus(refund, DisputeRefundStatus.REQUESTED);
        requireNote(note);

        refund.setStatus(DisputeRefundStatus.DECLINED);
        refund.setSupplierDecidedBy(actorId);
        refund.setSupplierDecidedAt(Instant.now());
        refund.setSupplierNote(note);
        requests.save(refund);
        return decided(refund, actorId, "SUPPLIER", "Declined the refund." + suffix(note));
    }

    // ── Operations ───────────────────────────────────────────────────────

    /** Declined by the supplier, or unanswered past their 48 hours. Oldest first. */
    @Transactional(readOnly = true)
    public List<TrustDtos.DisputeRefundResponse> escalated(Long actorId) {
        accessControl.require(actorId, Permissions.DISPUTE_INSPECT, ScopeType.PLATFORM, null);
        return requests.findEscalated(Instant.now().minus(SUPPLIER_WINDOW)).stream()
                .map(DisputeRefundService::toResponse)
                .toList();
    }

    /** Operations approve: one transaction, run once more after a lost deadlock (see {@link #supplierApprove}). */
    public TrustDtos.DisputeRefundResponse opsApprove(Long actorId, Long requestId, String note) {
        return DeadlockRetry.once(() -> txTemplate.execute(status -> approveAsOperations(actorId, requestId, note)));
    }

    private TrustDtos.DisputeRefundResponse approveAsOperations(Long actorId, Long requestId, String note) {
        accessControl.require(actorId, Permissions.REFUND_DECIDE, ScopeType.PLATFORM, null);
        requireNote(note);
        var refund = lockForOps(requestId);
        if (refund.getStatus() == DisputeRefundStatus.OPS_APPROVED) {
            return toResponse(refund);
        }

        pay(refund, actorId, note);
        refund.setStatus(DisputeRefundStatus.OPS_APPROVED);
        refund.setOpsDecidedBy(actorId);
        refund.setOpsDecidedAt(Instant.now());
        refund.setOpsNote(note);
        requests.save(refund);
        return decided(refund, actorId, "OPERATIONS",
                "Mandi approved the refund of ₹%s.%s".formatted(Rupees.of(refund.getAmount()), suffix(note)));
    }

    @Transactional
    public TrustDtos.DisputeRefundResponse opsDecline(Long actorId, Long requestId, String note) {
        accessControl.require(actorId, Permissions.REFUND_DECIDE, ScopeType.PLATFORM, null);
        requireNote(note);
        var refund = lockForOps(requestId);
        if (refund.getStatus() == DisputeRefundStatus.OPS_DECLINED) {
            return toResponse(refund);
        }

        refund.setStatus(DisputeRefundStatus.OPS_DECLINED);
        refund.setOpsDecidedBy(actorId);
        refund.setOpsDecidedAt(Instant.now());
        refund.setOpsNote(note);
        requests.save(refund);
        return decided(refund, actorId, "OPERATIONS", "Mandi declined the refund." + suffix(note));
    }

    // ── Reading, for disputes ────────────────────────────────────────────

    public Map<Long, TrustDtos.DisputeRefundResponse> forDisputes(List<Long> disputeIds) {
        var found = new HashMap<Long, TrustDtos.DisputeRefundResponse>();
        if (!disputeIds.isEmpty()) {
            requests.findByDisputeIdIn(disputeIds)
                    .forEach(refund -> found.put(refund.getDisputeId(), toResponse(refund)));
        }
        return found;
    }

    public static TrustDtos.DisputeRefundResponse toResponse(DisputeRefund refund) {
        return new TrustDtos.DisputeRefundResponse(
                refund.getId(), refund.getDisputeId(), refund.getSupplierOrderId(),
                refund.getOutletId(), refund.getSupplierStoreId(), refund.getAmount(),
                refund.getReason(), refund.getStatus(), refund.getCreatedAt(),
                refund.getCreatedAt() == null ? null : refund.getCreatedAt().plus(SUPPLIER_WINDOW),
                refund.getSupplierNote(), refund.getSupplierDecidedAt(),
                refund.getOpsNote(), refund.getOpsDecidedAt(), refund.getRefundId());
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Charge the supplier, then credit the restaurant. The ledger refuses if the
     * payout cannot cover it, and the funding method refuses if the order's money
     * cannot; either refusal rolls both back.
     */
    private void pay(DisputeRefund refund, Long actorId, String note) {
        var dispute = disputes.findById(refund.getDisputeId()).orElseThrow();
        ledger.charge(refund.getSupplierOrderId(), refund.getId(), refund.getAmount(),
                "dispute " + dispute.getDisputeNumber(), actorId);
        Long refundId = funding.refundToWallet(refund.getSupplierOrderId(), refund.getAmount(),
                "dispute-refund-" + refund.getId(), actorId,
                "Dispute " + dispute.getDisputeNumber() + suffix(note));
        refund.setRefundId(refundId);
        log.info("Refund {} of {} on dispute {} paid to the wallet and charged to the supplier",
                refund.getId(), refund.getAmount().toPlainString(), dispute.getDisputeNumber());
    }

    /** What this dispute's order can still give back: the lower of the payout and the money. */
    private BigDecimal maxRefund(Dispute dispute) {
        var coverage = ledger.coverage(dispute.getSupplierOrderId());
        if (coverage.refused()) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, coverage.refusal());
        }
        BigDecimal money = funding.refundableToWallet(dispute.getSupplierOrderId());
        if (money.signum() <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Nothing on this order can be refunded to your wallet. A credit order is "
                            + "settled with your supplier directly.");
        }
        BigDecimal max = coverage.available().min(money);
        if (max.signum() <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This order has already been refunded in full.");
        }
        return max;
    }

    private TrustDtos.DisputeRefundResponse decided(DisputeRefund refund, Long actorId, String side,
                                                    String message) {
        var dispute = disputes.findById(refund.getDisputeId()).orElseThrow();
        say(dispute, side, actorId, message);
        auditService.record(actorId, null, "DISPUTE_REFUND_" + refund.getStatus().name(), "DISPUTE",
                dispute.getId(), null, refund.getStatus().name(), refund.getAmount().toPlainString(),
                "OPERATIONS".equals(side) ? "ADMIN" : "API");
        publish(refund, dispute, actorId, side);
        log.info("Refund request {} on dispute {} → {} by {}", refund.getId(),
                dispute.getDisputeNumber(), refund.getStatus(), side);
        return toResponse(refund);
    }

    private void publish(DisputeRefund refund, Dispute dispute, Long actorId, String side) {
        outbox.publish(refund.getStatus().eventName(), "DISPUTE", dispute.getId(),
                Map.of("outletId", dispute.getOutletId(),
                        "supplierStoreId", dispute.getSupplierStoreId(),
                        "supplierOrderId", dispute.getSupplierOrderId(),
                        "disputeNumber", dispute.getDisputeNumber(),
                        "amount", Rupees.of(refund.getAmount()),
                        "status", refund.getStatus().name(),
                        "decidedBy", side),
                actorId);
    }

    private void say(Dispute dispute, String side, Long actorId, String body) {
        var message = new DisputeMessage();
        message.setDisputeId(dispute.getId());
        message.setAuthorSide(side);
        message.setAuthorId(actorId);
        message.setMessage(body);
        message.setInternal(false);
        messages.save(message);
    }

    private Dispute loadForRestaurant(Long actorId, Long disputeId) {
        var dispute = disputes.findById(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute", disputeId));
        accessControl.requireScoped(actorId, Permissions.DISPUTE_CREATE, ScopeType.OUTLET,
                dispute.getOutletId(), "Dispute");
        return dispute;
    }

    private DisputeRefund lockForSupplier(Long actorId, Long requestId) {
        var refund = requests.lockById(requestId)
                .orElseThrow(() -> new NotFoundException("DisputeRefund", requestId));
        accessControl.requireScoped(actorId, Permissions.DISPUTE_REFUND_DECIDE, ScopeType.SUPPLIER_STORE,
                refund.getSupplierStoreId(), "DisputeRefund");
        return refund;
    }

    /** Operations decide only what the supplier declined or left unanswered past the window. */
    private DisputeRefund lockForOps(Long requestId) {
        var refund = requests.lockById(requestId)
                .orElseThrow(() -> new NotFoundException("DisputeRefund", requestId));
        if (refund.getStatus() == DisputeRefundStatus.OPS_APPROVED
                || refund.getStatus() == DisputeRefundStatus.OPS_DECLINED) {
            return refund;
        }
        if (refund.getStatus() == DisputeRefundStatus.REQUESTED) {
            Instant answerBy = refund.getCreatedAt().plus(SUPPLIER_WINDOW);
            if (Instant.now().isBefore(answerBy)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "The supplier has until %s to answer.".formatted(answerBy));
            }
            return refund;
        }
        if (refund.getStatus() != DisputeRefundStatus.DECLINED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The supplier already approved this refund.");
        }
        return refund;
    }

    private static void requireStatus(DisputeRefund refund, DisputeRefundStatus expected) {
        if (refund.getStatus() != expected) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This refund has already been decided (%s).".formatted(refund.getStatus()));
        }
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say why.");
        }
    }

    private static String suffix(String text) {
        return text == null || text.isBlank() ? "" : " " + text.trim();
    }
}
