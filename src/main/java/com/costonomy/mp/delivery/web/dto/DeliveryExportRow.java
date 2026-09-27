package com.costonomy.mp.delivery.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single flattened row representation for admin delivery bulk exports (CSV / JSON).
 */
public record DeliveryExportRow(
        Long deliveryId,
        Long supplierOrderId,
        Long outletId,
        Long supplierStoreId,
        String mode,
        String status,
        String providerCode,
        String providerDeliveryId,
        BigDecimal fee,
        String currency,
        String vehicleType,
        BigDecimal weightKg,
        BigDecimal volumeCbm,
        String driverName,
        String driverPhone,
        Integer etaMinutes,
        Integer attemptCount,
        String failureCode,
        String failureReason,
        Instant requestedAt,
        Instant bookedAt,
        Instant assignedAt,
        Instant pickedUpAt,
        Instant deliveredAt,
        Instant cancelledAt
) {}
