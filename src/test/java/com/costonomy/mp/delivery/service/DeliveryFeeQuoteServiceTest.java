package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.delivery.repository.DeliveryFeeQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.domain.DeliveryFeeQuote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryFeeQuoteServiceTest {

    @Mock
    private DeliveryFeeQuoteRepository quotes;

    @Mock
    private DeliveryDirectory directory;

    @Mock
    private DeliveryProviderRegistry registry;

    @Mock
    private AppConfigService config;

    @Mock
    private ColdChainCarrierGate coldChainGate;

    private DeliveryFeeQuoteService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryFeeQuoteService(quotes, directory, registry, config, coldChainGate);
    }

    @Test
    @DisplayName("Throws VALIDATION_ERROR when delivery distance exceeds 30 km intra-city radius limit")
    void quote_whenExceeds30KmRadius_throwsValidationException() {
        Long intentId = 10L;
        Long outletId = 20L;
        Long supplierStoreId = 30L;

        // Indiranagar, Bengaluru (12.9716, 77.5946) to Hosur (12.7409, 77.8253) (~45 km)
        var pickup = new DeliveryDirectory.Place(
                "Supplier Indiranagar", "Indiranagar, Bengaluru",
                BigDecimal.valueOf(12.9716), BigDecimal.valueOf(77.5946),
                "Mgr", "+919876543210");
        var drop = new DeliveryDirectory.Place(
                "Outlet Hosur", "Hosur Industrial Complex",
                BigDecimal.valueOf(12.7409), BigDecimal.valueOf(77.8253),
                "Chef", "+919876543211");

        when(directory.pickupFor(supplierStoreId)).thenReturn(pickup);
        when(directory.dropFor(outletId)).thenReturn(drop);

        assertThatThrownBy(() -> service.quote(intentId, outletId, supplierStoreId, BigDecimal.valueOf(1500)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("30 km intra-city limit");
                });
    }

    @Test
    @DisplayName("Throws VALIDATION_ERROR when coordinates are missing")
    void quote_whenCoordinatesMissing_throwsValidationException() {
        Long intentId = 10L;
        Long outletId = 20L;
        Long supplierStoreId = 30L;

        when(directory.pickupFor(supplierStoreId)).thenReturn(null);
        when(directory.dropFor(outletId)).thenReturn(null);

        assertThatThrownBy(() -> service.quote(intentId, outletId, supplierStoreId, BigDecimal.valueOf(1500)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("We can't price delivery to this address yet");
                });
    }

    // ── chilled goods (D-134) ────────────────────────────────────────────

    private static DeliveryProviderRecord provider(Long id, String code) {
        var record = new DeliveryProviderRecord();
        record.setId(id);
        record.setCode(code);
        record.setName(code);
        record.setEnabled(true);
        record.setPriority(10);
        return record;
    }

    private void nearbyChilledIntent() {
        var pickup = new DeliveryDirectory.Place("Supplier", "Indiranagar", BigDecimal.valueOf(12.9716),
                BigDecimal.valueOf(77.5946), "Mgr", "+919876543210");
        var drop = new DeliveryDirectory.Place("Outlet", "Koramangala", BigDecimal.valueOf(12.9352),
                BigDecimal.valueOf(77.6245), "Chef", "+919876543211");
        when(directory.pickupFor(30L)).thenReturn(pickup);
        when(directory.dropFor(20L)).thenReturn(drop);
        when(directory.intentLines(10L)).thenReturn(List.of());
        when(directory.intentRequiresColdChain(10L)).thenReturn(true);
        lenient().when(config.getDecimal(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(config.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    private static DeliveryProvider.Quote answer(String amount, VehicleType vehicle) {
        return new DeliveryProvider.Quote("q", true, new BigDecimal(amount), "INR", 30, 5.0,
                Instant.now().plusSeconds(600), null, vehicle);
    }

    @Test
    @DisplayName("chilled goods with no verified carrier are refused (422), with no rate card, no quote saved")
    void chilled_noVerifiedCarrier_isRefusedWithNoRateCard() {
        nearbyChilledIntent();
        var adapter = mock(DeliveryProvider.class);
        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(provider(1L, "ANY"), adapter)));
        when(coldChainGate.vehicleFor(anyLong(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote(10L, 20L, 30L, BigDecimal.valueOf(1500)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.DELIVERY_UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("chilled goods").contains("Choose pickup");
                });
        verify(quotes, never()).save(any());
        verify(adapter, never()).quote(any());
    }

    @Test
    @DisplayName("a verified carrier whose quote states no vehicle does not qualify: refused, not rate-carded")
    void chilled_unstatedVehicle_isRefused() {
        nearbyChilledIntent();
        var adapter = mock(DeliveryProvider.class);
        when(adapter.quote(any())).thenReturn(answer("90.00", null));
        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(provider(1L, "COLD_CO"), adapter)));
        when(coldChainGate.vehicleFor(anyLong(), any())).thenReturn(Optional.of(VehicleType.THREE_WHEELER));

        assertThatThrownBy(() -> service.quote(10L, 20L, 30L, BigDecimal.valueOf(1500)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DELIVERY_UNAVAILABLE));
        verify(quotes, never()).save(any());
    }

    @Test
    @DisplayName("a verified carrier's quote for a verified vehicle prices it, marked as a chilled quote")
    void chilled_verifiedCarrierPricesIt() {
        nearbyChilledIntent();
        var adapter = mock(DeliveryProvider.class);
        when(adapter.quote(any())).thenReturn(answer("90.00", VehicleType.THREE_WHEELER));
        when(registry.enabled()).thenReturn(List.of(
                new DeliveryProviderRegistry.Available(provider(1L, "COLD_CO"), adapter)));
        when(coldChainGate.vehicleFor(anyLong(), any())).thenReturn(Optional.of(VehicleType.THREE_WHEELER));
        when(coldChainGate.qualifies(1L, VehicleType.THREE_WHEELER)).thenReturn(true);
        when(quotes.save(any(DeliveryFeeQuote.class))).thenAnswer(inv -> inv.getArgument(0));

        var fee = service.quote(10L, 20L, 30L, BigDecimal.valueOf(1500));

        assertThat(fee.amount()).isEqualByComparingTo("90.00");
        var saved = org.mockito.ArgumentCaptor.forClass(DeliveryFeeQuote.class);
        verify(quotes).save(saved.capture());
        assertThat(saved.getValue().isColdChain()).isTrue();
    }

    @Test
    @DisplayName("a quote priced for ordinary goods cannot be spent once the order has become chilled")
    void staleOrdinaryQuoteIsRefusedForAChilledOrder() {
        var stale = new DeliveryFeeQuote();
        stale.setOutletId(20L);
        stale.setSupplierStoreId(30L);
        stale.setIntentId(10L);
        stale.setColdChain(false);
        stale.setFee(BigDecimal.TEN);
        stale.setExpiresAt(Instant.now().plusSeconds(600));
        when(quotes.findByReference("DQ-1")).thenReturn(Optional.of(stale));
        when(directory.intentRequiresColdChain(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.priceFor("DQ-1", 10L, 20L, 30L, Instant.now()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PRICE_CHANGED);
                    assertThat(ex.getMessage()).contains("temperature-controlled");
                });
    }
}
