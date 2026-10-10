package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryLedgerEntry;
import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import com.costonomy.mp.delivery.repository.DeliveryLedgerRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderStatsRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderMetricsRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
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
    private final DeliveryWaterfallService waterfallService;
    private final DeliveryService deliveryService;
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

    @Transactional
    public DeliveryDtos.DeliveryResponse forceWaterfall(Long actorId, Long deliveryId, String reason) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);

        log.info("Admin actor {} manually forcing delivery waterfall for delivery {}: {}", actorId, deliveryId, reason);
        waterfallService.forceEscalate(deliveryId, reason);

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
}
