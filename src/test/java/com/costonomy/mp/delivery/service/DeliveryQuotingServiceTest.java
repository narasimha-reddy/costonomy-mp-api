package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.domain.DeliveryQuote;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryQuotingServiceTest {

    @Mock
    private DeliveryProviderRegistry registry;

    @Mock
    private DeliveryQuoteRepository quotes;

    @Mock
    private DeliveryProvider mockAdapter;

    @Mock
    private ColdChainCarrierGate coldGate;

    private DeliveryQuotingService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryQuotingService(registry, quotes, coldGate);
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

    @Test
    @DisplayName("When every carrier declines or fails, each is recorded and none is selected (D-121)")
    void gather_whenEveryCarrierDeclinesOrFails_recordsEachAndSelectsNone() {
        // Indiranagar to Koramangala (~5 km), inside the radius, so carriers really are asked
        var delivery = buildDelivery(12.9716, 77.5946, 12.9352, 77.6245);

        var declining = mock(DeliveryProvider.class);
        var failing = mock(DeliveryProvider.class);
        when(declining.quote(any())).thenReturn(DeliveryProvider.Quote.unserviceable(
                "Porter fare contract not verified against a live response; a rate card is not a quote (D-121)"));
        when(failing.quote(any())).thenThrow(
                new DeliveryProviderException("SHADOWFAX", "Shadowfax serviceability timeout", true));

        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(provider(1L, "PORTER", 25), declining),
                new DeliveryProviderRegistry.Available(provider(2L, "SHADOWFAX", 20), failing)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(1000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isFalse();
        assertThat(outcome.selected()).isNull();
        assertThat(outcome.all()).hasSize(2);

        var porter = outcome.all().stream().filter(q -> "PORTER".equals(q.getProviderCode())).findFirst().orElseThrow();
        var shadowfax = outcome.all().stream().filter(q -> "SHADOWFAX".equals(q.getProviderCode())).findFirst().orElseThrow();
        assertThat(porter.getStatus()).isEqualTo("UNSERVICEABLE");
        assertThat(porter.getFailureReason()).contains("fare");
        assertThat(shadowfax.getStatus()).isEqualTo("FAILED");
        assertThat(shadowfax.getFailureReason()).contains("timeout");
        assertThat(outcome.all()).allSatisfy(q -> {
            assertThat(q.getAmount()).isNull();
            assertThat(q.getSelected()).isFalse();
        });

        // Both were persisted, so a fallback to the rate card is explicable afterwards (doc 06 §12).
        ArgumentCaptor<DeliveryQuote> saved = ArgumentCaptor.forClass(DeliveryQuote.class);
        verify(quotes, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DeliveryQuote::getProviderCode)
                .containsExactlyInAnyOrder("PORTER", "SHADOWFAX");
    }

    private static DeliveryProviderRecord provider(Long id, String code, int priority) {
        var record = new DeliveryProviderRecord();
        record.setId(id);
        record.setCode(code);
        record.setName(code);
        record.setEnabled(true);
        record.setPriority(priority);
        return record;
    }

    // ── cold chain (D-134) ───────────────────────────────────────────────

    private DeliveryProvider.Quote answer(String id, String amount, VehicleType vehicle) {
        return new DeliveryProvider.Quote(id, true, new BigDecimal(amount), "INR", 30, 5.0,
                Instant.now().plusSeconds(600), null, vehicle);
    }

    private Delivery chilled() {
        var delivery = buildDelivery(12.9716, 77.5946, 12.9352, 77.6245);
        delivery.setRequiresColdChain(true);
        return delivery;
    }

    @Test
    @DisplayName("a chilled consignment is asked only of a carrier verified for it, in the vehicle it is verified for")
    void chilled_onlyVerifiedCarrierIsAsked() {
        var delivery = chilled();
        var unverified = provider(1L, "BIKE_CO", 30);
        var verified = provider(2L, "COLD_CO", 20);
        var unverifiedAdapter = mock(DeliveryProvider.class);
        var verifiedAdapter = mock(DeliveryProvider.class);
        when(coldGate.vehicleFor(eq(1L), any())).thenReturn(Optional.empty());
        when(coldGate.vehicleFor(eq(2L), any())).thenReturn(Optional.of(VehicleType.THREE_WHEELER));
        when(coldGate.qualifies(2L, VehicleType.THREE_WHEELER)).thenReturn(true);
        when(verifiedAdapter.quote(any())).thenReturn(answer("q-cold", "120.00", VehicleType.THREE_WHEELER));
        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(unverified, unverifiedAdapter),
                new DeliveryProviderRegistry.Available(verified, verifiedAdapter)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(2000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.selected().getProviderCode()).isEqualTo("COLD_CO");
        assertThat(outcome.selected().getVehicleType()).isEqualTo(VehicleType.THREE_WHEELER);
        // The unverified carrier is on record as unable, and was never asked (and so could never undercut).
        var recordedUnverified = outcome.all().stream().filter(q -> "BIKE_CO".equals(q.getProviderCode())).findFirst()
                .orElseThrow();
        assertThat(recordedUnverified.getStatus()).isEqualTo("UNSERVICEABLE");
        assertThat(recordedUnverified.getFailureReason()).contains("No verified temperature-controlled");
        verify(unverifiedAdapter, never()).quote(any());
    }

    @Test
    @DisplayName("a quote with no vehicle type is not given the one we asked for: unstated means unverified")
    void chilled_nullVehicleTypeIsUnserviceable() {
        var delivery = chilled();
        var carrier = provider(2L, "COLD_CO", 20);
        var adapter = mock(DeliveryProvider.class);
        when(coldGate.vehicleFor(eq(2L), any())).thenReturn(Optional.of(VehicleType.THREE_WHEELER));
        when(adapter.quote(any())).thenReturn(answer("q-null", "80.00", null));
        when(registry.enabled()).thenReturn(List.of(new DeliveryProviderRegistry.Available(carrier, adapter)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(2000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isFalse();
        assertThat(outcome.selected()).isNull();
        var quote = outcome.all().get(0);
        assertThat(quote.getStatus()).isEqualTo("UNSERVICEABLE");
        assertThat(quote.getVehicleType()).isNull();
        assertThat(quote.getFailureReason()).contains("did not state the vehicle");
    }

    @Test
    @DisplayName("a quote in a vehicle the carrier is not verified for is unserviceable, even if it is the cheapest")
    void chilled_unverifiedVehicleInAnswerIsUnserviceable() {
        var delivery = chilled();
        var carrier = provider(2L, "COLD_CO", 20);
        var adapter = mock(DeliveryProvider.class);
        when(coldGate.vehicleFor(eq(2L), any())).thenReturn(Optional.of(VehicleType.THREE_WHEELER));
        when(coldGate.qualifies(2L, VehicleType.TWO_WHEELER)).thenReturn(false);
        when(adapter.quote(any())).thenReturn(answer("q-bike", "40.00", VehicleType.TWO_WHEELER));
        when(registry.enabled()).thenReturn(List.of(new DeliveryProviderRegistry.Available(carrier, adapter)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(2000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isFalse();
        assertThat(outcome.all().get(0).getFailureReason()).contains("not verified");
    }

    @Test
    @DisplayName("with no verified carrier at all every provider is recorded as unable and nothing is selected")
    void chilled_noQualifyingCarrier() {
        var delivery = chilled();
        var a = provider(1L, "A", 10);
        var b = provider(2L, "B", 20);
        var adapterA = mock(DeliveryProvider.class);
        var adapterB = mock(DeliveryProvider.class);
        when(coldGate.vehicleFor(any(), any())).thenReturn(Optional.empty());
        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(a, adapterA),
                new DeliveryProviderRegistry.Available(b, adapterB)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(2000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.anyServiceable()).isFalse();
        assertThat(outcome.selected()).isNull();
        assertThat(outcome.all()).hasSize(2).allMatch(q -> "UNSERVICEABLE".equals(q.getStatus()));
        verify(adapterA, never()).quote(any());
        verify(adapterB, never()).quote(any());
    }

    @Test
    @DisplayName("an ordinary consignment is unaffected: no gate, and an unstated vehicle keeps the requested one")
    void ordinary_isUnaffected() {
        var delivery = buildDelivery(12.9716, 77.5946, 12.9352, 77.6245);
        var carrier = provider(2L, "ANY_CO", 20);
        var adapter = mock(DeliveryProvider.class);
        when(adapter.quote(any())).thenReturn(answer("q", "50.00", null));
        when(registry.enabled()).thenReturn(List.of(new DeliveryProviderRegistry.Available(carrier, adapter)));

        var outcome = service.gather(delivery, BigDecimal.valueOf(2000), BigDecimal.valueOf(5000), 45, List.of());

        assertThat(outcome.selected()).isNotNull();
        assertThat(outcome.selected().getVehicleType()).isEqualTo(VehicleType.TWO_WHEELER);
        verifyNoInteractions(coldGate);
    }

    @Test
    @DisplayName("a capability revoked after quoting stops a chilled quote being booked again")
    void chilled_usableQuotesRecheckCapability() {
        var quote = new DeliveryQuote();
        quote.setDeliveryProviderId(2L);
        quote.setVehicleType(VehicleType.THREE_WHEELER);
        quote.setStatus("QUOTED");
        quote.setProviderCode("COLD_CO");
        quote.setAmount(new BigDecimal("120.00"));
        when(quotes.findByDeliveryIdOrderByIdAsc(100L)).thenReturn(List.of(quote));
        when(coldGate.qualifies(2L, VehicleType.THREE_WHEELER)).thenReturn(false);

        assertThat(service.usableQuotes(100L, List.of(), true)).isEmpty();
        assertThat(service.usableQuotes(100L, List.of(), false)).hasSize(1);
    }
}
