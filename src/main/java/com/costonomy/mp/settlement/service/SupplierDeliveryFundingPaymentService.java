package com.costonomy.mp.settlement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.service.GatewayRefundService;
import com.costonomy.mp.settlement.domain.SupplierDeduction;
import com.costonomy.mp.settlement.repository.SupplierDeductionRepository;
import com.costonomy.mp.settlement.web.dto.SettlementDtos.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Direct payment collection for supplier-funded delivery fees (D-203).
 *
 * <p>When a supplier funds Costonomy delivery for an order (e.g. credit orders or
 * when there are no upcoming settlements to deduct from), they can settle the
 * delivery fee directly through the payment gateway (UPI/Razorpay).
 *
 * <p><b>Guarantees:</b>
 * <ul>
 *   <li><b>No provider call holds a database transaction:</b> Authorization and
 *       verification calls run outside open transactions (D-099).</li>
 *   <li><b>No double deduction:</b> Marking a deduction as {@code PAID_DIRECTLY}
 *       ensures it is never picked up by {@link SupplierRefundLedger#applyPending}
 *       during subsequent settlement cycles.</li>
 *   <li><b>Verification before recording:</b> Signatures and provider payment status
 *       are verified against Razorpay before any status update commits.</li>
 * </ul>
 */
@Service
@Slf4j
public class SupplierDeliveryFundingPaymentService {

    private final SupplierDeductionRepository deductions;
    private final PaymentProvider paymentProvider;
    private final GatewayRefundService gatewayRefunds;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SupplierDeliveryFundingPaymentService(SupplierDeductionRepository deductions,
                                                 PaymentProvider paymentProvider,
                                                 GatewayRefundService gatewayRefunds,
                                                 AccessControlService accessControl,
                                                 AuditService auditService,
                                                 JdbcTemplate jdbc,
                                                 PlatformTransactionManager transactionManager) {
        this.deductions = deductions;
        this.paymentProvider = paymentProvider;
        this.gatewayRefunds = gatewayRefunds;
        this.accessControl = accessControl;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Pending delivery funding deductions awaiting settlement or payment for this store.
     */
    @Transactional(readOnly = true)
    public List<PendingDeliveryFundingResponse> listPending(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        return jdbc.query("""
                select d.id, d.supplier_order_id, so.order_number, d.delivery_id, d.amount,
                       d.reason, d.status, coalesce(so.payment_method, 'PREPAID'),
                       d.provider_order_id, d.created_at
                  from supplier_deduction d
                  join supplier_order so on so.id = d.supplier_order_id
                 where d.supplier_store_id = ?
                   and d.status = 'PENDING'
                   and d.kind = 'DELIVERY_FUNDING'
                 order by d.id desc
                """, (rs, rowNum) -> new PendingDeliveryFundingResponse(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getObject(4) != null ? rs.getLong(4) : null,
                rs.getBigDecimal(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getTimestamp(10).toInstant()
        ), storeId);
    }

    /**
     * Every delivery-funding line for this store, in any state, newest first. Bounded: the supplier asks for a page.
     * Pending lines come with what to do about them; the others say where the money went.
     */
    @Transactional(readOnly = true)
    public List<DeliveryFundingLine> listHistory(Long actorId, Long storeId, String status, int page, int size) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");
        int limit = Math.max(1, Math.min(size, 100));
        return jdbc.query("""
                select d.id, d.kind, d.supplier_order_id, so.order_number, d.delivery_id, d.amount, d.status,
                       coalesce(so.payment_method, 'PREPAID'), d.settlement_id, d.applied_at, d.paid_at, d.reason,
                       d.created_at
                  from supplier_deduction d
                  join supplier_order so on so.id = d.supplier_order_id
                 where d.supplier_store_id = ?
                   and d.kind in ('DELIVERY_FUNDING', 'DELIVERY_FUNDING_CREDIT')
                   and (? is null or d.status = ?)
                 order by d.id desc
                 limit ? offset ?
                """, (rs, rowNum) -> {
            String kind = rs.getString(2);
            String st = rs.getString(7);
            String method = rs.getString(8);
            return new DeliveryFundingLine(rs.getLong(1), kind, rs.getLong(3), rs.getString(4),
                    rs.getObject(5) != null ? rs.getLong(5) : null, rs.getBigDecimal(6), st,
                    outcome(kind, st), nextAction(kind, st, method), method,
                    rs.getObject(9) != null ? rs.getLong(9) : null,
                    rs.getTimestamp(10) != null ? rs.getTimestamp(10).toInstant() : null,
                    rs.getTimestamp(11) != null ? rs.getTimestamp(11).toInstant() : null, rs.getString(12),
                    rs.getTimestamp(13).toInstant());
        }, storeId, blank(status), blank(status), limit, (long) Math.max(page, 0) * limit);
    }

    @Transactional(readOnly = true)
    public DeliveryFundingSummary summary(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");
        return jdbc.queryForObject("""
                select
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING' and status = 'PENDING' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING' and status = 'PAID_DIRECTLY' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING' and status = 'APPLIED' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING' and status = 'WAIVED' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING_CREDIT' and status = 'PENDING' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING_CREDIT' and status = 'APPLIED' then amount end), 0),
                  coalesce(sum(case when kind = 'DELIVERY_FUNDING' and status = 'PENDING' then 1 end), 0)
                  from supplier_deduction
                 where supplier_store_id = ? and kind in ('DELIVERY_FUNDING', 'DELIVERY_FUNDING_CREDIT')
                """, (rs, n) -> new DeliveryFundingSummary(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getLong(7)), storeId);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String outcome(String kind, String status) {
        boolean credit = SupplierDeduction.KIND_DELIVERY_FUNDING_CREDIT.equals(kind);
        return switch (status) {
            case SupplierDeduction.PENDING -> credit ? "CREDIT_WAITING_FOR_SETTLEMENT" : "OWED";
            case SupplierDeduction.APPLIED -> credit ? "CREDITED_IN_SETTLEMENT" : "TAKEN_FROM_SETTLEMENT";
            case SupplierDeduction.PAID_DIRECTLY -> "PAID_DIRECTLY";
            case SupplierDeduction.WAIVED -> "WAIVED";
            default -> status;
        };
    }

    /** What the supplier can do. A credit order has no payout to take it from, so paying directly is the way. */
    private static String nextAction(String kind, String status, String paymentMethod) {
        if (!SupplierDeduction.PENDING.equals(status)) {
            return "NONE";
        }
        if (SupplierDeduction.KIND_DELIVERY_FUNDING_CREDIT.equals(kind)) {
            return "NONE";
        }
        return "CREDIT".equalsIgnoreCase(paymentMethod) ? "PAY_DIRECTLY" : "PAY_DIRECTLY_OR_DEDUCTED_FROM_NEXT_SETTLEMENT";
    }

    /**
     * Start direct payment for a delivery funding deduction: opens a Razorpay order
     * with auto-capture for the exact amount.
     */
    public InitiateDeliveryFundingPaymentResponse initiatePayment(Long actorId, Long storeId,
                                                                 Long deductionId, String clientKey) {
        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var deduction = deductions.findById(deductionId)
                .orElseThrow(() -> new NotFoundException("SupplierDeduction", deductionId));

        if (!deduction.getSupplierStoreId().equals(storeId)) {
            throw new NotFoundException("SupplierDeduction", deductionId);
        }
        if (!SupplierDeduction.KIND_DELIVERY_FUNDING.equals(deduction.getKind())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This deduction is not for delivery funding.");
        }
        if (SupplierDeduction.PAID_DIRECTLY.equals(deduction.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This delivery fee has already been paid directly.");
        }
        if (SupplierDeduction.APPLIED.equals(deduction.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This delivery fee has already been deducted from a settlement.");
        }
        if (!SupplierDeduction.PENDING.equals(deduction.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This deduction is in status " + deduction.getStatus());
        }

        BigDecimal amount = deduction.getAmount().setScale(2, RoundingMode.HALF_UP);

        // If checkout was already opened and is still pending, replay it idempotently.
        if (deduction.getProviderOrderId() != null) {
            // The supplier is about to pay again: keep the settlement from taking this charge meanwhile.
            jdbc.update("update supplier_deduction set checkout_opened_at = now(6) where id = ? and status = 'PENDING'",
                    deductionId);
            return new InitiateDeliveryFundingPaymentResponse(
                    deduction.getId(),
                    deduction.getProviderOrderId(),
                    paymentProvider.createAuthorizationPublicKey(),
                    amount,
                    "INR");
        }

        String referenceId = "del-funding-" + deduction.getId();
        String idempotencyKey = (clientKey != null && !clientKey.isBlank())
                ? ("store-" + storeId + "-del-" + deduction.getId() + "-" + clientKey)
                : referenceId;

        PaymentProvider.AuthorizationIntent intent;
        try {
            intent = paymentProvider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                    referenceId, amount, "INR",
                    "Delivery funding for order " + deduction.getSupplierOrderId(),
                    idempotencyKey, true));
        } catch (RuntimeException ex) {
            log.warn("Could not open checkout for delivery funding deduction {}: {}", deductionId, ex.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                    "We couldn't start checkout. Nothing was charged. Please try again.");
        }

        // Attach providerOrderId to deduction in a quick transaction.
        tx.executeWithoutResult(status -> {
            var row = deductions.findById(deductionId).orElseThrow();
            row.setProviderOrderId(intent.providerOrderId());
            row.setCheckoutOpenedAt(Instant.now());
            deductions.save(row);
        });

        log.info("Delivery funding payment initiated for deduction {} with Razorpay order {}",
                deduction.getId(), intent.providerOrderId());

        return new InitiateDeliveryFundingPaymentResponse(
                deduction.getId(),
                intent.providerOrderId(),
                intent.publicKey(),
                amount,
                "INR");
    }

    /**
     * Confirm a direct payment after checkout finishes: verifies signature, fetches
     * payment facts from the provider, advances status to {@code PAID_DIRECTLY},
     * and logs the settlement to the delivery ledger.
     */
    public ConfirmDeliveryFundingPaymentResponse confirmPayment(Long actorId, Long storeId,
                                                               Long deductionId,
                                                               ConfirmDeliveryFundingPaymentRequest request) {
        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        if (request.paymentId() == null || request.paymentId().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Payment ID is required.");
        }
        if (request.signature() == null || request.signature().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Checkout signature is required.");
        }

        var deduction = deductions.findById(deductionId)
                .orElseThrow(() -> new NotFoundException("SupplierDeduction", deductionId));

        if (!deduction.getSupplierStoreId().equals(storeId)) {
            throw new NotFoundException("SupplierDeduction", deductionId);
        }
        if (!SupplierDeduction.KIND_DELIVERY_FUNDING.equals(deduction.getKind())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This deduction is not for delivery funding.");
        }

        // Idempotent retry: already paid with the same payment ID.
        if (SupplierDeduction.PAID_DIRECTLY.equals(deduction.getStatus())) {
            if (request.paymentId().equals(deduction.getPaymentId())) {
                return new ConfirmDeliveryFundingPaymentResponse(
                        deduction.getId(),
                        deduction.getStatus(),
                        deduction.getPaymentId(),
                        deduction.getAmount(),
                        deduction.getPaidAt());
            }
            // Paid with another payment: this one is verified below and, if it is real, refunded.
        }
        // Already deducted from a settlement: the same, a real payment for it is verified below and refunded. Rejecting
        // here, before looking at the payment, would leave the money captured with nothing to say where it went.
        if (!SupplierDeduction.PENDING.equals(deduction.getStatus())
                && !SupplierDeduction.APPLIED.equals(deduction.getStatus())
                && !SupplierDeduction.PAID_DIRECTLY.equals(deduction.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This deduction is in status " + deduction.getStatus());
        }
        if (deduction.getProviderOrderId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Checkout was never opened for this delivery fee.");
        }

        try (var trace = TraceScope.of("supplier_deduction", deductionId, "store", storeId,
                "rzp_order", deduction.getProviderOrderId(), "rzp_payment", request.paymentId())) {

            // Step 1: verify checkout signature.
            if (!paymentProvider.verifyCheckoutSignature(deduction.getProviderOrderId(),
                    request.paymentId(), request.signature())) {
                auditService.record(actorId, null, "DELIVERY_FUNDING_CONFIRM_REJECTED",
                        "SUPPLIER_DEDUCTION", deductionId,
                        deduction.getStatus(), deduction.getStatus(),
                        "Checkout signature did not verify for payment " + request.paymentId(), "API");
                log.warn("Delivery funding deduction {} confirm rejected: signature did not verify", deductionId);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "We couldn't verify that payment. Nothing was charged.");
            }

            // Step 2: fetch payment from provider outside database transaction.
            PaymentProvider.ProviderPayment payment;
            try {
                payment = paymentProvider.fetchPayment(request.paymentId());
            } catch (PaymentProviderException ex) {
                log.warn("Delivery funding deduction {} confirm named a payment refused: {}", deductionId, ex.getMessage());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "We couldn't find that payment.");
            }

            if (payment.status() != PaymentProvider.ProviderPaymentStatus.CAPTURED) {
                log.warn("Delivery funding deduction {} payment {} is {}, not CAPTURED",
                        deductionId, request.paymentId(), payment.status());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "That payment didn't go through. Nothing was charged.");
            }

            // Not this checkout's payment: nothing of ours to refund, and a refund here would let anyone name any payment id.
            if (!deduction.getProviderOrderId().equals(payment.providerOrderId())) {
                log.error("Delivery funding deduction {} payment {} belongs to order {}, not {}",
                        deductionId, request.paymentId(), payment.providerOrderId(), deduction.getProviderOrderId());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That payment does not match this checkout order.");
            }

            BigDecimal expectedAmount = deduction.getAmount().setScale(2, RoundingMode.HALF_UP);
            if (payment.capturedAmount() == null || payment.capturedAmount().compareTo(expectedAmount) != 0) {
                log.error("Delivery funding deduction {} payment {} captured {} but deduction is for {}; refunding it",
                        deductionId, request.paymentId(), payment.capturedAmount(), expectedAmount);
                boolean back = payment.capturedAmount() != null
                        && refundUnusablePayment(deductionId, request.paymentId(), payment.capturedAmount());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, back
                        ? "Payment amount does not match the delivery fee, so it has been refunded."
                        : "Payment amount does not match the delivery fee. We are refunding it.");
            }

            // Step 3: record the payment, or send it back if the charge can no longer take it.
            var recorded = recordDirectPayment(deductionId, deduction.getDeliveryId(), deduction.getAmount(),
                    request.paymentId(), actorId, "API");
            if (recorded.outcome() == Outcome.PAID || recorded.outcome() == Outcome.SAME_PAYMENT) {
                return new ConfirmDeliveryFundingPaymentResponse(
                        deductionId,
                        SupplierDeduction.PAID_DIRECTLY,
                        request.paymentId(),
                        deduction.getAmount(),
                        recorded.paidAt());
            }
            boolean back = refundUnusablePayment(deductionId, request.paymentId(), payment.capturedAmount());
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, recorded.outcome() == Outcome.TAKEN_BY_SETTLEMENT
                    ? (back ? "This delivery fee was already deducted from your payout, so your payment has been refunded to where it came from."
                            : "This delivery fee was already deducted from your payout. We are refunding your payment.")
                    : (back ? "This delivery fee was already paid with another payment, so this one has been refunded."
                            : "This delivery fee was already paid with another payment. We are refunding this one."));
        }
    }

    private enum Outcome { PAID, SAME_PAYMENT, TAKEN_BY_SETTLEMENT, PAID_ELSEWHERE }

    private record Recorded(Outcome outcome, Instant paidAt) {}

    /**
     * Mark the charge paid, once. A compare-and-set on {@code PENDING}, so a settlement that took the charge first (or a
     * second payment) leaves it untouched and the outcome says which. No provider call inside: a refund, if one is
     * needed, is the caller's to make afterwards (D-099).
     */
    private Recorded recordDirectPayment(Long deductionId, Long deliveryId, BigDecimal amount, String paymentId,
                                         Long actorId, String via) {
        Instant paidAt = Instant.now();
        return tx.execute(status -> {
            int updated = jdbc.update("""
                    update supplier_deduction
                       set status = ?, payment_id = ?, paid_at = ?, updated_at = now(6), version = version + 1
                     where id = ? and status = ?
                    """, SupplierDeduction.PAID_DIRECTLY, paymentId,
                    Timestamp.from(paidAt), deductionId, SupplierDeduction.PENDING);

            if (updated == 0) {
                var current = deductions.findById(deductionId).orElseThrow();
                if (SupplierDeduction.PAID_DIRECTLY.equals(current.getStatus())) {
                    return paymentId.equals(current.getPaymentId())
                            ? new Recorded(Outcome.SAME_PAYMENT, current.getPaidAt())
                            : new Recorded(Outcome.PAID_ELSEWHERE, null);
                }
                return new Recorded(Outcome.TAKEN_BY_SETTLEMENT, null);
            }

            // Not in the courier's rows: this is the supplier paying Costonomy, so a sum of what couriers cost by
            // provider must not see it. Its own provider code and entry type keep it out of those totals.
            if (deliveryId != null) {
                jdbc.update("""
                        insert into delivery_ledger
                            (delivery_id, provider_code, provider_delivery_id, entry_type, amount, currency, description, created_at)
                        select d.id, 'SUPPLIER', d.provider_delivery_id, 'SUPPLIER_PAID', ?, 'INR', ?, now(6)
                          from delivery d
                         where d.id = ?
                        """, amount,
                        "Supplier paid their share of the delivery directly via " + paymentProvider.name()
                                + " (" + paymentId + ")",
                        deliveryId);
            }

            auditService.record(actorId, null, "DELIVERY_FUNDING_PAID_DIRECTLY",
                    "SUPPLIER_DEDUCTION", deductionId,
                    SupplierDeduction.PENDING, SupplierDeduction.PAID_DIRECTLY,
                    "Paid ₹%s via %s (%s)".formatted(amount.toPlainString(), paymentId, via),
                    "WEBHOOK".equals(via) ? "WEBHOOK" : "API");
            log.info("Delivery funding deduction {} paid directly via payment {} ({})", deductionId, paymentId, via);
            return new Recorded(Outcome.PAID, paidAt);
        });
    }

    /**
     * Send a captured payment back to its source because the charge it was for can no longer take it. Outside any
     * transaction. If the provider cannot do it now, nothing is marked and the sweep finds it again.
     */
    private boolean refundUnusablePayment(Long deductionId, String paymentId, BigDecimal capturedAmount) {
        String key = "del-funding-unusable-" + paymentId;
        // Recorded first, so an unknown outcome is found again and never sent twice; then sent with no transaction open.
        Long refundId = tx.execute(s -> gatewayRefunds.record(new GatewayRefundService.Request(
                GatewayRefund.SUPPLIER_DELIVERY_FUNDING, deductionId, "UNUSABLE_PAYMENT", paymentId,
                capturedAmount, capturedAmount,
                "The charge could not take this payment: already deducted from a settlement, paid twice, or the wrong amount",
                key)).getId());
        gatewayRefunds.send(refundId);
        boolean done = gatewayRefunds.find(key).map(r -> GatewayRefund.SUCCEEDED.equals(r.getStatus())).orElse(false);
        if (done) {
            auditService.record(null, null, "DELIVERY_FUNDING_PAYMENT_REFUNDED", "SUPPLIER_DEDUCTION", deductionId,
                    null, null, "Payment %s refunded: the charge could not take it".formatted(paymentId), "SYSTEM");
        }
        return done;
    }

    /**
     * Reconciles a payment captured at the gateway for a delivery funding checkout (webhook or sweep): marks the charge
     * paid, or refunds the payment if the charge can no longer take it (the settlement got there first, or it was paid
     * twice). See {@link #recordDirectPayment}.
     *
     * @return true if the event was dealt with (recorded, already recorded, or refunded or queued for refund)
     */
    public boolean reconcileWebhookPayment(String providerOrderId, String providerPaymentId) {
        if (providerOrderId == null && providerPaymentId == null) {
            return false;
        }
        SupplierDeduction d = null;
        if (providerOrderId != null) {
            d = deductions.findByProviderOrderId(providerOrderId).orElse(null);
        }
        if (d == null && providerPaymentId != null) {
            d = deductions.findByPaymentId(providerPaymentId).orElse(null);
        }
        if (d == null || providerPaymentId == null) {
            return false;
        }
        if (SupplierDeduction.PAID_DIRECTLY.equals(d.getStatus()) && providerPaymentId.equals(d.getPaymentId())) {
            return true;
        }
        if (d.getGatewayRefundedAt() != null) {
            return true;
        }

        PaymentProvider.ProviderPayment payment;
        try {
            payment = paymentProvider.fetchPayment(providerPaymentId);
        } catch (Exception ex) {
            log.warn("Could not fetch payment {} for deduction {}: {}", providerPaymentId, d.getId(), ex.getMessage());
            return false;
        }
        if (payment.status() != PaymentProvider.ProviderPaymentStatus.CAPTURED) {
            log.warn("Payment {} for deduction {} is in status {}, not CAPTURED",
                    providerPaymentId, d.getId(), payment.status());
            return false;
        }
        // Only this checkout's own payments: a refund must never follow a payment id that belongs to something else.
        if (d.getProviderOrderId() == null || !d.getProviderOrderId().equals(payment.providerOrderId())) {
            return false;
        }

        BigDecimal expectedAmount = d.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (payment.capturedAmount() == null || payment.capturedAmount().compareTo(expectedAmount) != 0) {
            log.error("Payment {} captured {} does not match deduction {} expected {}; refunding it",
                    providerPaymentId, payment.capturedAmount(), d.getId(), expectedAmount);
            refundUnusablePayment(d.getId(), providerPaymentId, payment.capturedAmount());
            return true;
        }

        var recorded = recordDirectPayment(d.getId(), d.getDeliveryId(), d.getAmount(), providerPaymentId, null, "WEBHOOK");
        if (recorded.outcome() == Outcome.TAKEN_BY_SETTLEMENT || recorded.outcome() == Outcome.PAID_ELSEWHERE) {
            refundUnusablePayment(d.getId(), providerPaymentId, payment.capturedAmount());
        }
        return true;
    }

    /**
     * Sweeps pending delivery funding deductions that have opened checkout orders and checks
     * if the provider captured payment, recovering lost client callbacks or missed webhooks (Doc 46).
     */
    public int sweepStaleGatewayPayments() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofSeconds(30));
        Instant createdAfter = now.minus(Duration.ofDays(1));
        var stale = deductions.findStaleGatewayDeductions(staleBefore, createdAfter, 50);

        int recovered = 0;
        for (var d : stale) {
            if (d.getProviderOrderId() == null) {
                continue;
            }
            try {
                var paymentOpt = paymentProvider.findPaymentForOrder(d.getProviderOrderId());
                if (paymentOpt.isPresent()) {
                    var payment = paymentOpt.get();
                    if (payment.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED) {
                        boolean ok = reconcileWebhookPayment(d.getProviderOrderId(), payment.providerPaymentId());
                        if (ok) {
                            recovered++;
                            log.info("Sweep reconciled captured delivery funding payment: deduction={}, order={}, payment={}",
                                    d.getId(), d.getProviderOrderId(), payment.providerPaymentId());
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to sweep payment for deduction {}: {}", d.getId(), ex.getMessage());
            }
        }
        return recovered;
    }
}
