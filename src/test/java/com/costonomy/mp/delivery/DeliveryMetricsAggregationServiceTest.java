package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.service.DeliveryMetricsAggregationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryMetricsAggregationServiceTest {

    @Test
    @DisplayName("P95 calculation on empty or null values returns 0.0")
    void p95EmptyOrNull() {
        assertThat(DeliveryMetricsAggregationService.calculateP95(null)).isEqualTo(0.0);
        assertThat(DeliveryMetricsAggregationService.calculateP95(List.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("P95 calculation with single value returns that value")
    void p95SingleValue() {
        assertThat(DeliveryMetricsAggregationService.calculateP95(List.of(1500L))).isEqualTo(1500.0);
    }

    @Test
    @DisplayName("P95 calculation computes 95th percentile correctly")
    void p95MultipleValues() {
        // 100 values from 1 to 100
        List<Long> values = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        double p95 = DeliveryMetricsAggregationService.calculateP95(values);
        assertThat(p95).isEqualTo(95.0);
    }

    @Test
    @DisplayName("P95 calculation on small dataset")
    void p95SmallDataset() {
        List<Long> values = List.of(100L, 200L, 300L, 400L, 500L);
        // size=5, 0.95*5 = 4.75 -> ceil is 5 -> index 4 -> 500L
        double p95 = DeliveryMetricsAggregationService.calculateP95(values);
        assertThat(p95).isEqualTo(500.0);
    }
}
