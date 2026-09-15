package com.costonomy.mp.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operations shapes.
 *
 * <p>Deliberately <b>not</b> the tenant DTOs. Doc 09 §17: operations is a separate
 * consumer, and reusing a restaurant's order shape would tie the two together —
 * every field an operator needs would have to be added to what a restaurant sees,
 * or hidden behind a flag that someone eventually gets wrong. These carry what an
 * investigation needs and nothing a tenant screen does.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // ── Suppliers ────────────────────────────────────────────────────────

    public record SupplierSummary(
            Long id,
            String legalName,
            String displayName,
            String gstin,
            String lifecycleStatus,
            String verificationStatus,
            int storeCount,
            int activeSkuCount,
            Instant createdAt) {
    }

    public record SupplierDetail(
            SupplierSummary supplier,
            List<StoreSummary> stores) {
    }

    public record StoreSummary(
            Long id,
            String name,
            String city,
            String status,
            Integer responseSlaSeconds,
            int activeSkuCount,
            /** Null where nobody has rated this store. Never a default of three. */
            BigDecimal averageRating,
            int ratingCount) {
    }

    public record SuspendSupplierRequest(
            @NotBlank(message = "Give a reason") @Size(max = 500) String reason) {
    }

    // ── Orders ───────────────────────────────────────────────────────────

    public record OrderSummary(
            Long id,
            String orderNumber,
            String status,
            Long outletId,
            String outletName,
            String restaurantName,
            Long supplierStoreId,
            String supplierName,
            BigDecimal totalAmount,
            BigDecimal acceptedAmount,
            String paymentMethod,
            String paymentStatus,
            Instant acceptanceDeadline,
            Instant createdAt) {
    }

    /**
     * Everything that happened to one order, from every module.
     *
     * <p>The single most useful screen an operator has: a restaurant says "where is
     * my order" and the answer is usually visible in the ordering of these entries
     * — the payment that authorised late, the courier that cancelled, the
     * acceptance that raced a timeout.
     */
    public record OrderTimeline(
            OrderSummary order,
            PaymentDetail payment,
            DeliveryDetail delivery,
            List<TimelineEntry> entries) {
    }

    /**
     * @param source which module recorded it — AUDIT, DELIVERY or DISPUTE. Kept so
     *               an operator can tell a state change from a courier's report.
     */
    public record TimelineEntry(
            Instant at,
            String source,
            String action,
            String detail,
            Long actorId) {
    }

    // ── Payments ─────────────────────────────────────────────────────────

    public record PaymentDetail(
            Long id,
            Long supplierOrderId,
            String status,
            String provider,
            String providerPaymentId,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            BigDecimal releasedAmount,
            String failureCode,
            String failureReason,
            Instant reconciledAt,
            List<TransactionSummary> transactions,
            List<RefundSummary> refunds) {
    }

    public record TransactionSummary(
            String type,
            BigDecimal amount,
            String status,
            String providerReference,
            Instant createdAt) {
    }

    public record RefundSummary(
            Long id,
            BigDecimal amount,
            String reason,
            String status,
            Instant createdAt) {
    }

    // ── Delivery ─────────────────────────────────────────────────────────

    /**
     * @param attempts every courier asked, in order. This is the point of the
     *                 endpoint: doc 06 §7 requires attempts to stay auditable, and
     *                 the delivery row alone only shows the last one.
     */
    public record DeliveryDetail(
            Long id,
            Long supplierOrderId,
            String mode,
            String status,
            /** Visible here and nowhere a restaurant can see it (doc 06 §10). */
            String providerCode,
            String providerDeliveryId,
            BigDecimal fee,
            Integer attemptCount,
            String failureCode,
            String failureReason,
            Instant requestedAt,
            Instant deliveredAt,
            List<DeliveryAttemptSummary> attempts,
            List<DeliveryQuoteSummary> quotes) {
    }

    public record DeliveryAttemptSummary(
            Integer attemptNumber,
            String providerCode,
            String attemptType,
            String outcome,
            String failureReason,
            Instant startedAt,
            Instant endedAt) {
    }

    /** Internal by definition — see doc 06 §4. There is no tenant equivalent. */
    public record DeliveryQuoteSummary(
            String providerCode,
            String status,
            BigDecimal amount,
            Integer etaMinutes,
            boolean selected,
            String failureReason) {
    }

    // ── Credit ───────────────────────────────────────────────────────────

    public record CreditExposureSummary(
            Long agreementId,
            Long outletId,
            String outletName,
            Long supplierStoreId,
            String supplierName,
            String status,
            BigDecimal approvedLimit,
            BigDecimal reserved,
            BigDecimal utilized,
            BigDecimal available,
            BigDecimal due,
            BigDecimal overdue) {
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    public record DisputeSummary(
            Long id,
            String disputeNumber,
            Long supplierOrderId,
            String orderNumber,
            String category,
            String status,
            BigDecimal claimedAmount,
            String outletName,
            String supplierName,
            Instant createdAt) {
    }

    // ── Audit ────────────────────────────────────────────────────────────

    public record AuditEntry(
            Long id,
            Long actorId,
            String action,
            String entityType,
            Long entityId,
            String oldState,
            String newState,
            String reason,
            String requestId,
            String source,
            Instant createdAt) {
    }

    // ── Configuration ────────────────────────────────────────────────────

    /**
     * @param effectiveFrom doc 09 §10: financially relevant configuration is
     *                      effective-dated, so a settlement can be reproduced with
     *                      the rate that applied at the time
     */
    public record ConfigEntry(
            String key,
            String value,
            String valueType,
            Integer configVersion,
            String description,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            Long updatedBy) {
    }

    public record UpdateConfigRequest(
            @NotBlank(message = "Which key?") @Size(max = 120) String key,
            @NotNull(message = "Give a value") @Size(max = 1000) String value,
            @NotBlank(message = "Say why") @Size(max = 500) String reason,
            /** Omit for "now". Future-dated where a rate must not change retroactively. */
            Instant effectiveFrom) {
    }

    // ── Dashboard ────────────────────────────────────────────────────────

    /**
     * Doc 08 §11's operational figures.
     *
     * <p>Every one is computed from its own denominator and is <b>null where there
     * is nothing to measure</b> — the same rule the ranking signals follow (doc 07
     * §4). A dashboard that shows 100% acceptance because no orders were placed is
     * worse than one that shows a dash.
     */
    public record OperationsDashboard(
            Instant generatedAt,
            int windowDays,
            OrderMetrics orders,
            PaymentMetrics payments,
            DeliveryMetrics delivery,
            CreditMetrics credit,
            DisputeMetrics disputes) {
    }

    public record OrderMetrics(
            long activeOrders,
            long awaitingAcceptance,
            long placedInWindow,
            BigDecimal acceptanceRate,
            BigDecimal expiryRate,
            BigDecimal fillRate) {
    }

    public record PaymentMetrics(
            long authorized,
            long captured,
            long failed,
            long awaitingReconciliation,
            BigDecimal capturedValue) {
    }

    public record DeliveryMetrics(
            long inFlight,
            long deliveredInWindow,
            long failedInWindow,
            BigDecimal onTimeRate,
            BigDecimal reassignmentRate) {
    }

    public record CreditMetrics(
            long activeAgreements,
            BigDecimal totalApproved,
            BigDecimal totalUtilized,
            BigDecimal totalOverdue,
            long overdueInvoices,
            long suspendedAgreements) {
    }

    public record DisputeMetrics(
            long open,
            long openedInWindow,
            long resolvedInWindow,
            Map<String, Long> byCategory) {
    }
}
