package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.delivery.web.dto.DeliveryExportRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Service for streaming bulk deliveries as CSV or JSON for Ops / BI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDeliveryExportService {

    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;

    @Value("${costonomy.mp.admin.export.max-rows:10000}")
    private int maxRows;

    @Transactional(readOnly = true)
    public List<DeliveryExportRow> fetchDeliveries(Long actorId, LocalDate since, String status, int limit) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        int effectiveLimit = Math.min(limit > 0 ? limit : maxRows, maxRows);
        Timestamp sinceTimestamp = since != null ? Timestamp.from(since.atStartOfDay().toInstant(ZoneOffset.UTC)) : null;

        StringBuilder sql = new StringBuilder("""
                SELECT
                    d.id,
                    d.supplier_order_id,
                    d.outlet_id,
                    d.supplier_store_id,
                    d.mode,
                    d.status,
                    d.provider_code,
                    d.provider_delivery_id,
                    d.fee,
                    d.currency,
                    d.vehicle_type,
                    d.weight_kg,
                    d.volume_cbm,
                    d.driver_name,
                    d.driver_phone,
                    d.eta_minutes,
                    d.attempt_count,
                    d.failure_code,
                    d.failure_reason,
                    d.requested_at,
                    d.booked_at,
                    d.assigned_at,
                    d.picked_up_at,
                    d.delivered_at,
                    d.cancelled_at
                FROM delivery d
                WHERE 1=1
                """);

        List<Object> params = new java.util.ArrayList<>();
        if (sinceTimestamp != null) {
            sql.append(" AND d.requested_at >= ?");
            params.add(sinceTimestamp);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = ?");
            params.add(status.trim().toUpperCase());
        }

        sql.append(" ORDER BY d.requested_at DESC LIMIT ?");
        params.add(effectiveLimit);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new DeliveryExportRow(
                rs.getLong("id"),
                rs.getLong("supplier_order_id"),
                rs.getLong("outlet_id"),
                rs.getLong("supplier_store_id"),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("provider_code"),
                rs.getString("provider_delivery_id"),
                rs.getBigDecimal("fee"),
                rs.getString("currency"),
                rs.getString("vehicle_type"),
                rs.getBigDecimal("weight_kg"),
                rs.getBigDecimal("volume_cbm"),
                rs.getString("driver_name"),
                rs.getString("driver_phone"),
                rs.getObject("eta_minutes") == null ? null : rs.getInt("eta_minutes"),
                rs.getInt("attempt_count"),
                rs.getString("failure_code"),
                rs.getString("failure_reason"),
                rs.getTimestamp("requested_at") != null ? rs.getTimestamp("requested_at").toInstant() : null,
                rs.getTimestamp("booked_at") != null ? rs.getTimestamp("booked_at").toInstant() : null,
                rs.getTimestamp("assigned_at") != null ? rs.getTimestamp("assigned_at").toInstant() : null,
                rs.getTimestamp("picked_up_at") != null ? rs.getTimestamp("picked_up_at").toInstant() : null,
                rs.getTimestamp("delivered_at") != null ? rs.getTimestamp("delivered_at").toInstant() : null,
                rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null
        ), params.toArray());
    }

    public static void writeCsv(List<DeliveryExportRow> rows, PrintWriter writer) {
        writer.println("deliveryId,supplierOrderId,outletId,supplierStoreId,mode,status,providerCode,providerDeliveryId,fee,currency,vehicleType,weightKg,volumeCbm,driverName,driverPhone,etaMinutes,attemptCount,failureCode,failureReason,requestedAt,bookedAt,assignedAt,pickedUpAt,deliveredAt,cancelledAt");
        for (DeliveryExportRow r : rows) {
            writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    r.deliveryId(),
                    r.supplierOrderId(),
                    r.outletId(),
                    r.supplierStoreId(),
                    escapeCsv(r.mode()),
                    escapeCsv(r.status()),
                    escapeCsv(r.providerCode()),
                    escapeCsv(r.providerDeliveryId()),
                    r.fee(),
                    escapeCsv(r.currency()),
                    escapeCsv(r.vehicleType()),
                    r.weightKg(),
                    r.volumeCbm(),
                    escapeCsv(r.driverName()),
                    escapeCsv(r.driverPhone()),
                    r.etaMinutes() != null ? r.etaMinutes() : "",
                    r.attemptCount(),
                    escapeCsv(r.failureCode()),
                    escapeCsv(r.failureReason()),
                    r.requestedAt() != null ? r.requestedAt().toString() : "",
                    r.bookedAt() != null ? r.bookedAt().toString() : "",
                    r.assignedAt() != null ? r.assignedAt().toString() : "",
                    r.pickedUpAt() != null ? r.pickedUpAt().toString() : "",
                    r.deliveredAt() != null ? r.deliveredAt().toString() : "",
                    r.cancelledAt() != null ? r.cancelledAt().toString() : ""
            );
        }
        writer.flush();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
