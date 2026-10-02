package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.delivery.domain.DeliveryFeeQuote;
import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.domain.DeliveryQuoteSource;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.DeliveryFeeQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-102: when every carrier declines (Shadowfax, Porter) or fails, the checkout fee is the
 * platform's own rate card, labelled ESTIMATED. It is never a carrier price, and no carrier
 * is named on the quote.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryFeeQuoteCarrierDeclineTest {

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
        // Every key falls back to the default the code passes in, i.e. the shipped rate card.
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(i -> i.getArgument(1));
        when(quotes.save(any(DeliveryFeeQuote.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static DeliveryProviderRegistry.Available available(Long id, String code, DeliveryProvider adapter) {
        var record = new DeliveryProviderRecord();
        record.setId(id);
        record.setCode(code);
        record.setName(code);
        record.setEnabled(true);
        record.setPriority(20);
        return new DeliveryProviderRegistry.Available(record, adapter);
    }

    @Test
    @DisplayName("Prices from the rate card, marked ESTIMATED, when every carrier declines or fails")
    void quote_whenEveryCarrierDeclinesOrFails_usesRateCardAndNamesNoCarrier() {
        // Secunderabad to Gachibowli, Hyderabad: about 7 km, inside the 30 km limit
        when(directory.pickupFor(30L)).thenReturn(new DeliveryDirectory.Place(
                "Annapurna Stores", "SD Road, Secunderabad, Telangana, 500003",
                new BigDecimal("17.4156"), new BigDecimal("78.4347"), "Store Desk", "+919876500000"));
        when(directory.dropFor(20L)).thenReturn(new DeliveryDirectory.Place(
                "Gachibowli Outlet", "Road 2, Hyderabad, Telangana, 500081",
                new BigDecimal("17.4474"), new BigDecimal("78.3762"), "Chef", "+919876511111"));
        when(directory.intentLines(10L)).thenReturn(List.of());

        var declining = mock(DeliveryProvider.class);
        var failing = mock(DeliveryProvider.class);
        when(declining.quote(any())).thenReturn(DeliveryProvider.Quote.unserviceable(
                "Porter fare contract not verified against a live response; a rate card is not a quote (D-102)"));
        when(failing.quote(any())).thenThrow(
                new DeliveryProviderException("SHADOWFAX", "Shadowfax serviceability timeout", true));
        when(registry.enabled()).thenReturn(List.of(
                available(1L, "PORTER", declining), available(2L, "SHADOWFAX", failing)));

        var fee = service.quote(10L, 20L, 30L, new BigDecimal("1500"));

        ArgumentCaptor<DeliveryFeeQuote> saved = ArgumentCaptor.forClass(DeliveryFeeQuote.class);
        verify(quotes).save(saved.capture());
        var quote = saved.getValue();

        // Labelled as our estimate, with nothing from a carrier on it.
        assertThat(quote.getSource()).isEqualTo(DeliveryQuoteSource.ESTIMATED);
        assertThat(quote.getProviderReference()).isNull();

        // The fee is the configured rate card: base + per-km x distance + per-kg x weight,
        // never below the minimum. Distance and weight come from the saved quote itself.
        BigDecimal distanceKm = quote.getDistanceKm();
        assertThat(distanceKm.doubleValue()).isBetween(5.0, 9.0);
        BigDecimal weightKg = quote.getWeightGrams().divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        BigDecimal expected = BigDecimal.valueOf(40)
                .add(BigDecimal.valueOf(8).multiply(distanceKm))
                .add(BigDecimal.valueOf(2).multiply(weightKg))
                .setScale(2, RoundingMode.HALF_UP)
                .max(new BigDecimal("40.00"));
        assertThat(quote.getFee()).isEqualByComparingTo(expected);
        assertThat(quote.getFee()).isGreaterThanOrEqualTo(new BigDecimal("40.00"));
        assertThat(quote.getEtaMinutes()).isNotNull();

        // What the restaurant is shown is the same figure, and a quote reference to spend it with.
        assertThat(fee.amount()).isEqualByComparingTo(quote.getFee());
        assertThat(fee.quoteReference()).startsWith("DQ-");
        assertThat(fee.currency()).isEqualTo("INR");
    }
}
