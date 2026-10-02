package com.costonomy.mp.delivery.provider.xpressbees;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps Xpressbees status codes onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>Lifecycle mapping:
 * <ul>
 *   <li>{@code manifested}, {@code booked}, {@code order_created}, {@code pending} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code pickup_scheduled}, {@code pickup_assigned}, {@code assigned} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code reached_pickup}, {@code at_pickup} -> {@link ProviderDeliveryStatus#DRIVER_AT_PICKUP}</li>
 *   <li>{@code picked_up}, {@code pickup_done}, {@code picked} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code in_transit}, {@code dispatched}, {@code transit} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code out_for_delivery}, {@code reached_destination}, {@code arrived_at_destination} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code delivered}, {@code completed}, {@code closed} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code cancelled}, {@code canceled} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code rto}, {@code rto_initiated}, {@code returned}, {@code failed}, {@code undelivered}, {@code lost}, {@code damaged} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class XpressbeesStatusMapper {

    private XpressbeesStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        String normalized = status.toLowerCase(Locale.ROOT).trim().replace(" ", "_").replace("-", "_");
        return switch (normalized) {
            case "manifested", "booked", "order_created", "pending", "open" ->
                    ProviderDeliveryStatus.PENDING;
            case "pickup_scheduled", "pickup_assigned", "assigned", "driver_assigned" ->
                    ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "reached_pickup", "at_pickup" ->
                    ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "picked_up", "pickup_done", "picked" ->
                    ProviderDeliveryStatus.PICKED_UP;
            case "in_transit", "dispatched", "transit" ->
                    ProviderDeliveryStatus.IN_TRANSIT;
            case "out_for_delivery", "reached_destination", "arrived_at_destination" ->
                    ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "delivered", "completed", "closed" ->
                    ProviderDeliveryStatus.DELIVERED;
            case "cancelled", "canceled" ->
                    ProviderDeliveryStatus.CANCELLED;
            case "rto", "rto_initiated", "returned", "failed", "undelivered", "lost", "damaged" ->
                    ProviderDeliveryStatus.DELIVERY_FAILED;
            default ->
                    ProviderDeliveryStatus.PENDING;
        };
    }
}
