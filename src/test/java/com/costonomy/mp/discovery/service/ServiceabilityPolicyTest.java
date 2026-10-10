package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.config.AppConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceabilityPolicyTest {

    private AppConfigService config;
    private ServiceabilityPolicy policy;

    @BeforeEach
    void setUp() {
        config = mock(AppConfigService.class);
        when(config.getDecimal("serviceability.defaultRadiusKm", ServiceabilityPolicy.FALLBACK_DEFAULT_RADIUS_KM))
                .thenReturn(BigDecimal.valueOf(25));
        policy = new ServiceabilityPolicy(config);
    }

    @Test
    @DisplayName("pincode list wins even if distance is within or outside radius")
    void pincodeWins() {
        // Pincode matches: serviceable even if distance is 100km and store radius is 10km
        assertThat(policy.serves(Set.of("500034", "500033"), BigDecimal.valueOf(10), "500034", 100.0)).isTrue();

        // Pincode does not match: unserviceable even if distance is 1km and store radius is 50km
        assertThat(policy.serves(Set.of("500034"), BigDecimal.valueOf(50), "500081", 1.0)).isFalse();

        // Outlet pincode is null: unserviceable when store has a declared pincode list
        assertThat(policy.serves(Set.of("500034"), BigDecimal.valueOf(50), null, 1.0)).isFalse();
    }

    @Test
    @DisplayName("store's own radius decides when no pincode list declared")
    void storeRadiusDecides() {
        // No pincodes declared, store radius 15km
        assertThat(policy.serves(null, BigDecimal.valueOf(15), "500034", 14.9)).isTrue();
        assertThat(policy.serves(List.of(), BigDecimal.valueOf(15), "500034", 15.0)).isTrue();
        assertThat(policy.serves(List.of(), BigDecimal.valueOf(15), "500034", 15.1)).isFalse();
    }

    @Test
    @DisplayName("configured default radius applies when store declared neither pincode nor radius")
    void defaultRadiusApplies() {
        when(config.getDecimal("serviceability.defaultRadiusKm", ServiceabilityPolicy.FALLBACK_DEFAULT_RADIUS_KM))
                .thenReturn(BigDecimal.valueOf(30));

        // 28km is within configured default of 30km
        assertThat(policy.serves(null, null, "500034", 28.0)).isTrue();
        // 32km is outside configured default of 30km
        assertThat(policy.serves(null, null, "500034", 32.0)).isFalse();
    }

    @Test
    @DisplayName("missing coordinates on either side means serviceable")
    void missingCoordinatesMeansServiceable() {
        // distanceKm is null (e.g. store or outlet missing coordinates)
        assertThat(policy.serves(null, BigDecimal.valueOf(10), "500034", null)).isTrue();
        assertThat(policy.serves(null, null, "500034", null)).isTrue();
    }
}
