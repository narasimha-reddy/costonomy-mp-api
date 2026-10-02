package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.domain.DeliveryQuote;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryQuotingServiceTest {

    @Mock
    private DeliveryProviderRegistry registry;

    @Mock
    private DeliveryQuoteRepository quotes;

    @Mock
    private DeliveryProvider mockAdapter;

    private DeliveryQuotingService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryQuotingService(registry, quotes);
        ReflectionTestUtils.setField(service, "maxRadiusKm", 30.0);
    }

    private Delivery buildDelivery(double pickupLat, double pickupLng, double dropLat, double dropLng) {
        var delivery = new Delivery();
        delivery.setId(100L);
        delivery.setSupplierOrderId(200L);
        delivery.setVehicleType(VehicleType.TWO_WHEELER);
        delivery.setWeightKg(BigDecimal.valueOf(5.0));
        delivery.setPickupAddress("Indiranagar, Bengaluru");
        delivery.setPickupLatitude(BigDecimal.valueOf(pickupLat));
        delivery.setPickupLongitude(BigDecimal.valueOf(pickupLng));
        delivery.setDropAddress("Hosur / Koramangala");
        delivery.setDropLatitude(BigDecimal.valueOf(dropLat));
        delivery.setDropLongitude(BigDecimal.valueOf(dropLng));
        return delivery;
    }

    @Test
    @DisplayName("Declines quote and saves unserviceable quote without querying providers when distance > 30 km")
    void gather_exceeds30KmRadius_declinesWithoutQueryingProviders() {
        // Indiranagar to Hosur (~45 km)
        var delivery = buildDelivery(12.9716, 77.5946, 12.7409, 77.8253);

        var outcome = service.gather(delivery, BigDecimal.valueOf(1000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isFalse();
        assertThat(outcome.selected()).isNull();
        assertThat(outcome.all()).hasSize(1);

        var quote = outcome.all().get(0);
        assertThat(quote.getStatus()).isEqualTo("UNSERVICEABLE");
        assertThat(quote.getFailureReason()).contains("Exceeds 30.0 km intra-city radius limit");
        assertThat(quote.getDistanceKm()).isNotNull();
        assertThat(quote.getDistanceKm().doubleValue()).isGreaterThan(30.0);

        verify(quotes).save(quote);
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("Queries providers and selects winning quote when distance <= 30 km")
    void gather_within30KmRadius_queriesProvidersAndSelectsWinner() {
        // Indiranagar to Koramangala (~5 km)
        var delivery = buildDelivery(12.9716, 77.5946, 12.9352, 77.6245);

        var record = new DeliveryProviderRecord();
        record.setId(1L);
        record.setCode("PORTER");
        record.setName("Porter");
        record.setEnabled(true);
        record.setPriority(10);

        when(registry.enabled()).thenReturn(List.of(new DeliveryProviderRegistry.Available(record, mockAdapter)));
        when(mockAdapter.quote(any())).thenReturn(new DeliveryProvider.Quote(
                "q-porter-1",
                true,
                new BigDecimal("65.00"),
                "INR",
                25,
                5.0,
                Instant.now().plusSeconds(600),
                null
        ));

        var outcome = service.gather(delivery, BigDecimal.valueOf(1000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isTrue();
        assertThat(outcome.selected()).isNotNull();
        assertThat(outcome.selected().getProviderCode()).isEqualTo("PORTER");
        assertThat(outcome.selected().getAmount()).isEqualByComparingTo("65.00");

        verify(quotes, atLeastOnce()).save(any(DeliveryQuote.class));
    }
}
