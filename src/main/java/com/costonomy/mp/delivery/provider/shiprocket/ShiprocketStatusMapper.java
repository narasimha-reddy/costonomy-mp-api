package com.costonomy.mp.delivery.provider.shiprocket;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;

import java.util.Locale;

/**
 * Maps Shiprocket tracking statuses onto Costonomy's {@link ProviderDeliveryStatus}.
 *
 * <p>Shiprocket tracking activities and statuses:
 * <ul>
 *   <li>{@code new}, {@code order_created}, {@code manifest_generated}, {@code pickup_scheduled}, {@code pickup_generated}, {@code pending} -> {@link ProviderDeliveryStatus#PENDING}</li>
 *   <li>{@code out_for_pickup}, {@code pickup_queued}, {@code pickup_rescheduled}, {@code assigned} -> {@link ProviderDeliveryStatus#DRIVER_ASSIGNED}</li>
 *   <li>{@code reached_pickup_location}, {@code driver_arrived} -> {@link ProviderDeliveryStatus#DRIVER_AT_PICKUP}</li>
 *   <li>{@code picked_up}, {@code pickup_done} -> {@link ProviderDeliveryStatus#PICKED_UP}</li>
 *   <li>{@code in_transit}, {@code shipped}, {@code reached_at_destination_hub} -> {@link ProviderDeliveryStatus#IN_TRANSIT}</li>
 *   <li>{@code arrived_at_destination}, {@code out_for_delivery} -> {@link ProviderDeliveryStatus#ARRIVED_AT_DESTINATION}</li>
 *   <li>{@code delivered} -> {@link ProviderDeliveryStatus#DELIVERED}</li>
 *   <li>{@code canceled}, {@code cancelled} -> {@link ProviderDeliveryStatus#CANCELLED}</li>
 *   <li>{@code rto_initiated}, {@code rto_delivered}, {@code lost}, {@code damaged}, {@code undelivered}, {@code failed} -> {@link ProviderDeliveryStatus#DELIVERY_FAILED}</li>
 * </ul>
 */
public final class ShiprocketStatusMapper {

    private ShiprocketStatusMapper() {
    }

    public static ProviderDeliveryStatus map(String status) {
        if (status == null) {
            return ProviderDeliveryStatus.PENDING;
        }

        String normalized = status.toLowerCase(Locale.ROOT).trim().replace(" ", "_").replace("-", "_");
        return switch (normalized) {
            case "new", "order_created", "manifest_generated", "pickup_scheduled", "pickup_generated", "pending" ->
                    ProviderDeliveryStatus.PENDING;
            case "out_for_pickup", "pickup_queued", "pickup_rescheduled", "assigned" ->
                    ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "reached_pickup_location", "driver_arrived" ->
                    ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "picked_up", "pickup_done" ->
                    ProviderDeliveryStatus.PICKED_UP;
            case "in_transit", "shipped", "reached_at_destination_hub" ->
                    ProviderDeliveryStatus.IN_TRANSIT;
            case "arrived_at_destination", "out_for_delivery" ->
                    ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "delivered" ->
                    ProviderDeliveryStatus.DELIVERED;
            case "canceled", "cancelled" ->
                    ProviderDeliveryStatus.CANCELLED;
            case "rto_initiated", "rto_delivered", "lost", "damaged", "undelivered", "failed" ->
                    ProviderDeliveryStatus.DELIVERY_FAILED;
            default ->
                    ProviderDeliveryStatus.PENDING;
        };
    }
}
