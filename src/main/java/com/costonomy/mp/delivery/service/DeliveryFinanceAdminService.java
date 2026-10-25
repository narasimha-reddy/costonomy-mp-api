package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableEvent;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.domain.CourierPaymentAttempt;
import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import com.costonomy.mp.delivery.repository.CourierPaymentAttemptRepository;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.service.GatewayRefundService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What finance and operations see and do about courier payables and gateway refunds (D-204). Reads need
 * DELIVERY_FINANCE_INSPECT and writes DELIVERY_FINANCE_OPERATE, both at PLATFORM scope (D-046). Every list is bounded.
 * Every write is audited here and recorded on the payable's own event trail by the service it calls.
 *
 * <p>Nothing here moves money itself: a payment goes through {@link CourierPaymentService}, a refund through
 * {@link GatewayRefundService}. The operator records what happened outside, or puts a stuck item back in the queue.
 */
@Service
public class DeliveryFinanceAdminService {

    /** The most any queue returns. A queue longer than this is itself the alarm; the count says how long. */
    public static final int QUEUE_LIMIT = 100;

    private final AccessControlService accessControl;
    private final CourierPayableRepository payables;
    private final CourierPaymentAttemptRepository attempts;
    private final CourierPayableService payableService;
    private final GatewayRefundService gatewayRefundService;
    private final AuditService audit;
    private final CourierPayableProperties props;
    private final CourierPayableSyncService syncService;

    public DeliveryFinanceAdminService(AccessControlService accessControl, CourierPayableRepository payables,
                                       CourierPaymentAttemptRepository attempts, CourierPayableService payableService,
                                       GatewayRefundService gatewayRefundService,
                                       AuditService audit, CourierPayableProperties props,
                                       CourierPayableSyncService syncService) {
        this.accessControl = accessControl;
        this.payables = payables;
        this.attempts = attempts;
        this.payableService = payableService;
        this.gatewayRefundService = gatewayRefundService;
        this.audit = audit;
        this.props = props;
        this.syncService = syncService;
    }

    // ── reads ───────────────────────────────────────────────────────────

    public record PayableView(Long id, String sourceType, Long deliveryId, Long attemptId, Long supplierOrderId,
                              Long outletId, Long supplierStoreId, String providerCode, String providerDeliveryId,
                              BigDecimal quotedCost, BigDecimal finalCost, boolean finalCostManual,
                              BigDecimal cancellationFee, BigDecimal providerCredit, BigDecimal amountDue,
                              BigDecimal amountPaid, BigDecimal buyerContribution, BigDecimal supplierContribution,
                              BigDecimal platformFunded, String state, String dueReason, Instant dueAt,
                              String paymentRail, String paymentReference, Instant paidAt, int attemptCount,
                              String lastError, Instant nextRetryAt, String reconciliationState,
                              String reconciliationNote, Instant createdAt, Instant updatedAt) {
    }

    public record AttemptView(int attemptNumber, String rail, BigDecimal amount, String status, String reference,
                              String error, Instant startedAt, Instant endedAt) {
    }

    public record EventView(String eventType, String fromState, String toState, BigDecimal amount, String actor,
                            String reference, String detail, Instant createdAt) {
    }

    public record PayableDetail(PayableView payable, List<AttemptView> attempts, List<EventView> events) {
    }

    public record RefundView(Long id, String sourceType, Long sourceId, String purpose, String providerPaymentId,
                             BigDecimal capturedAmount, BigDecimal amount, String reason, String status,
                             String providerRefundId, int attemptCount, int lookupCount, String lastError,
                             Instant nextRetryAt, Instant requestedAt, Instant completedAt) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    /**
     * The four counts and amounts a finance person starts the day with, split by what the money is: the courier
     * (what we owe) and the customer (what we owe back). A supplier's delivery-funding collection is deliberately not
     * here; it is a receivable, not a courier payment (see the supplier delivery-funding view).
     */
    public record Queues(List<PayableView> overdue, List<PayableView> exhausted, List<PayableView> ambiguous,
                         List<PayableView> unreconciled, List<PayableView> awaitingManualPayment,
                         List<RefundView> refundsOverdue, List<RefundView> refundsExhausted,
                         List<RefundView> refundsAmbiguous, List<RefundView> refundsPending, Totals totals) {
    }

