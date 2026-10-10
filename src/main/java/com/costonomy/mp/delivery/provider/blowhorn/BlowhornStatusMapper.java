package com.costonomy.mp.delivery.provider.blowhorn;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps Blowhorn status strings onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>Lifecycle mapping:
 * <ul>
 *   <li>{@code created}, {@code pending}, {@code accepted}, {@code booked}, {@code manifest_created} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code assigned}, {@code driver_assigned}, {@code pilot_assigned}, {@code allocated} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code arrived_at_pickup}, {@code reached_pickup}, {@code at_pickup} -> {@link ProviderDeliveryStatus#DRIVER_AT_PICKUP}</li>
 *   <li>{@code picked_up}, {@code pickup_done} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code in_transit}, {@code out_for_delivery}, {@code dispatched} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code reached_drop}, {@code arrived_at_drop}, {@code reached_destination} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code delivered}, {@code completed} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code cancelled}, {@code canceled} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code failed}, {@code returned}, {@code rto}, {@code undelivered}, {@code pickup_failed} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class BlowhornStatusMapper {

    private BlowhornStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        String normalized = status.toLowerCase(Locale.ROOT).trim().replace(" ", "_").replace("-", "_");
        return switch (normalized) {
            case "created", "pending", "accepted", "booked", "manifest_created", "order_created" ->
                    ProviderDeliveryStatus.PENDING;
            case "assigned", "driver_assigned", "pilot_assigned", "allocated" ->
                    ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "arrived_at_pickup", "reached_pickup", "at_pickup" ->
                    ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "picked_up", "pickup_done" ->
                    ProviderDeliveryStatus.PICKED_UP;
            case "in_transit", "out_for_delivery", "dispatched" ->
                    ProviderDeliveryStatus.IN_TRANSIT;
            case "reached_drop", "arrived_at_drop", "reached_destination" ->
                    ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "delivered", "completed" ->
                    ProviderDeliveryStatus.DELIVERED;
            case "cancelled", "canceled" ->
                    ProviderDeliveryStatus.CANCELLED;
            case "failed", "returned", "rto", "undelivered", "pickup_failed" ->
                    ProviderDeliveryStatus.DELIVERY_FAILED;
            default ->
                    ProviderDeliveryStatus.PENDING;
        };
    }
}
