package com.costonomy.mp.delivery.provider.shadowfax;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps Shadowfax status strings onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>Shadowfax uses status codes such as:
 * <ul>
 *   <li>{@code new} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code assigned_for_delivery}, {@code assigned} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code received_from_client_warehouse}, {@code pickup_done}, {@code picked_up} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code recd_at_fwd_dc}, {@code item_manifested}, {@code bag_in_transit}, {@code bag_received},
 *       {@code recd_at_fwd_hub}, {@code ofd}, {@code in_transit} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code arrived_at_destination} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code delivered}, {@code delivered to customer} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code cancelled}, {@code rto_initiated}, {@code rto_delivered} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code failed}, {@code undelivered} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class ShadowfaxStatusMapper {

    private ShadowfaxStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        return switch (status.toLowerCase(Locale.ROOT).trim()) {
            case "new" -> ProviderDeliveryStatus.PENDING;
            case "assigned_for_delivery", "assigned" -> ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "received_from_client_warehouse", "pickup_done", "picked_up" -> ProviderDeliveryStatus.PICKED_UP;
            case "recd_at_fwd_dc", "item_manifested", "bag_in_transit", "bag_received",
                 "recd_at_fwd_hub", "in_transit", "ofd", "out_for_delivery" -> ProviderDeliveryStatus.IN_TRANSIT;
            case "arrived_at_destination" -> ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "delivered", "delivered to customer" -> ProviderDeliveryStatus.DELIVERED;
            case "cancelled", "rto_initiated", "rto_delivered" -> ProviderDeliveryStatus.CANCELLED;
            case "failed", "undelivered", "delivery_failed" -> ProviderDeliveryStatus.DELIVERY_FAILED;
            default -> ProviderDeliveryStatus.PENDING;
        };
    }
}
