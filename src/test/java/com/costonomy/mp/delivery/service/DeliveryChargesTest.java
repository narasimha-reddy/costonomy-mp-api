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

    // ── Supplier-funded Costonomy delivery (D-203) ───────────────────────

    private static DeliveryDirectory.DeliveryPolicy funding(boolean on, String threshold, String cap) {
        return new DeliveryDirectory.DeliveryPolicy(false, true, BigDecimal.ZERO, null, BigDecimal.ZERO,
                threshold == null ? null : new BigDecimal(threshold), null, on, cap == null ? null : new BigDecimal(cap));
    }

    @Test
    @DisplayName("a supplier who funds delivery pays the whole courier fee when it is under their cap and the goods reach the threshold")
    void supplierPaysTheWholeFeeUnderTheCap() {
        var split = DeliveryCharges.fundCostonomyFee(funding(true, "2000", "100"),
                new BigDecimal("2460"), new BigDecimal("76.00"));

        assertThat(split.supplierFunded()).isEqualByComparingTo("76.00");
        assertThat(split.buyerFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("above the cap the supplier pays the cap and the restaurant pays the rest")
    void restaurantPaysWhatIsOverTheCap() {
        var split = DeliveryCharges.fundCostonomyFee(funding(true, "2000", "50"),
                new BigDecimal("2460"), new BigDecimal("76.00"));

        assertThat(split.supplierFunded()).isEqualByComparingTo("50");
        assertThat(split.buyerFee()).isEqualByComparingTo("26.00");
    }

    @Test
    @DisplayName("the two parts always add up to the courier's fee")
    void thePartsAddUp() {
        var fee = new BigDecimal("83.37");
        var split = DeliveryCharges.fundCostonomyFee(funding(true, "100", "40.10"), new BigDecimal("500"), fee);

        assertThat(split.buyerFee().add(split.supplierFunded())).isEqualByComparingTo(fee);
    }

    @Test
    @DisplayName("goods one paisa under the threshold leave the whole fee with the restaurant; exactly at it qualifies")
    void thresholdBoundary() {
        var policy = funding(true, "2000", "100");

        var under = DeliveryCharges.fundCostonomyFee(policy, new BigDecimal("1999.99"), new BigDecimal("76"));
        var at = DeliveryCharges.fundCostonomyFee(policy, new BigDecimal("2000.00"), new BigDecimal("76"));

        assertThat(under.supplierFunded()).isEqualByComparingTo("0");
        assertThat(under.buyerFee()).isEqualByComparingTo("76");
        assertThat(at.supplierFunded()).isEqualByComparingTo("76");
    }

    @Test
    @DisplayName("a supplier who has not opted in is never charged, however large the order")
    void noOptInNoCharge() {
        var split = DeliveryCharges.fundCostonomyFee(funding(false, "2000", "100"),
                new BigDecimal("99999"), new BigDecimal("76"));

        assertThat(split.supplierFunded()).isEqualByComparingTo("0");
        assertThat(split.buyerFee()).isEqualByComparingTo("76");
    }

    @Test
    @DisplayName("a policy missing its cap or threshold funds nothing, and a zero fee stays zero")
    void incompletePolicyFundsNothing() {
        assertThat(DeliveryCharges.fundCostonomyFee(funding(true, "2000", null),
                new BigDecimal("3000"), new BigDecimal("76")).supplierFunded()).isEqualByComparingTo("0");
        assertThat(DeliveryCharges.fundCostonomyFee(funding(true, null, "100"),
                new BigDecimal("3000"), new BigDecimal("76")).supplierFunded()).isEqualByComparingTo("0");
        assertThat(DeliveryCharges.fundCostonomyFee(funding(true, "2000", "100"),
                new BigDecimal("3000"), BigDecimal.ZERO).supplierFunded()).isEqualByComparingTo("0");
    }
}
