package com.costonomy.mp.fooddelivery.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Timings of food delivery (D-197): hot food does not wait the way raw material does. */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.food-delivery")
@Getter
@Setter
public class FoodDeliveryProperties {

    /**
     * How long a delivery keeps looking for a courier after it was requested. Past it the delivery ends {@code FAILED}
     * (NO_COURIER) and the fee is refunded. Supplier delivery retries for 30 minutes and then offers to deliver
     * themselves; neither suits a hot meal.
     */
    private Duration retryWindow = Duration.ofMinutes(5);

    /** How long a booked courier has to send a rider before the next one is tried. A bike, as food always is. */
    private Duration assignmentTimeout = Duration.ofMinutes(3);

    /** Slack past the assignment deadline before the sweep acts, so the live path wins when it can. */
    private Duration sweepGrace = Duration.ofSeconds(30);

    /** An attempt left PENDING this long is taken to be abandoned (the process died mid-call) and no longer blocks booking. */
    private Duration pendingStaleAfter = Duration.ofMinutes(2);

    /**
     * How long a delivery waits for the restaurant to pay at the gateway. Past it the sweep cancels it; a payment that
     * still arrives later is refunded to its source rather than stranded.
     */
    private Duration paymentExpiry = Duration.ofMinutes(30);

    /** Off switch for the retry and sweep jobs. */
    private boolean jobsEnabled = true;
}
