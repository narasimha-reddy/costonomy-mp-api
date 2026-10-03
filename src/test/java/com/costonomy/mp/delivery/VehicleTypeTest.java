package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleTypeTest {

    @Test
    @DisplayName("null or zero weight defaults to 2-wheeler")
    void defaultTwoWheeler() {
        assertThat(VehicleType.fromWeight(null)).isEqualTo(VehicleType.TWO_WHEELER);
        assertThat(VehicleType.fromWeight(BigDecimal.ZERO)).isEqualTo(VehicleType.TWO_WHEELER);
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(-5))).isEqualTo(VehicleType.TWO_WHEELER);
    }

    @Test
    @DisplayName("up to 20kg resolves to 2-wheeler")
    void upToTwentyKg() {
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(5))).isEqualTo(VehicleType.TWO_WHEELER);
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(20))).isEqualTo(VehicleType.TWO_WHEELER);
    }

    @Test
    @DisplayName("20kg to 100kg resolves to 3-wheeler")
    void betweenTwentyAndHundredKg() {
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(20.5))).isEqualTo(VehicleType.THREE_WHEELER);
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(50))).isEqualTo(VehicleType.THREE_WHEELER);
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(100))).isEqualTo(VehicleType.THREE_WHEELER);
    }

    @Test
    @DisplayName("above 100kg resolves to 4-wheeler truck")
    void aboveHundredKg() {
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(100.1))).isEqualTo(VehicleType.FOUR_WHEELER_TRUCK);
        assertThat(VehicleType.fromWeight(BigDecimal.valueOf(500))).isEqualTo(VehicleType.FOUR_WHEELER_TRUCK);
    }
}
