package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages;
import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages.Rider;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** D-195: the sandbox rider gets a believable, made-up identity instead of Pidge's "Rider name" placeholder. */
class PidgeSandboxRiderTest {

    private static final List<String> NAMES =
            List.of("Ravi Kumar", "Imran Sheikh", "Suresh Babu", "Anil Reddy", "Manoj Yadav");

    @Test
    void theSameDeliveryAlwaysGetsTheSameRider() {
        assertThat(PidgeSandboxStages.rider(4821L)).isEqualTo(PidgeSandboxStages.rider(4821L));
        assertThat(PidgeSandboxStages.rider(7L)).isEqualTo(PidgeSandboxStages.rider(7L));
    }

    @Test
    void theRiderLooksRealButIsClearlyMadeUp() {
        for (long id = 1; id <= 200; id++) {
            Rider rider = PidgeSandboxStages.rider(id);
            assertThat(NAMES).contains(rider.name());
            assertThat(rider.name()).isNotEqualTo("Rider name");
            // A 10-digit Indian mobile starting with 9, in the obviously fake 90000 range.
            assertThat(rider.phone()).matches("90000\\d{5}");
            assertThat(rider.vehicle()).matches("Bike, KA 01 EX \\d{4}");
        }
    }

    @Test
    void differentDeliveriesGetDifferentRiders() {
        Set<String> phones = new HashSet<>();
        Set<String> plates = new HashSet<>();
        for (long id = 1; id <= 500; id++) {
            phones.add(PidgeSandboxStages.rider(id).phone());
            plates.add(PidgeSandboxStages.rider(id).vehicle());
            assertThat(PidgeSandboxStages.rider(id).name()).isNotEqualTo(PidgeSandboxStages.rider(id + 1).name());
        }
        assertThat(phones).hasSize(500);
        assertThat(plates).hasSize(500);
        assertThat(PidgeSandboxStages.rider(1L)).isNotEqualTo(PidgeSandboxStages.rider(2L));
    }

    @Test
    void largeIdsStayInShape() {
        Rider rider = PidgeSandboxStages.rider(Long.MAX_VALUE);
        assertThat(rider.phone()).matches("90000\\d{5}");
        assertThat(rider.vehicle()).matches("Bike, KA 01 EX \\d{4}");
        assertThat(NAMES).contains(rider.name());
    }
}
