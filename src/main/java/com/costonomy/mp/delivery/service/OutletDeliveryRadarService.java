package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Situational radar and operational action engine for kitchen and outlet staff.
 *
 * <p>Directly answers:
 * <ol>
 *   <li>"I have 12 active orders. Which one is approaching the kitchen?" (ranked by arrival urgency)</li>
 *   <li>"Is this supplier still on schedule?" (schedule status and overdue duration)</li>
 *   <li>"Did the driver call? Is there a problem?" (driver contacts, problem flags, and stale GPS)</li>
 *   <li>"Which orders should I check in, receive, or escalate?" (recommended action classifier)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutletDeliveryRadarService {

    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;

    @Value("${costonomy.mp.delivery.location-stale-after:60s}")
    private Duration locationStaleAfter = Duration.ofSeconds(60);

    /**
     * Active delivery radar for an outlet, ranked by arrival proximity.
     */
    @Transactional(readOnly = true)
    public DeliveryDtos.OutletDeliveryRadarResponse getRadar(
            Long actorId,
            Long outletId,
            DeliveryDtos.KitchenAction actionFilter,
            DeliveryDtos.ArrivalStage stageFilter,
            DeliveryDtos.ScheduleStatus scheduleFilter) {

        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        String sql = """
                SELECT
                    d.id,
                    d.supplier_order_id,
                    so.order_number,
                    d.outlet_id,
                    d.supplier_store_id,
                    ss.name AS supplier_store_name,
                    sorg.display_name AS supplier_org_name,
                    ss.contact_phone AS supplier_contact_phone,
                    d.mode,
                    d.status,
                    d.driver_name,
                    d.driver_phone,
                    d.driver_vehicle,
                    d.eta_minutes,
                    d.estimated_arrival_at,
                    d.requested_at,
                    d.assigned_at,
                    d.picked_up_at,
                    d.delivered_at,
                    d.failure_code,
                    d.failure_reason,
                    rec.id AS receiving_id,
                    loc.latitude,
                    loc.longitude,
                    loc.bearing,
                    loc.recorded_at
                FROM delivery d
                LEFT JOIN supplier_order so ON so.id = d.supplier_order_id
                LEFT JOIN supplier_store ss ON ss.id = d.supplier_store_id
                LEFT JOIN supplier_organization sorg ON sorg.id = ss.supplier_organization_id
                LEFT JOIN receiving rec ON rec.supplier_order_id = d.supplier_order_id
                LEFT JOIN (
                    SELECT dl1.*
                    FROM delivery_location dl1
                    INNER JOIN (
                        SELECT delivery_id, MAX(id) AS max_id
                        FROM delivery_location
                        GROUP BY delivery_id
                    ) dl2 ON dl1.id = dl2.max_id
                ) loc ON loc.delivery_id = d.id
                WHERE d.outlet_id = ?
                  AND (
                    d.status IN ('DELIVERY_REQUESTED', 'QUOTE_RECEIVED', 'PROVIDER_SELECTED',
                                 'DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP',
                                 'IN_TRANSIT', 'ARRIVED_AT_DESTINATION')
                    OR (d.status = 'DELIVERED' AND rec.id IS NULL)
                  )
                """;

        List<DeliveryDtos.OutletDeliveryRadarItemResponse> allItems = jdbc.query(
                sql,
                this::mapRadarItem,
                outletId
        );

        // Sort by arrivalRank ASC (most urgent first), then estimatedArrivalAt ASC, then id ASC
        List<DeliveryDtos.OutletDeliveryRadarItemResponse> sortedItems = allItems.stream()
                .sorted(Comparator.comparingInt(DeliveryDtos.OutletDeliveryRadarItemResponse::arrivalRank)
                        .thenComparing(item -> item.estimatedArrivalAt() == null ? Instant.MAX : item.estimatedArrivalAt())
                        .thenComparing(DeliveryDtos.OutletDeliveryRadarItemResponse::deliveryId))
                .toList();

        // Calculate radar summary counts
        int atDoorCount = 0;
        int approachingCount = 0;
        int enRouteCount = 0;
        int delayedCount = 0;
        int pendingCheckInCount = 0;
        int requiresEscalationCount = 0;

        for (var item : sortedItems) {
            if (item.arrivalStage() == DeliveryDtos.ArrivalStage.AT_KITCHEN_DOOR) {
                atDoorCount++;
            } else if (item.arrivalStage() == DeliveryDtos.ArrivalStage.APPROACHING) {
                approachingCount++;
            } else if (item.arrivalStage() == DeliveryDtos.ArrivalStage.EN_ROUTE) {
                enRouteCount++;
            }

            if (item.scheduleStatus() != DeliveryDtos.ScheduleStatus.ON_SCHEDULE) {
                delayedCount++;
            }
            if (item.recommendedAction() == DeliveryDtos.KitchenAction.CHECK_IN) {
                pendingCheckInCount++;
            }
            if (item.recommendedAction() == DeliveryDtos.KitchenAction.ESCALATE) {
                requiresEscalationCount++;
            }
        }

        DeliveryDtos.RadarSummaryResponse summary = new DeliveryDtos.RadarSummaryResponse(
                sortedItems.size(),
                atDoorCount,
                approachingCount,
                enRouteCount,
                delayedCount,
                pendingCheckInCount,
                requiresEscalationCount
        );

        // Apply filters if specified
        List<DeliveryDtos.OutletDeliveryRadarItemResponse> filteredItems = sortedItems.stream()
                .filter(item -> actionFilter == null || item.recommendedAction() == actionFilter)
                .filter(item -> stageFilter == null || item.arrivalStage() == stageFilter)
                .filter(item -> scheduleFilter == null || item.scheduleStatus() == scheduleFilter)
                .toList();

        return new DeliveryDtos.OutletDeliveryRadarResponse(outletId, summary, filteredItems);
    }

    /**
     * Paginated list of all deliveries for an outlet (default 10 items/page).
     */
    @Transactional(readOnly = true)
    public DeliveryDtos.PagedResponse<DeliveryDtos.OutletDeliveryRadarItemResponse> listDeliveries(
            Long actorId, Long outletId, int page, int size, String status) {

        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        int effectivePage = Math.max(0, page);
        int effectiveSize = size <= 0 ? 10 : Math.min(size, 100);
        int offset = effectivePage * effectiveSize;

        StringBuilder whereClause = new StringBuilder(" WHERE d.outlet_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(outletId);

        if (status != null && !status.isBlank()) {
            whereClause.append(" AND d.status = ?");
            params.add(status.trim().toUpperCase());
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
                    d.supplier_store_id,
                    ss.name AS supplier_store_name,
                    sorg.display_name AS supplier_org_name,
                    ss.contact_phone AS supplier_contact_phone,
                    d.mode,
                    d.status,
                    d.driver_name,
                    d.driver_phone,
                    d.driver_vehicle,
                    d.eta_minutes,
                    d.estimated_arrival_at,
                    d.requested_at,
                    d.assigned_at,
                    d.picked_up_at,
                    d.delivered_at,
                    d.failure_code,
                    d.failure_reason,
                    rec.id AS receiving_id,
                    loc.latitude,
                    loc.longitude,
                    loc.bearing,
                    loc.recorded_at
                FROM delivery d
                LEFT JOIN supplier_order so ON so.id = d.supplier_order_id
                LEFT JOIN supplier_store ss ON ss.id = d.supplier_store_id
                LEFT JOIN supplier_organization sorg ON sorg.id = ss.supplier_organization_id
                LEFT JOIN receiving rec ON rec.supplier_order_id = d.supplier_order_id
                LEFT JOIN (
                    SELECT dl1.*
                    FROM delivery_location dl1
                    INNER JOIN (
                        SELECT delivery_id, MAX(id) AS max_id
                        FROM delivery_location
                        GROUP BY delivery_id
                    ) dl2 ON dl1.id = dl2.max_id
                ) loc ON loc.delivery_id = d.id
                """ + whereClause + " ORDER BY d.requested_at DESC, d.id DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(effectiveSize);
        queryParams.add(offset);

        List<DeliveryDtos.OutletDeliveryRadarItemResponse> items = jdbc.query(
                selectSql,
                this::mapRadarItem,
                queryParams.toArray()
        );

        int totalPages = (int) Math.ceil((double) total / effectiveSize);
        boolean hasNext = (effectivePage + 1) < totalPages;

        return new DeliveryDtos.PagedResponse<>(items, effectivePage, effectiveSize, total, totalPages, hasNext);
    }

    private DeliveryDtos.OutletDeliveryRadarItemResponse mapRadarItem(ResultSet rs, int rowNum) throws SQLException {
        Long deliveryId = rs.getLong("id");
        Long supplierOrderId = rs.getLong("supplier_order_id");
        String orderNumber = rs.getString("order_number");
        Long outletId = rs.getLong("outlet_id");
        Long supplierStoreId = rs.getLong("supplier_store_id");
        String supplierStoreName = rs.getString("supplier_store_name");
        String supplierOrgName = rs.getString("supplier_org_name");
        String supplierContactPhone = rs.getString("supplier_contact_phone");

        String statusStr = rs.getString("status");
        DeliveryStatus status = statusStr != null ? DeliveryStatus.valueOf(statusStr) : DeliveryStatus.DELIVERY_REQUESTED;

        String driverName = rs.getString("driver_name");
        String driverPhone = rs.getString("driver_phone");
        String driverVehicle = rs.getString("driver_vehicle");

        Integer etaMinutes = rs.getObject("eta_minutes") == null ? null : rs.getInt("eta_minutes");
        Instant estimatedArrivalAt = rs.getTimestamp("estimated_arrival_at") != null
                ? rs.getTimestamp("estimated_arrival_at").toInstant() : null;

        Instant requestedAt = rs.getTimestamp("requested_at") != null
                ? rs.getTimestamp("requested_at").toInstant() : null;
        Instant assignedAt = rs.getTimestamp("assigned_at") != null
                ? rs.getTimestamp("assigned_at").toInstant() : null;
        Instant pickedUpAt = rs.getTimestamp("picked_up_at") != null
                ? rs.getTimestamp("picked_up_at").toInstant() : null;
        Instant deliveredAt = rs.getTimestamp("delivered_at") != null
                ? rs.getTimestamp("delivered_at").toInstant() : null;

        String failureCode = rs.getString("failure_code");
        String failureReason = rs.getString("failure_reason");
        boolean isCheckedIn = rs.getObject("receiving_id") != null;

        // Location & Staleness
        BigDecimal latitude = rs.getBigDecimal("latitude");
        BigDecimal longitude = rs.getBigDecimal("longitude");
        BigDecimal bearing = rs.getBigDecimal("bearing");
        Instant recordedAt = rs.getTimestamp("recorded_at") != null
                ? rs.getTimestamp("recorded_at").toInstant() : null;

        DeliveryDtos.LocationResponse location = null;
        boolean locationStale = false;
        Integer locationAgeSeconds = null;

        if (recordedAt != null && latitude != null && longitude != null) {
            location = new DeliveryDtos.LocationResponse(latitude, longitude, bearing, recordedAt);
            long age = Duration.between(recordedAt, Instant.now()).getSeconds();
            locationAgeSeconds = (int) Math.max(0, age);
            locationStale = age > locationStaleAfter.getSeconds();
        }

        // 1. Calculate ArrivalStage and arrivalRank
        DeliveryDtos.ArrivalStage arrivalStage;
        int arrivalRank;

        if (status == DeliveryStatus.ARRIVED_AT_DESTINATION) {
            arrivalStage = DeliveryDtos.ArrivalStage.AT_KITCHEN_DOOR;
            arrivalRank = 0;
        } else if (status == DeliveryStatus.IN_TRANSIT) {
            if ((etaMinutes != null && etaMinutes <= 15)
                    || (estimatedArrivalAt != null && estimatedArrivalAt.isBefore(Instant.now().plus(Duration.ofMinutes(15))))) {
                arrivalStage = DeliveryDtos.ArrivalStage.APPROACHING;
                arrivalRank = 1;
            } else {
                arrivalStage = DeliveryDtos.ArrivalStage.EN_ROUTE;
                arrivalRank = 2;
            }
        } else if (status == DeliveryStatus.DELIVERED && !isCheckedIn) {
            arrivalStage = DeliveryDtos.ArrivalStage.DELIVERED_UNCHECKED;
            arrivalRank = 3;
        } else if (status == DeliveryStatus.PICKED_UP || status == DeliveryStatus.DRIVER_AT_PICKUP) {
            arrivalStage = DeliveryDtos.ArrivalStage.AT_SUPPLIER_PICKUP;
            arrivalRank = 4;
        } else if (status == DeliveryStatus.DRIVER_ASSIGNED || status == DeliveryStatus.PROVIDER_SELECTED) {
            arrivalStage = DeliveryDtos.ArrivalStage.DRIVER_DISPATCHED;
            arrivalRank = 5;
        } else {
            arrivalStage = DeliveryDtos.ArrivalStage.AWAITING_DRIVER;
            arrivalRank = 6;
        }

        // 2. Calculate ScheduleStatus & minutesOverdue
        Instant now = Instant.now();
        int minutesOverdue = 0;
        if (estimatedArrivalAt != null && estimatedArrivalAt.isBefore(now) && !status.isTerminal()) {
            minutesOverdue = (int) Duration.between(estimatedArrivalAt, now).toMinutes();
        } else if (status == DeliveryStatus.DELIVERED && assignedAt != null && deliveredAt != null && etaMinutes != null) {
            int actualDuration = (int) Duration.between(assignedAt, deliveredAt).toMinutes();
            if (actualDuration > etaMinutes) {
                minutesOverdue = actualDuration - etaMinutes;
            }
        }

        DeliveryDtos.ScheduleStatus scheduleStatus;
        if (minutesOverdue > 15) {
            scheduleStatus = DeliveryDtos.ScheduleStatus.CRITICALLY_DELAYED;
        } else if (minutesOverdue > 0) {
            scheduleStatus = DeliveryDtos.ScheduleStatus.RUNNING_LATE;
        } else {
            scheduleStatus = DeliveryDtos.ScheduleStatus.ON_SCHEDULE;
        }

        // 3. Problem Diagnostics
        boolean hasProblem = false;
        DeliveryDtos.ProblemType problemType = DeliveryDtos.ProblemType.NONE;
        String problemDesc = null;

        if (failureCode != null && !failureCode.isBlank()) {
            hasProblem = true;
            problemType = DeliveryDtos.ProblemType.CARRIER_EXCEPTION;
            problemDesc = failureReason != null ? failureReason : "Carrier exception reported: " + failureCode;
        } else if (status == DeliveryStatus.IN_TRANSIT && locationStale) {
            hasProblem = true;
            problemType = DeliveryDtos.ProblemType.STALE_TELEMETRY;
            problemDesc = "Driver GPS signal stale (" + locationAgeSeconds + "s since last ping)";
        } else if (minutesOverdue > 0) {
            hasProblem = true;
            problemType = DeliveryDtos.ProblemType.MISSED_ETA;
            problemDesc = "Running " + minutesOverdue + " minutes behind projected arrival";
        }

        DeliveryDtos.ProblemDetails problemDetails = new DeliveryDtos.ProblemDetails(
                hasProblem,
                problemType,
                problemDesc,
                failureCode,
                failureReason
        );

        // 4. Action Recommendation Classifier
        DeliveryDtos.KitchenAction recommendedAction;
        String actionReason;

        if (status == DeliveryStatus.DELIVERED && !isCheckedIn) {
            recommendedAction = DeliveryDtos.KitchenAction.CHECK_IN;
            actionReason = "Order delivered at door. Verify items and complete receiving check-in.";
        } else if (status == DeliveryStatus.ARRIVED_AT_DESTINATION) {
            recommendedAction = DeliveryDtos.KitchenAction.MEET_DRIVER;
            actionReason = "Driver is waiting at kitchen receiving dock.";
        } else if (scheduleStatus == DeliveryDtos.ScheduleStatus.CRITICALLY_DELAYED
                || problemType == DeliveryDtos.ProblemType.CARRIER_EXCEPTION) {
            recommendedAction = DeliveryDtos.KitchenAction.ESCALATE;
            actionReason = "Severe delay or carrier failure. Escalate with supplier or support.";
        } else if (arrivalStage == DeliveryDtos.ArrivalStage.APPROACHING) {
            recommendedAction = DeliveryDtos.KitchenAction.PREPARE_DOCK;
            actionReason = "Order approaching within " + (etaMinutes != null ? etaMinutes : 15) + " mins. Clear dock area.";
        } else if (problemType == DeliveryDtos.ProblemType.STALE_TELEMETRY
                || scheduleStatus == DeliveryDtos.ScheduleStatus.RUNNING_LATE) {
            recommendedAction = DeliveryDtos.KitchenAction.CALL_DRIVER;
            actionReason = "Driver running late or GPS stale. Call driver for direct status.";
        } else {
            recommendedAction = DeliveryDtos.KitchenAction.MONITOR;
            actionReason = "Order progressing normally on schedule.";
        }

        DeliveryDtos.SupplierInfo supplierInfo = new DeliveryDtos.SupplierInfo(
                supplierStoreId,
                supplierStoreName,
                supplierOrgName,
                supplierContactPhone
        );

        DeliveryDtos.DriverInfo driverInfo = new DeliveryDtos.DriverInfo(
                driverName,
                driverPhone,
                driverVehicle
        );

        return new DeliveryDtos.OutletDeliveryRadarItemResponse(
                deliveryId,
                supplierOrderId,
                orderNumber,
                outletId,
                status,
                arrivalStage,
                arrivalRank,
                scheduleStatus,
                minutesOverdue,
                etaMinutes,
                estimatedArrivalAt,
                supplierInfo,
                driverInfo,
                problemDetails,
                recommendedAction,
                actionReason,
                isCheckedIn,
                location,
                locationStale,
                locationAgeSeconds,
                requestedAt,
                assignedAt,
                pickedUpAt,
                deliveredAt
        );
    }
}
