package com.costonomy.mp.delivery.provider.loadshare;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps LoadShare Hyperlocal status codes onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>LoadShare status lifecycle:
 * <ul>
 *   <li>{@code created}, {@code pending}, {@code accepted}, {@code unassigned} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code assigned}, {@code allocated}, {@code rider_assigned} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code arrived_at_pickup}, {@code at_pickup}, {@code reached_store} -> {@link ProviderDeliveryStatus#DRIVER_AT_PICKUP}</li>
 *   <li>{@code picked_up}, {@code pickup_complete} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code in_transit}, {@code out_for_delivery}, {@code dispatched} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code reached_drop}, {@code arrived_at_destination}, {@code at_destination} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code delivered}, {@code completed} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code cancelled}, {@code canceled} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code failed}, {@code returned}, {@code rto}, {@code pickup_failed} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class LoadshareStatusMapper {

    private LoadshareStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        String normalized = status.toLowerCase(Locale.ROOT).trim().replace(" ", "_").replace("-", "_");
        return switch (normalized) {
            case "created", "pending", "accepted", "unassigned" ->
                    ProviderDeliveryStatus.PENDING;
            case "assigned", "allocated", "rider_assigned" ->
                    ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "arrived_at_pickup", "at_pickup", "reached_store" ->
                    ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "picked_up", "pickup_complete" ->
                    ProviderDeliveryStatus.PICKED_UP;
            case "in_transit", "out_for_delivery", "dispatched" ->
                    ProviderDeliveryStatus.IN_TRANSIT;
            case "reached_drop", "arrived_at_destination", "at_destination" ->
                    ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "delivered", "completed" ->
                    ProviderDeliveryStatus.DELIVERED;
            case "cancelled", "canceled" ->
                    ProviderDeliveryStatus.CANCELLED;
            case "failed", "returned", "rto", "pickup_failed" ->
                    ProviderDeliveryStatus.DELIVERY_FAILED;
            default ->
                    ProviderDeliveryStatus.PENDING;
        };
    }
}
