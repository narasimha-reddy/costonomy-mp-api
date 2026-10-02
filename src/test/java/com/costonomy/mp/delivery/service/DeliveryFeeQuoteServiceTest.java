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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private DeliveryFeeQuoteService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryFeeQuoteService(quotes, directory, registry, config);
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
}
