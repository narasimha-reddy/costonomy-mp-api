package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for derived metric calculations on {@link DeliveryProviderStats}.
 *
 * <p>These are the numbers that will eventually feed {@link
 * com.costonomy.mp.delivery.domain.DeliverySelection} — getting the maths
 * right here is more important than it looks.
 */
class DeliveryProviderStatsTest {

    // -----------------------------------------------------------------------
    // cancellationRate()
    // -----------------------------------------------------------------------

    @Test
    void cancellationRate_noBookings_returnsZero() {
        var stats = statsWith(0, 0, 0, 0, 0, 0);
        assertThat(stats.cancellationRate()).isEqualTo(0.0);
    }

    @Test
    void cancellationRate_calculatesCorrectly() {
        // 3 cancellations out of 10 bookings = 30 %
        var stats = statsWith(10, 3, 0, 0, 0, 0);
        assertThat(stats.cancellationRate()).isCloseTo(0.30, within(0.001));
    }

    @Test
    void cancellationRate_allCancelled_returnsOne() {
        var stats = statsWith(5, 5, 0, 0, 0, 0);
        assertThat(stats.cancellationRate()).isCloseTo(1.0, within(0.001));
    }

    // -----------------------------------------------------------------------
    // etaBreachRate()
    // -----------------------------------------------------------------------

    @Test
    void etaBreachRate_noCompletions_returnsZero() {
        var stats = statsWith(10, 0, 0, 0, 0, 0);
        assertThat(stats.etaBreachRate()).isEqualTo(0.0);
    }

    @Test
    void etaBreachRate_calculatesCorrectly() {
        // 2 overruns out of 8 completed = 25 %
        var stats = statsWithCompletions(8, 2);
        assertThat(stats.etaBreachRate()).isCloseTo(0.25, within(0.001));
    }

    // -----------------------------------------------------------------------
    // overallFailureRate()
    // -----------------------------------------------------------------------

    @Test
    void overallFailureRate_noBookings_returnsZero() {
        var stats = statsWith(0, 0, 0, 0, 0, 0);
        assertThat(stats.overallFailureRate()).isEqualTo(0.0);
    }

    @Test
    void overallFailureRate_sumsCancellationsPickupAndDeliveryFailures() {
        // 2 + 1 + 1 = 4 failures out of 10 bookings = 40 %
        var stats = statsWith(10, 2, 1, 1, 0, 0);
        assertThat(stats.overallFailureRate()).isCloseTo(0.40, within(0.001));
    }

    @Test
    void overallFailureRate_perfectProvider_returnsZero() {
        var stats = statsWith(20, 0, 0, 0, 0, 0);
        assertThat(stats.overallFailureRate()).isEqualTo(0.0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static DeliveryProviderStats statsWith(
            int totalBookings, int driverCancellations, int pickupFailures,
            int deliveryFailures, int etaOverruns, int completedDeliveries) {

        var s = new DeliveryProviderStats();
        s.setProviderCode("TEST_PROVIDER");
        s.setWindowDate(LocalDate.of(2026, 9, 1));
        s.setTotalBookings(totalBookings);
        s.setDriverCancellations(driverCancellations);
        s.setPickupFailures(pickupFailures);
        s.setDeliveryFailures(deliveryFailures);
        s.setEtaOverruns(etaOverruns);
        s.setCompletedDeliveries(completedDeliveries);
        return s;
    }

    private static DeliveryProviderStats statsWithCompletions(int completed, int etaOverruns) {
        return statsWith(completed, 0, 0, 0, etaOverruns, completed);
    }
}
