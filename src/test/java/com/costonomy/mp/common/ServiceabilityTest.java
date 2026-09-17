package com.costonomy.mp.common;

import com.costonomy.mp.common.domain.Serviceability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceabilityTest {

    // Two real Hyderabad points about 7 km apart.
    private static final BigDecimal BANJARA_LAT = new BigDecimal("17.4156");
    private static final BigDecimal BANJARA_LON = new BigDecimal("78.4347");
    private static final BigDecimal SECUNDERABAD_LAT = new BigDecimal("17.4399");
    private static final BigDecimal SECUNDERABAD_LON = new BigDecimal("78.4983");

    @Test
    @DisplayName("distance between two city points is plausible")
    void distanceIsPlausible() {
        Double km = Serviceability.distanceKm(
                BANJARA_LAT, BANJARA_LON, SECUNDERABAD_LAT, SECUNDERABAD_LON);

        assertThat(km).isBetween(6.0, 8.0);
    }

    @Test
    @DisplayName("distance to the same point is zero")
    void sameePointIsZero() {
        assertThat(Serviceability.distanceKm(BANJARA_LAT, BANJARA_LON, BANJARA_LAT, BANJARA_LON))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("distance is symmetric")
    void distanceIsSymmetric() {
        Double there = Serviceability.distanceKm(
                BANJARA_LAT, BANJARA_LON, SECUNDERABAD_LAT, SECUNDERABAD_LON);
        Double back = Serviceability.distanceKm(
                SECUNDERABAD_LAT, SECUNDERABAD_LON, BANJARA_LAT, BANJARA_LON);

        assertThat(there).isEqualTo(back);
    }

    @Test
    @DisplayName("an unknown coordinate yields an unknown distance, not zero")
    void missingCoordinatesAreUnknown() {
        // Zero would read as "next door" and put an ungeocoded store top of every
        // ranking.
        assertThat(Serviceability.distanceKm(null, BANJARA_LON, SECUNDERABAD_LAT, SECUNDERABAD_LON))
                .isNull();
        assertThat(Serviceability.distanceKm(BANJARA_LAT, BANJARA_LON, null, null)).isNull();
    }

    @Test
    @DisplayName("ETA is preparation plus transit plus dispatch overhead")
    void etaCombinesPreparationAndTransit() {
        // 60 min prep + 15 min dispatch + 7 km at 20 km/h ≈ 21 min transit.
        Integer eta = Serviceability.estimateMinutes(60, 7.0, new BigDecimal("20"), 15);

        assertThat(eta).isBetween(90, 100);
    }

    @Test
    @DisplayName("a longer distance gives a longer ETA")
    void etaGrowsWithDistance() {
        Integer near = Serviceability.estimateMinutes(60, 2.0, new BigDecimal("20"), 15);
        Integer far = Serviceability.estimateMinutes(60, 20.0, new BigDecimal("20"), 15);

        assertThat(far).isGreaterThan(near);
    }

    @Test
    @DisplayName("an unknown distance yields an unknown ETA")
    void unknownDistanceGivesUnknownEta() {
        // The scorer treats a null ETA as absent rather than slow, which only
        // works if this returns null instead of guessing.
        assertThat(Serviceability.estimateMinutes(60, null, new BigDecimal("20"), 15)).isNull();
    }

    @Test
    @DisplayName("a nonsensical speed yields an unknown ETA rather than dividing by zero")
    void guardsAgainstBadConfiguration() {
        assertThat(Serviceability.estimateMinutes(60, 7.0, BigDecimal.ZERO, 15)).isNull();
        assertThat(Serviceability.estimateMinutes(60, 7.0, null, 15)).isNull();
    }
}
