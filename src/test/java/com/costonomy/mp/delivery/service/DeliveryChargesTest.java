package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.procurement.domain.DeliveryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryChargesTest {

    private final DeliveryDirectory directory = mock(DeliveryDirectory.class);
    private final DeliveryCharges charges = new DeliveryCharges(directory);

    private void policy(boolean ownDelivery, String fee, String minOrder, String freeAbove) {
        when(directory.deliveryPolicy(7L)).thenReturn(new DeliveryDirectory.DeliveryPolicy(
                ownDelivery, true, new BigDecimal(fee), minOrder == null ? null : new BigDecimal(minOrder),
                BigDecimal.ZERO, freeAbove == null ? null : new BigDecimal(freeAbove), null));
    }

    @Test
    @DisplayName("a store that does not deliver is refused even when the order is over its free-delivery threshold")
    void thresholdDoesNotWaiveWhetherTheStoreDelivers() {
        policy(false, "40.00", null, "500.00");

        assertThatThrownBy(() -> charges.feeFor(7L, DeliveryMode.SUPPLIER_DELIVERY, new BigDecimal("900.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("doesn't deliver");
    }

    @Test
    @DisplayName("over the threshold the fee is waived; below it the store's own fee applies; pickup is free")
    void feeAndWaiver() {
        policy(true, "40.00", "100.00", "500.00");

        assertThat(charges.feeFor(7L, DeliveryMode.SUPPLIER_DELIVERY, new BigDecimal("600.00"))).isEqualByComparingTo("0");
        assertThat(charges.feeFor(7L, DeliveryMode.SUPPLIER_DELIVERY, new BigDecimal("200.00"))).isEqualByComparingTo("40.00");
        assertThat(charges.feeFor(7L, DeliveryMode.PICKUP, new BigDecimal("200.00"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an order below the store's delivery minimum is refused")
    void belowMinimumRefused() {
        policy(true, "40.00", "100.00", null);

        assertThatThrownBy(() -> charges.feeFor(7L, DeliveryMode.SUPPLIER_DELIVERY, new BigDecimal("50.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least");
    }
}
