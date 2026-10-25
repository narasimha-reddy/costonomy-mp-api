package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryLedgerEntry;
import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import com.costonomy.mp.delivery.repository.DeliveryLedgerRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderMetricsRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderStatsRepository;
import com.costonomy.mp.delivery.repository.DeliveryQuoteRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Operations &amp; Admin service for delivery inspection and carrier management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDeliveryService {

    private final AccessControlService accessControl;
    private final DeliveryRepository deliveries;
    private final DeliveryLedgerRepository ledgerRepository;
    private final DeliveryProviderStatsRepository statsRepository;
    private final DeliveryProviderMetricsRepository metricsRepository;
    private final DeliveryQuoteRepository quotes;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryRecoveryService recoveryService;
    private final DeliveryWaterfallService waterfallService;
    private final DeliveryService deliveryService;
    private final FoodDeliveryQueryService foodDeliveryQueryService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<DeliveryDtos.DeliveryLedgerResponse> getLedger(Long actorId, Long deliveryId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        if (!deliveries.existsById(deliveryId)) {
            throw new NotFoundException("Delivery", deliveryId);
        }

        return ledgerRepository.findByDeliveryIdOrderByCreatedAtAsc(deliveryId).stream()
                .map(this::toLedgerResponse)
                .toList();
    }

    /**
     * Not transactional: the escalation books a courier over the network and writes its audit rows in transactions of
     * their own, so a failure reaches the operator as an error and is still audited with their id.
     */
    public DeliveryDtos.DeliveryResponse forceWaterfall(Long actorId, Long deliveryId, String reason) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);

        log.info("Admin actor {} manually forcing delivery waterfall for delivery {}: {}", actorId, deliveryId, reason);
        waterfallService.forceEscalate(actorId, deliveryId, reason);

        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        return deliveryService.toResponse(delivery, null);
    }

    /**
     * Rolling 30-day reliability stats across all providers.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderStatsResponse> getLast30DaysStats(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        LocalDate since = LocalDate.now().minusDays(30);
        return statsRepository.findSince(since).stream()
                .map(this::toStatsResponse)
                .toList();
    }

    /**
     * All daily rows for one provider (for trend analysis / charting).
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderStatsResponse> getProviderStats(Long actorId, String providerCode) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        return statsRepository.findByProviderCodeOrderByWindowDateDesc(providerCode).stream()
                .map(this::toStatsResponse)
                .toList();
    }

    /**
     * Recent rolling 2-hour metrics across all providers.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderMetricsResponse> getLastMetrics(Long actorId, int days) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        java.time.Instant since = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        return metricsRepository.findSince(since).stream()
                .map(this::toMetricsResponse)
                .toList();
    }

    /**
     * 2-hour window metrics history for one provider.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderMetricsResponse> getProviderMetrics(Long actorId, String providerCode) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        return metricsRepository.findByProviderCodeOrderByWindowStartDesc(providerCode).stream()
                .map(this::toMetricsResponse)
                .toList();
    }

    /**
     * Paginated list of recent deliveries with joined supplier store, supplier org, and provider details.
     * Default page size is 10.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public DeliveryDtos.PagedResponse<DeliveryDtos.AdminDeliverySummaryResponse> listDeliveries(
            Long actorId, int page, int size, String status, String providerCode, Long supplierStoreId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        int effectivePage = Math.max(0, page);
        int effectiveSize = size <= 0 ? 10 : Math.min(size, 100);
        int offset = effectivePage * effectiveSize;

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();

        if (status != null && !status.isBlank()) {
            whereClause.append(" AND d.status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (providerCode != null && !providerCode.isBlank()) {
            whereClause.append(" AND d.provider_code = ?");
            params.add(providerCode.trim().toUpperCase());
        }
        if (supplierStoreId != null) {
            whereClause.append(" AND d.supplier_store_id = ?");
            params.add(supplierStoreId);
        }

        String countSql = "SELECT COUNT(*) FROM delivery d" + whereClause;
        Long totalElements = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long total = totalElements != null ? totalElements : 0L;

        String selectSql = """
                SELECT
                    d.id,
                    d.supplier_order_id,
                    so.order_number,
                    d.outlet_id,
                    o.name AS outlet_name,
                    d.supplier_store_id,
                    ss.name AS supplier_store_name,
                    sorg.display_name AS supplier_org_name,
                    d.mode,
                    d.status,
                    d.provider_code,
                    dp.name AS provider_name,
                    d.provider_delivery_id,
                    d.fee,
                    d.currency,
                    d.vehicle_type,
                    d.weight_kg,
                    d.driver_name,
                    d.driver_phone,
                    d.driver_vehicle,
                    d.eta_minutes,
                    d.estimated_arrival_at,
                    CASE
                        WHEN d.status IN ('DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT')
                             AND d.estimated_arrival_at IS NOT NULL
                             AND d.estimated_arrival_at < NOW()
                        THEN TIMESTAMPDIFF(MINUTE, d.estimated_arrival_at, NOW())
                        WHEN d.status = 'DELIVERED'
                             AND d.assigned_at IS NOT NULL
                             AND d.delivered_at IS NOT NULL
                             AND d.eta_minutes IS NOT NULL
                             AND TIMESTAMPDIFF(MINUTE, d.assigned_at, d.delivered_at) > d.eta_minutes
                        THEN TIMESTAMPDIFF(MINUTE, d.assigned_at, d.delivered_at) - d.eta_minutes
                        ELSE 0
                    END AS minutes_overdue,
                    d.requested_at,
                    d.booked_at,
                    d.assigned_at,
                    d.picked_up_at,
                    d.delivered_at,
                    d.cancelled_at,
                    d.failure_code,
                    d.failure_reason
                FROM delivery d
                LEFT JOIN supplier_order so ON so.id = d.supplier_order_id
                LEFT JOIN outlet o ON o.id = d.outlet_id
                LEFT JOIN supplier_store ss ON ss.id = d.supplier_store_id
                LEFT JOIN supplier_organization sorg ON sorg.id = ss.supplier_organization_id
                LEFT JOIN delivery_provider dp ON dp.code = d.provider_code
                """ + whereClause + " ORDER BY d.requested_at DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new java.util.ArrayList<>(params);
        queryParams.add(effectiveSize);
        queryParams.add(offset);

        List<DeliveryDtos.AdminDeliverySummaryResponse> items = jdbc.query(
                selectSql,
                this::mapAdminDeliverySummary,
                queryParams.toArray()
        );

        int totalPages = (int) Math.ceil((double) total / effectiveSize);
        boolean hasNext = (effectivePage + 1) < totalPages;

        return new DeliveryDtos.PagedResponse<>(items, effectivePage, effectiveSize, total, totalPages, hasNext);
    }

    /**
     * List deliveries with missed ETA (live breaches and/or historical breaches).
     * Defaults to 10 records per page.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public DeliveryDtos.PagedResponse<DeliveryDtos.AdminDeliverySummaryResponse> listLateDeliveries(
            Long actorId, boolean liveOnly, int page, int size) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        int effectivePage = Math.max(0, page);
        int effectiveSize = size <= 0 ? 10 : Math.min(size, 100);
        int offset = effectivePage * effectiveSize;

        String condition = liveOnly
                ? """
                  WHERE d.status IN ('DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT')
                    AND d.estimated_arrival_at IS NOT NULL
                    AND d.estimated_arrival_at < NOW()
                  """
                : """
                  WHERE (
                    (d.status IN ('DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT')
                     AND d.estimated_arrival_at IS NOT NULL
                     AND d.estimated_arrival_at < NOW())
                    OR
                    (d.status = 'DELIVERED'
                     AND d.assigned_at IS NOT NULL
                     AND d.delivered_at IS NOT NULL
                     AND d.eta_minutes IS NOT NULL
                     AND TIMESTAMPDIFF(MINUTE, d.assigned_at, d.delivered_at) > d.eta_minutes)
                  )
                  """;

        String countSql = "SELECT COUNT(*) FROM delivery d " + condition;
        Long totalElements = jdbc.queryForObject(countSql, Long.class);
        long total = totalElements != null ? totalElements : 0L;

        String selectSql = """
                SELECT
                    d.id,
                    d.supplier_order_id,
                    so.order_number,
                    d.outlet_id,
                    o.name AS outlet_name,
                    d.supplier_store_id,
                    ss.name AS supplier_store_name,
                    sorg.display_name AS supplier_org_name,
                    d.mode,
                    d.status,
                    d.provider_code,
                    dp.name AS provider_name,
                    d.provider_delivery_id,
                    d.fee,
                    d.currency,
                    d.vehicle_type,
                    d.weight_kg,
                    d.driver_name,
                    d.driver_phone,
                    d.driver_vehicle,
                    d.eta_minutes,
                    d.estimated_arrival_at,
                    CASE
                        WHEN d.status IN ('DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT')
                             AND d.estimated_arrival_at IS NOT NULL
                             AND d.estimated_arrival_at < NOW()
                        THEN TIMESTAMPDIFF(MINUTE, d.estimated_arrival_at, NOW())
                        WHEN d.status = 'DELIVERED'
                             AND d.assigned_at IS NOT NULL
                             AND d.delivered_at IS NOT NULL
                             AND d.eta_minutes IS NOT NULL
                             AND TIMESTAMPDIFF(MINUTE, d.assigned_at, d.delivered_at) > d.eta_minutes
                        THEN TIMESTAMPDIFF(MINUTE, d.assigned_at, d.delivered_at) - d.eta_minutes
                        ELSE 0
                    END AS minutes_overdue,
                    d.requested_at,
                    d.booked_at,
                    d.assigned_at,
                    d.picked_up_at,
                    d.delivered_at,
                    d.cancelled_at,
                    d.failure_code,
                    d.failure_reason
                FROM delivery d
                LEFT JOIN supplier_order so ON so.id = d.supplier_order_id
                LEFT JOIN outlet o ON o.id = d.outlet_id
                LEFT JOIN supplier_store ss ON ss.id = d.supplier_store_id
                LEFT JOIN supplier_organization sorg ON sorg.id = ss.supplier_organization_id
                LEFT JOIN delivery_provider dp ON dp.code = d.provider_code
                """ + condition + " ORDER BY minutes_overdue DESC, d.requested_at DESC LIMIT ? OFFSET ?";

        List<DeliveryDtos.AdminDeliverySummaryResponse> items = jdbc.query(
                selectSql,
                this::mapAdminDeliverySummary,
                effectiveSize, offset
        );

        int totalPages = (int) Math.ceil((double) total / effectiveSize);
        boolean hasNext = (effectivePage + 1) < totalPages;

        return new DeliveryDtos.PagedResponse<>(items, effectivePage, effectiveSize, total, totalPages, hasNext);
    }

    private DeliveryDtos.AdminDeliverySummaryResponse mapAdminDeliverySummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String statusStr = rs.getString("status");
        com.costonomy.mp.delivery.domain.DeliveryStatus deliveryStatus = statusStr != null
                ? com.costonomy.mp.delivery.domain.DeliveryStatus.valueOf(statusStr)
                : null;

        return new DeliveryDtos.AdminDeliverySummaryResponse(
                rs.getLong("id"),
                rs.getLong("supplier_order_id"),
                rs.getString("order_number"),
                rs.getLong("outlet_id"),
                rs.getString("outlet_name"),
                rs.getLong("supplier_store_id"),
                rs.getString("supplier_store_name"),
                rs.getString("supplier_org_name"),
                rs.getString("mode"),
                deliveryStatus,
                rs.getString("provider_code"),
                rs.getString("provider_name"),
                rs.getString("provider_delivery_id"),
                rs.getBigDecimal("fee"),
                rs.getString("currency"),
                rs.getString("vehicle_type"),
                rs.getBigDecimal("weight_kg"),
                rs.getString("driver_name"),
                rs.getString("driver_phone"),
                rs.getString("driver_vehicle"),
                rs.getObject("eta_minutes") == null ? null : rs.getInt("eta_minutes"),
                rs.getTimestamp("estimated_arrival_at") != null ? rs.getTimestamp("estimated_arrival_at").toInstant() : null,
                rs.getObject("minutes_overdue") == null ? 0 : rs.getInt("minutes_overdue"),
                rs.getTimestamp("requested_at") != null ? rs.getTimestamp("requested_at").toInstant() : null,
                rs.getTimestamp("booked_at") != null ? rs.getTimestamp("booked_at").toInstant() : null,
                rs.getTimestamp("assigned_at") != null ? rs.getTimestamp("assigned_at").toInstant() : null,
                rs.getTimestamp("picked_up_at") != null ? rs.getTimestamp("picked_up_at").toInstant() : null,
                rs.getTimestamp("delivered_at") != null ? rs.getTimestamp("delivered_at").toInstant() : null,
                rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null,
                rs.getString("failure_code"),
                rs.getString("failure_reason")
        );
    }

    private DeliveryDtos.DeliveryLedgerResponse toLedgerResponse(DeliveryLedgerEntry entry) {
        return new DeliveryDtos.DeliveryLedgerResponse(
                entry.getId(),
                entry.getDeliveryId(),
                entry.getProviderCode(),
                entry.getProviderDeliveryId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getCreatedAt());
    }

    private DeliveryDtos.ProviderStatsResponse toStatsResponse(DeliveryProviderStats s) {
        return new DeliveryDtos.ProviderStatsResponse(
                s.getProviderCode(),
                s.getWindowDate(),
                s.getTotalBookings(),
                s.getDriverCancellations(),
                s.getPickupFailures(),
                s.getDeliveryFailures(),
                s.getEtaOverruns(),
                s.getCompletedDeliveries(),
                round1dp(s.cancellationRate() * 100),
                round1dp(s.etaBreachRate() * 100),
                round1dp(s.overallFailureRate() * 100),
                s.getAvgActualEtaMinutes(),
                s.getAvgQuotedEtaMinutes(),
                s.getAvgPriceDeviationInr());
    }

    private DeliveryDtos.ProviderMetricsResponse toMetricsResponse(com.costonomy.mp.delivery.domain.DeliveryProviderMetrics m) {
        return new DeliveryDtos.ProviderMetricsResponse(
                m.getProviderCode(),
                m.getWindowStart(),
                round1dp(m.getAvgLatencyMs()),
                round1dp(m.getP95LatencyMs()),
                m.getTotalCostInr(),
                m.getOrderCount());
    }

    private static double round1dp(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.SupplierDeliveryInspectionResponse inspectSupplierDelivery(Long actorId, Long deliveryId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        var qList = quotes.findByDeliveryIdOrderByIdAsc(deliveryId).stream()
                .map(q -> new FoodDeliveryDtos.QuoteInspection(
                        q.getId(), q.getProviderCode(), q.getStatus(), q.getAmount(),
                        q.getEtaMinutes(), q.getDistanceKm(), q.getFailureReason(),
                        false, q.getCreatedAt()))
                .toList();

        var aList = attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId).stream()
                .map(a -> new FoodDeliveryDtos.AttemptInspection(
                        a.getId(), a.getAttemptNumber(), a.getAttemptType(), a.getProviderCode(),
                        a.getOutcome(), a.getQuotedAmount(), a.getRankPosition(), a.getSelectionReason(),
                        a.getFailureCode(), a.getFailureReason(), a.getEndedCause(),
                        a.getStartedAt(), a.getEndedAt()))
                .toList();

        var timeline = deliveryService.timelineFor(actorId, deliveryId);

        var rList = recoveryService.getTasksForDelivery("SUPPLIER", deliveryId).stream()
                .map(r -> new FoodDeliveryDtos.RecoveryTaskInspection(
                        r.getId(), r.getTaskType(), r.getProviderCode(), r.getProviderDeliveryId(),
                        r.getIdempotencyRef(), r.getAmount(), r.getStatus(), r.getAttempts(),
                        r.getMaxAttempts(), r.getNextRetryAt(), r.getLastError(), r.getCreatedAt()))
                .toList();

        var next = nextStep(delivery);
        String nextAction = next.action();
        String nextReason = next.reason();

        return new FoodDeliveryDtos.SupplierDeliveryInspectionResponse(
                deliveryService.toResponse(delivery, null),
                qList, aList, timeline, rList,
                delivery.isReconciliationRequired(), delivery.getReconciliationReason(),
                nextAction, nextReason
        );
    }

    /** What happens next to a supplier delivery, and who does it. */
    public record NextStep(String action, String reason) {
    }

    /**
     * The honest answer for every status (D-204 audit, gap 9): "TERMINAL" only for a delivery that is over, and for
     * every other state the job that will move it, or the operator action that has to.
     */
    public static NextStep nextStep(com.costonomy.mp.delivery.domain.Delivery d) {
        var st = d.getStatus();
        if (d.isReconciliationRequired()) {
            return new NextStep("OPERATOR_RECONCILIATION",
                    d.getReconciliationReason() != null ? d.getReconciliationReason() : "Reconciliation required");
        }
        if (d.getMode() == com.costonomy.mp.delivery.domain.DeliveryMode.SUPPLIER_OWN && !st.isTerminal()) {
            return new NextStep("SUPPLIER_DELIVERS", "The supplier is delivering this order themselves.");
        }
        return switch (st) {
            case DELIVERY_REQUESTED -> new NextStep("WATERFALL", "Searching and booking available partners");
            case QUOTE_RECEIVED -> new NextStep("BOOKING_SWEEP",
                    "Quoted; the assignment sweep books a courier if booking did not start");
            case QUOTE_FAILED -> new NextStep("RETRY",
                    "No partner would quote; the retry job asks again, then the supplier is offered own delivery. "
                            + "Operations can cancel and refund from /resolve.");
            case PROVIDER_UNAVAILABLE -> new NextStep("RETRY",
                    "Will auto-retry within the window, then offer the supplier own delivery");
            case PROVIDER_SELECTED -> new NextStep("ASSIGNMENT_SWEEP",
                    "Waiting for driver assignment deadline at " + d.getAssignmentDeadline());
            case DRIVER_ASSIGNED, DRIVER_AT_PICKUP -> new NextStep("AWAIT_PICKUP",
                    "A driver is on the way to collect it; nothing is automatic. If the driver is stuck, "
                            + "force-waterfall hands it to another courier.");
            case DRIVER_CANCELLED, PICKUP_FAILED -> new NextStep("ASSIGNMENT_SWEEP",
                    "The provider let go of it; the next courier is booked automatically");
            case DELIVERY_FAILED -> new NextStep("OPERATOR_RESOLVE",
                    "The goods were collected but not delivered. Resolve it: cancel and refund, or mark delivered.");
            case PICKED_UP, IN_TRANSIT, ARRIVED_AT_DESTINATION -> new NextStep("IN_TRANSIT",
                    "Consignment is in transit with " + d.getProviderCode());
            case DELIVERED, CANCELLED -> new NextStep("TERMINAL", "Delivery reached terminal status " + st);
        };
    }

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.FoodDeliveryInspectionResponse inspectFoodDelivery(Long actorId, Long foodDeliveryId) {
        return foodDeliveryQueryService.inspect(actorId, foodDeliveryId);
    }

    /** How long an order may wait at a step before it is an exception rather than a normal delay. */
    private static final int STUCK_AFTER_MINUTES = 30;
    /** A funded delivery share the supplier has not paid for this long is worth a person's attention. */
    private static final int UNCOLLECTED_AFTER_DAYS = 14;
    private static final int EXCEPTION_LIMIT = 50;

    /**
     * When no partner can be found, the automatic retries run and then the supplier is offered own delivery (D-185,
     * default 45 minutes after the first failure). A delivery is an operator's problem only after that offer is due,
     * not while the retries are still trying.
     */
    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.own-delivery-offer-after:PT45M}")
    private java.time.Duration ownDeliveryOfferAfter = java.time.Duration.ofMinutes(45);

    /**
     * How long a ready pickup order may wait before it is listed for operations. A visibility threshold only: nothing
     * happens to the order at this time. An uncollected-pickup policy (reminder, cancel, fee) is a product decision.
     */
    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.uncollected-pickup-listed-after:PT24H}")
    private java.time.Duration uncollectedPickupListedAfter = java.time.Duration.ofHours(24);

    /**
     * Orders that look complete or under way while a delivery or money step is unresolved. Each kind is bounded and
     * oldest first; this is the list that makes "no order stays quietly stuck" checkable.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.JourneyException> journeyExceptions(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        var out = new java.util.ArrayList<DeliveryDtos.JourneyException>();
        // Ambiguous booking or post-pickup failure requiring operator reconciliation.
        out.addAll(jdbc.query("""
                select so.id, d.id, so.status, d.status, timestampdiff(minute, d.updated_at, utc_timestamp(6))
                  from supplier_order so join delivery d on d.supplier_order_id = so.id
                 where d.reconciliation_required = true
                 order by d.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("RECONCILIATION_REQUIRED", rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5),
                        "The delivery booking outcome is ambiguous or failed and requires operator reconciliation.",
                        "POST /admin/deliveries/{id}/resolve with ASSOCIATE_CONSIGNMENT, RESUME_BOOKING, or CANCEL_AND_REFUND."),
                EXCEPTION_LIMIT));
        // Ready for pickup, but the delivery that should collect it has no courier and is not trying any more. A delivery
        // that no partner would take is listed once the own-delivery offer is due (after the automatic retries, D-185),
        // never while they are still trying; a rider who let go is rebooked within seconds, so those are listed after 30.
        out.addAll(jdbc.query("""
                select so.id, d.id, so.status, d.status, timestampdiff(minute, so.updated_at, utc_timestamp(6))
                  from supplier_order so join delivery d on d.supplier_order_id = so.id
                 where so.status = 'READY_FOR_PICKUP' and d.mode = 'COSTONOMY'
                   and ((d.status in ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE')
                         and coalesce(d.no_partner_since, so.updated_at) < utc_timestamp(6) - interval ? second)
                     or (d.status in ('DRIVER_CANCELLED', 'PICKUP_FAILED', 'DELIVERY_FAILED', 'CANCELLED')
                         and so.updated_at < utc_timestamp(6) - interval ? minute))
                 order by so.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("READY_WITHOUT_COURIER", rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5),
                        "The order is ready but its delivery has no courier and is not being retried.",
                        "Let the supplier deliver it, rebook, or POST /admin/deliveries/{id}/resolve to cancel and refund."),
                ownDeliveryOfferAfter.toSeconds(), STUCK_AFTER_MINUTES, EXCEPTION_LIMIT));
        // A pickup order the restaurant has not collected. Pickup has no delivery row and no timeout (a product decision),
        // so this is the only place it shows up, with the operator's exit.
        out.addAll(jdbc.query("""
                select so.id, so.status, timestampdiff(minute, so.updated_at, utc_timestamp(6))
                  from supplier_order so
                 where so.status = 'READY_FOR_PICKUP' and so.delivery_mode = 'PICKUP'
                   and so.updated_at < utc_timestamp(6) - interval ? second
                   and not exists (select 1 from delivery d where d.supplier_order_id = so.id)
                 order by so.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("PICKUP_NOT_COLLECTED", rs.getLong(1), null,
                        rs.getString(2), null, rs.getLong(3),
                        "A pickup order has been ready and uncollected for a long time. Nothing ends it automatically.",
                        "Ask the restaurant to collect it, or POST /admin/deliveries/orders/{orderId}/close-pickup to cancel and refund."),
                uncollectedPickupListedAfter.toSeconds(), EXCEPTION_LIMIT));
        // On the road according to the order, but the delivery failed.
        out.addAll(jdbc.query("""
                select so.id, d.id, so.status, d.status, timestampdiff(minute, so.updated_at, utc_timestamp(6))
                  from supplier_order so join delivery d on d.supplier_order_id = so.id
                 where so.status = 'OUT_FOR_DELIVERY' and d.status in ('DELIVERY_FAILED', 'CANCELLED')
                 order by so.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("ON_THE_ROAD_BUT_FAILED", rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5),
                        "The order says it is out for delivery but the delivery failed after pickup.",
                        "POST /admin/deliveries/{id}/resolve: cancel and refund the customer, or mark it delivered."),
                EXCEPTION_LIMIT));
        // The order is delivered but the courier cannot be paid or the payment is unresolved.
        out.addAll(jdbc.query("""
                select so.id, d.id, so.status, d.status, timestampdiff(minute, p.updated_at, utc_timestamp(6)), p.state
                  from courier_payable p join delivery d on d.id = p.delivery_id and p.source_type = 'SUPPLIER_DELIVERY'
                  join supplier_order so on so.id = d.supplier_order_id
                 where p.state in ('EXHAUSTED', 'AMBIGUOUS') or p.reconciliation_state = 'REQUIRED'
                 order by p.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("COURIER_PAYMENT_UNRESOLVED", rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5),
                        "The courier payment for this delivery is " + rs.getString(6) + " or needs reconciling.",
                        "See /admin/delivery-finance/payables for the payable and its actions."),
                EXCEPTION_LIMIT));
        // Restaurant-to-customer (food) deliveries: flagged for an operator, or whose courier payment is unresolved.
        // The id is the food delivery's, and there is no supplier order.
        out.addAll(jdbc.query("""
                select f.id, f.status, timestampdiff(minute, f.updated_at, utc_timestamp(6)), f.reconciliation_reason
                  from food_delivery f
                 where f.reconciliation_required = true
                 order by f.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("FOOD_RECONCILIATION_REQUIRED", null, rs.getLong(1),
                        null, rs.getString(2), rs.getLong(3),
                        rs.getString(4) != null ? rs.getString(4) : "A food delivery needs an operator's decision.",
                        "POST /admin/food-deliveries/{id}/resolve with CANCEL_AND_REFUND, MARK_DELIVERED or CLEAR_FLAG."),
                EXCEPTION_LIMIT));
        out.addAll(jdbc.query("""
                select f.id, f.status, timestampdiff(minute, p.updated_at, utc_timestamp(6)), p.state
                  from courier_payable p join food_delivery f on f.id = p.delivery_id and p.source_type = 'FOOD_DELIVERY'
                 where p.state in ('EXHAUSTED', 'AMBIGUOUS') or p.reconciliation_state = 'REQUIRED'
                 order by p.updated_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("FOOD_COURIER_PAYMENT_UNRESOLVED", null, rs.getLong(1),
                        null, rs.getString(2), rs.getLong(3),
                        "The courier payment for this food delivery is " + rs.getString(4) + " or needs reconciling.",
                        "See /admin/delivery-finance/payables for the payable and its actions."),
                EXCEPTION_LIMIT));
        // A supplier's funded share nobody has collected for weeks (for example a credit order with no settlement to take it from).
        out.addAll(jdbc.query("""
                select so.id, sd.delivery_id, so.status, null, timestampdiff(minute, sd.created_at, utc_timestamp(6))
                  from supplier_deduction sd join supplier_order so on so.id = sd.supplier_order_id
                 where sd.kind = 'DELIVERY_FUNDING' and sd.status = 'PENDING'
                   and sd.created_at < utc_timestamp(6) - interval ? day
                 order by sd.created_at limit ?
                """, (rs, n) -> new DeliveryDtos.JourneyException("SUPPLIER_SHARE_UNCOLLECTED", rs.getLong(1),
                        rs.getObject(2) != null ? rs.getLong(2) : null, rs.getString(3), null, rs.getLong(5),
                        "The supplier's share of the delivery has been owed for over " + UNCOLLECTED_AFTER_DAYS + " days.",
                        "Remind the supplier to pay it directly, or wait for a settlement that can take it."),
                UNCOLLECTED_AFTER_DAYS, EXCEPTION_LIMIT));
        return out;
    }

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.OperationalAlertsResponse getOperationalAlerts(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        var stuckSupplier = jdbc.query(
                "SELECT id, outlet_id, status, provider_code, reconciliation_required, reconciliation_reason " +
                "FROM delivery WHERE reconciliation_required = true " +
                "OR (status = 'PROVIDER_SELECTED' AND assignment_deadline < NOW()) LIMIT 50",
                (rs, row) -> new FoodDeliveryDtos.StuckDeliveryAlert(
                        "SUPPLIER",
                        rs.getLong("id"),
                        rs.getLong("outlet_id"),
                        rs.getString("status"),
                        rs.getString("provider_code"),
                        rs.getBoolean("reconciliation_required"),
                        rs.getString("reconciliation_reason"),
                        rs.getBoolean("reconciliation_required") ? "Reconciliation required" : "Assignment deadline passed"
                )
        );

        var stuckFood = jdbc.query(
                "SELECT id, outlet_id, status, provider_code, reconciliation_required, reconciliation_reason " +
                "FROM food_delivery WHERE reconciliation_required = true " +
                "OR (status = 'PROVIDER_SELECTED' AND assignment_deadline < NOW()) LIMIT 50",
                (rs, row) -> new FoodDeliveryDtos.StuckDeliveryAlert(
                        "FOOD",
                        rs.getLong("id"),
                        rs.getLong("outlet_id"),
                        rs.getString("status"),
                        rs.getString("provider_code"),
                        rs.getBoolean("reconciliation_required"),
                        rs.getString("reconciliation_reason"),
                        rs.getBoolean("reconciliation_required") ? "Reconciliation required" : "Assignment deadline passed"
                )
        );

        var allStuck = new java.util.ArrayList<FoodDeliveryDtos.StuckDeliveryAlert>();
        allStuck.addAll(stuckSupplier);
        allStuck.addAll(stuckFood);

        long pendingTasks = recoveryService.countByStatus("PENDING");
        long exhaustedTasks = recoveryService.countByStatus("EXHAUSTED");
        long failedRefunds = recoveryService.countByTaskTypeAndStatus("WALLET_REFUND", "EXHAUSTED");
        long failedCancels = recoveryService.countByTaskTypeAndStatus("PROVIDER_CANCEL", "EXHAUSTED");

        long reconCount = allStuck.stream().filter(FoodDeliveryDtos.StuckDeliveryAlert::reconciliationRequired).count();

        return new FoodDeliveryDtos.OperationalAlertsResponse(
                stuckSupplier.size(),
                stuckFood.size(),
                reconCount,
                pendingTasks,
                exhaustedTasks,
                failedRefunds,
                failedCancels,
                allStuck
        );
    }
}
