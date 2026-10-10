package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Borzo's status onto ours. Doc 06 §5.
 *
 * <p>Borzo reports status in two layers — a top-level {@code order.status}
 * ({@code new}/{@code available}/{@code active}/{@code delayed}/
 * {@code canceled}/{@code completed}/{@code failed}) and a nested
 * {@code points[].delivery.status} on the drop point, where only
 * {@code planned} and {@code canceled} have been observed live. Neither
 * vocabulary distinguishes "courier assigned" from "picked up" from
 * "en route" the way {@link ProviderDeliveryStatus} can — the sandbox never
 * produced an order detailed enough to tell them apart.
 *
 * <p><b>Deliberately conservative.</b> {@code active}/{@code delayed} map to
 * {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}, the least-advanced state
 * consistent with "a courier has taken this" — never
 * {@code PICKED_UP}/{@code ARRIVED_AT_DESTINATION}, which this top-level field
 * cannot actually tell us. Doc 06 §13's out-of-order guard means under-mapping
 * only delays an advance the next real event still supplies; over-mapping would
 * assert a milestone — a pickup, an arrival — that may not have happened.
 */
public final class BorzoStatusMapper {

    private BorzoStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String orderStatus, String dropPointDeliveryStatus) {
        if (orderStatus == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        return switch (orderStatus.toLowerCase(Locale.ROOT).trim()) {
            case "new", "available" -> ProviderDeliveryStatus.PENDING;
            case "active", "delayed" -> "canceled".equalsIgnoreCase(dropPointDeliveryStatus)
                    ? ProviderDeliveryStatus.CANCELLED
                    : ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "completed" -> ProviderDeliveryStatus.DELIVERED;
            case "canceled" -> ProviderDeliveryStatus.CANCELLED;
            case "failed" -> ProviderDeliveryStatus.DELIVERY_FAILED;
            // An order.status Borzo has not been observed to send. PENDING rather
            // than guessing keeps an unrecognised value from silently advancing —
            // or regressing — the delivery doc 06 §13 protects.
            default -> ProviderDeliveryStatus.PENDING;
        };
    }
}
