package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;

import java.util.Optional;

/**
 * The Pidge sandbox has no riders, so a test moves a delivery along by asking Pidge's own dummy endpoint for the
 * next stage (D-188). This is the table of which stage follows which delivery status. Pure and test-only: nothing
 * here is reachable unless {@link PidgeProperties#isSandbox()} is on.
 */
public final class PidgeSandboxStages {

    private PidgeSandboxStages() {
    }

    /** The Pidge {@code dummy_status} that moves a delivery in this status forward one step. */
    public static Optional<String> nextDummyStatus(DeliveryStatus status) {
        return Optional.ofNullable(switch (status) {
            case PROVIDER_SELECTED -> "fulfilled|out for pickup";
            case DRIVER_ASSIGNED -> "fulfilled|reached pickup";
            case DRIVER_AT_PICKUP -> "fulfilled|picked up";
            case PICKED_UP -> "fulfilled|ofd";
            case IN_TRANSIT -> "fulfilled|reached delivery";
            case ARRIVED_AT_DESTINATION -> "fulfilled|delivered";
            default -> null;
        });
    }

    /** True when the sandbox controls apply to this delivery: Pidge, sandbox on, our own booking, a next stage. */
    public static boolean applies(PidgeProperties properties, Delivery delivery) {
        return properties.isSandbox()
                && isPidge(delivery)
                && delivery.getProviderDeliveryId() != null
                && delivery.getMode() == DeliveryMode.COSTONOMY
                && nextDummyStatus(delivery.getStatus()).isPresent();
    }

    public static boolean isPidge(Delivery delivery) {
        return PidgeDeliveryProvider.CODE.equals(delivery.getProviderCode());
    }
}
