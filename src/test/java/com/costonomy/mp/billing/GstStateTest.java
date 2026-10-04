package com.costonomy.mp.billing;

import com.costonomy.mp.billing.service.GstState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GstStateTest {

    @Test
    @DisplayName("the state of a GSTIN is its first two digits; an unknown code gives nothing, never a default")
    void fromGstin() {
        assertThat(GstState.ofGstin("36AABCU9603R1ZX")).contains(GstState.TELANGANA);
        assertThat(GstState.ofGstin("29AABCU9603R1ZX")).contains(GstState.KARNATAKA);
        assertThat(GstState.ofGstin("99AABCU9603R1ZX")).isEmpty();
        assertThat(GstState.ofGstin("XXAABCU9603R1ZX")).isEmpty();
        assertThat(GstState.ofGstin("3")).isEmpty();
        assertThat(GstState.ofGstin(null)).isEmpty();
    }

    @Test
    @DisplayName("a state name matches ignoring case and punctuation, with known aliases; an unknown name gives nothing")
    void fromName() {
        assertThat(GstState.ofName("Telangana")).contains(GstState.TELANGANA);
        assertThat(GstState.ofName("  TELANGANA ")).contains(GstState.TELANGANA);
        assertThat(GstState.ofName("Orissa")).contains(GstState.ODISHA);
        assertThat(GstState.ofName("Jammu & Kashmir")).contains(GstState.JAMMU_AND_KASHMIR);
        assertThat(GstState.ofName("Atlantis")).isEmpty();
        assertThat(GstState.ofName("")).isEmpty();
        assertThat(GstState.ofName(null)).isEmpty();
    }

    @Test
    @DisplayName("codes are unique two-digit values and the place of supply reads 'NN-State'")
    void codes() {
        var codes = Arrays.stream(GstState.values()).map(GstState::code).collect(Collectors.toList());
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("\\d{2}"));
        assertThat(GstState.TELANGANA.placeOfSupply()).isEqualTo("36-Telangana");
    }
}