    public record Totals(long overduePayables, BigDecimal courierDue, BigDecimal courierProcessing,
                         BigDecimal courierPaid, long exhaustedPayables, long ambiguousPayables,
                         long unreconciledPayables, long awaitingManualPayment, long refundsOverdue,
                         long refundsExhausted, long refundsAmbiguous, long refundsPending,
                         BigDecimal customerRefundsPending, long bookingsWithoutPayable,
                         long oldestBookingWithoutPayableSeconds, long payableSyncLagSeconds) {
    }

    @Transactional(readOnly = true)
    public Page<PayableView> listPayables(Long actorId, String state, String providerCode, String sourceType,
                                          int page, int size) {
        inspect(actorId);
        var result = payables.search(blankToNull(state), blankToNull(providerCode), blankToNull(sourceType),
                PageRequest.of(Math.max(page, 0), clamp(size)));
        return new Page<>(result.getContent().stream().map(DeliveryFinanceAdminService::view).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @Transactional(readOnly = true)
    public PayableDetail payable(Long actorId, Long id) {
        inspect(actorId);
        var p = payables.findById(id).orElseThrow(() -> new NotFoundException("CourierPayable", id));
        return new PayableDetail(view(p),
                attempts.findByPayableIdOrderByAttemptNumberAsc(id).stream().map(DeliveryFinanceAdminService::view).toList(),
                payableService.history(id).stream().map(DeliveryFinanceAdminService::view).toList());
    }

    @Transactional(readOnly = true)
    public Page<RefundView> listRefunds(Long actorId, String status, String sourceType, int page, int size) {
        inspect(actorId);
        var result = gatewayRefundService.search(blankToNull(status), blankToNull(sourceType), page, clamp(size));
        return new Page<>(result.getContent().stream().map(DeliveryFinanceAdminService::view).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @Transactional(readOnly = true)
    public Queues queues(Long actorId) {
        inspect(actorId);
        var now = Instant.now();
        var overdue = payables.overdue(now.minus(props.getOverdueAfter()), QUEUE_LIMIT);
        var exhausted = payables.inState(CourierPayableState.EXHAUSTED, QUEUE_LIMIT);
        var ambiguous = payables.inState(CourierPayableState.AMBIGUOUS, QUEUE_LIMIT);
        var unreconciled = payables.unreconciled(QUEUE_LIMIT);
        var manual = payables.awaitingManualPayment(QUEUE_LIMIT);
        var refundQueues = gatewayRefundService.queues(QUEUE_LIMIT);
        var syncHealth = syncService.health();
        var totals = new Totals(
                payables.countOverdue(now.minus(props.getOverdueAfter())),
                nz(payables.sumDue(List.of("DUE", "FAILED", "EXHAUSTED", "AMBIGUOUS"))),
                nz(payables.sumDue(List.of("PROCESSING"))),
                nz(payables.sumPaid()),
                payables.countInState(CourierPayableState.EXHAUSTED),
                payables.countInState(CourierPayableState.AMBIGUOUS),
                payables.countUnreconciled(),
                payables.countAwaitingManualPayment(),
                refundQueues.overdueCount(), refundQueues.exhaustedCount(), refundQueues.ambiguousCount(),
                refundQueues.pendingCount(),
                refundQueues.unreturned(), syncHealth.missingPayables(), syncHealth.oldestMissingSeconds(),
                syncHealth.cursorLagSeconds());
        return new Queues(overdue.stream().map(DeliveryFinanceAdminService::view).toList(),
                exhausted.stream().map(DeliveryFinanceAdminService::view).toList(),
                ambiguous.stream().map(DeliveryFinanceAdminService::view).toList(),
                unreconciled.stream().map(DeliveryFinanceAdminService::view).toList(),
                manual.stream().map(DeliveryFinanceAdminService::view).toList(),
                refundQueues.overdue().stream().map(DeliveryFinanceAdminService::view).toList(),
                refundQueues.exhausted().stream().map(DeliveryFinanceAdminService::view).toList(),
                refundQueues.ambiguous().stream().map(DeliveryFinanceAdminService::view).toList(),
                refundQueues.pending().stream().map(DeliveryFinanceAdminService::view).toList(), totals);
    }

    // ── payable actions ─────────────────────────────────────────────────

    @Transactional
    public PayableView markPaid(Long actorId, Long id, BigDecimal amount, String reference, Instant paidAt,
                                String note) {
        operate(actorId);
        var p = payableService.markPaid(id, amount, requireText(reference, "Give the bank or invoice reference."),
                paidAt, actor(actorId), note);
        audited(actorId, "COURIER_PAYABLE_MARKED_PAID", p, note);
        return view(p);
    }

    @Transactional
    public PayableView retry(Long actorId, Long id, String note) {
        operate(actorId);
        var p = payableService.retry(id, actor(actorId), requireText(note, "Say why."));
        audited(actorId, "COURIER_PAYABLE_RETRIED", p, note);
        return view(p);
    }

    @Transactional
    public PayableView voidPayable(Long actorId, Long id, String reason) {
        operate(actorId);
        var p = payableService.voidPayable(id, actor(actorId), requireText(reason, "Say why nothing is owed."));
        audited(actorId, "COURIER_PAYABLE_VOIDED", p, reason);
        return view(p);
    }

    @Transactional
    public PayableView recordCancellationFee(Long actorId, Long id, BigDecimal fee, String note) {
        operate(actorId);
        var p = payableService.recordCancellationFee(id, fee, actor(actorId), requireText(note, "Say where the fee comes from."));
        audited(actorId, "COURIER_CANCELLATION_FEE_RECORDED", p, note);
        return view(p);
    }

    @Transactional
    public PayableView recordProviderCredit(Long actorId, Long id, BigDecimal credit, String note) {
        operate(actorId);
        var p = payableService.recordProviderCredit(id, credit, actor(actorId), requireText(note, "Say where the credit comes from."));
        audited(actorId, "COURIER_PROVIDER_CREDIT_RECORDED", p, note);
        return view(p);
    }

    @Transactional
    public PayableView setFinalCost(Long actorId, Long id, BigDecimal finalCost, String note) {
        operate(actorId);
        var p = payableService.setFinalCost(id, finalCost, actor(actorId), requireText(note, "Say where the figure comes from."));
        audited(actorId, "COURIER_FINAL_COST_SET", p, note);
        return view(p);
    }

    @Transactional
    public PayableView resolveAmbiguous(Long actorId, Long id, boolean paid, String reference, String note) {
        operate(actorId);
        if (paid) {
            requireText(reference, "Say where the payment can be seen.");
        }
        var p = payableService.resolveAmbiguous(id, paid, reference, actor(actorId), requireText(note, "Say how you found out."));
        audited(actorId, "COURIER_AMBIGUOUS_RESOLVED", p, note);
        return view(p);
    }

    @Transactional
    public PayableView resolveReconciliation(Long actorId, Long id, String note) {
        operate(actorId);
        var p = payableService.resolveReconciliation(id, actor(actorId), note);
        audited(actorId, "COURIER_RECONCILIATION_RESOLVED", p, note);
        return view(p);
    }

    // ── payable sync repair ─────────────────────────────────────────────

    /**
     * Create the payables of courier bookings that have none, one deterministic page at a time. Audited. Call again with
     * the returned {@code nextAfterId} until {@code remaining} is zero.
     */
    public CourierPayableSyncService.Backfill backfillPayables(Long actorId, String sourceType, long afterId, int limit,
                                                               String note) {
        operate(actorId);
        var result = syncService.backfill(sourceType, afterId, limit);
        audit.record(actorId, null, "COURIER_PAYABLE_BACKFILL", "COURIER_PAYABLE", null, null, sourceType,
                requireText(note, "Say why.") + " (processed " + result.processed() + ", failed " + result.failed()
                        + ", remaining " + result.remaining() + ")", "ADMIN");
        return result;
    }

    /** Walk every delivery of a source again from the start. Idempotent; for after a defect in the sync is fixed. */
    public void rewindPayableSync(Long actorId, String sourceType, String note) {
        operate(actorId);
        syncService.rewind(sourceType);
        audit.record(actorId, null, "COURIER_PAYABLE_SYNC_REWOUND", "COURIER_PAYABLE", null, null, sourceType,
                requireText(note, "Say why."), "ADMIN");
    }

    // ── gateway refund actions ──────────────────────────────────────────

    @Transactional
    public RefundView retryRefund(Long actorId, Long id, String note) {
        operate(actorId);
        var g = gatewayRefundService.retry(id);
        audit.record(actorId, null, "GATEWAY_REFUND_RETRIED", "GATEWAY_REFUND", id, null, g.getStatus(),
                requireText(note, "Say why."), "ADMIN");
        return view(g);
    }

    @Transactional
    public RefundView resolveRefundExternally(Long actorId, Long id, String providerRefundId, String note) {
        operate(actorId);
        var g = gatewayRefundService.resolveExternally(id, providerRefundId);
        audit.record(actorId, null, "GATEWAY_REFUND_RESOLVED_EXTERNALLY", "GATEWAY_REFUND", id, null, g.getStatus(),
                requireText(note, "Say how you confirmed it.") + " (gateway refund " + providerRefundId + ")", "ADMIN");
        return view(g);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void inspect(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_FINANCE_INSPECT, ScopeType.PLATFORM, null);
    }

    private void operate(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_FINANCE_OPERATE, ScopeType.PLATFORM, null);
    }

    private void audited(Long actorId, String action, CourierPayable p, String reason) {
        audit.record(actorId, null, action, "COURIER_PAYABLE", p.getId(), null, p.getState(), reason, "ADMIN");
    }

    private static String actor(Long actorId) {
        return "ops:" + actorId;
    }

    private static String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, message);
        }
        return v.trim();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, QUEUE_LIMIT));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static PayableView view(CourierPayable p) {
        return new PayableView(p.getId(), p.getSourceType(), p.getDeliveryId(), p.getAttemptId(), p.getSupplierOrderId(),
                p.getOutletId(), p.getSupplierStoreId(), p.getProviderCode(), p.getProviderDeliveryId(),
                p.getQuotedCost(), p.getFinalCost(), p.isFinalCostManual(), p.getCancellationFee(),
                p.getProviderCredit(), p.getAmountDue(), p.getAmountPaid(), p.getBuyerContribution(),
                p.getSupplierContribution(), p.getPlatformFunded(), p.getState(), p.getDueReason(), p.getDueAt(),
                p.getPaymentRail(), p.getPaymentReference(), p.getPaidAt(), p.getAttemptCount(), p.getLastError(),
                p.getNextRetryAt(), p.getReconciliationState(), p.getReconciliationNote(), p.getCreatedAt(),
                p.getUpdatedAt());
    }

    private static AttemptView view(CourierPaymentAttempt a) {
        return new AttemptView(a.getAttemptNumber(), a.getRail(), a.getAmount(), a.getStatus(), a.getReference(),
                a.getError(), a.getStartedAt(), a.getEndedAt());
    }

    private static EventView view(CourierPayableEvent e) {
        return new EventView(e.getEventType(), e.getFromState(), e.getToState(), e.getAmount(), e.getActor(),
                e.getReference(), e.getDetail(), e.getCreatedAt());
    }

    static RefundView view(GatewayRefund g) {
        return new RefundView(g.getId(), g.getSourceType(), g.getSourceId(), g.getPurpose(), g.getProviderPaymentId(),
                g.getCapturedAmount(), g.getAmount(), g.getReason(), g.getStatus(), g.getProviderRefundId(),
                g.getAttemptCount(), g.getLookupCount(), g.getLastError(), g.getNextRetryAt(), g.getRequestedAt(),
                g.getCompletedAt());
    }
}
