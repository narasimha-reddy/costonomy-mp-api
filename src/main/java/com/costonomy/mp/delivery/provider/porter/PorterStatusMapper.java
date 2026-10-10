package com.costonomy.mp.delivery.provider.porter;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps Porter status strings onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>Porter uses status codes such as:
 * <ul>
 *   <li>{@code created}, {@code order_created}, {@code allocating}, {@code searching_for_partner} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code assigned}, {@code driver_assigned}, {@code partner_assigned}, {@code accepted} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code driver_arrived}, {@code partner_arrived}, {@code arrived_at_pickup} -> {@link ProviderDeliveryStatus#DRIVER_AT_PICKUP}</li>
 *   <li>{@code started}, {@code picked_up}, {@code pickup_complete} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code in_transit}, {@code live}, {@code out_for_delivery} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code arrived_at_drop}, {@code arrived_at_destination} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code completed}, {@code delivered}, {@code order_ended} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code cancelled}, {@code canceled} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code driver_cancelled} -> {@link ProviderDeliveryStatus#DRIVER_CANCELLED}</li>
 *   <li>{@code pickup_failed} -> {@link ProviderDeliveryStatus#PICKUP_FAILED}</li>
 *   <li>{@code failed}, {@code delivery_failed}, {@code unassigned} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class PorterStatusMapper {

    private PorterStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        return switch (status.toLowerCase(Locale.ROOT).trim()) {
            case "created", "order_created", "allocating", "searching_for_partner", "pending" -> ProviderDeliveryStatus.PENDING;
            case "assigned", "driver_assigned", "partner_assigned", "accepted" -> ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "driver_arrived", "partner_arrived", "arrived_at_pickup" -> ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "started", "picked_up", "pickup_complete" -> ProviderDeliveryStatus.PICKED_UP;
            case "in_transit", "live", "out_for_delivery" -> ProviderDeliveryStatus.IN_TRANSIT;
            case "arrived_at_drop", "arrived_at_destination" -> ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "completed", "delivered", "order_ended" -> ProviderDeliveryStatus.DELIVERED;
            case "cancelled", "canceled" -> ProviderDeliveryStatus.CANCELLED;
            case "driver_cancelled" -> ProviderDeliveryStatus.DRIVER_CANCELLED;
            case "pickup_failed" -> ProviderDeliveryStatus.PICKUP_FAILED;
            case "failed", "delivery_failed", "unassigned" -> ProviderDeliveryStatus.DELIVERY_FAILED;
            default -> ProviderDeliveryStatus.PENDING;
        };
    }
}
