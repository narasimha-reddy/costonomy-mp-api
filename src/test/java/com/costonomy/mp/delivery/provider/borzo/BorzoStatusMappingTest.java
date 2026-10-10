package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BorzoStatusMappingTest {

    @Test
    @DisplayName("borzo top-level order.status maps to the least-advanced consistent ProviderDeliveryStatus")
    void mapsOrderStatus() {
        assertThat(BorzoStatusMapper.map("new", null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BorzoStatusMapper.map("available", null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BorzoStatusMapper.map("active", "planned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BorzoStatusMapper.map("delayed", "planned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BorzoStatusMapper.map("completed", null)).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(BorzoStatusMapper.map("canceled", null)).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(BorzoStatusMapper.map("failed", null)).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("is case/whitespace tolerant, same as the Pidge mapper")
    void isLenientOnFormatting() {
        assertThat(BorzoStatusMapper.map(" COMPLETED ", null)).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(BorzoStatusMapper.map("Active", null)).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
    }

    @Test
    @DisplayName("an unrecognised order.status never advances or regresses the delivery")
    void unknownStatusStaysPending() {
        assertThat(BorzoStatusMapper.map("some_future_status", null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BorzoStatusMapper.map(null, null)).isEqualTo(ProviderDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("the drop point's nested delivery.status=canceled overrides active/delayed, not completed")
    void dropPointCancellationOverridesInProgressStatus() {
        assertThat(BorzoStatusMapper.map("active", "canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(BorzoStatusMapper.map("delayed", "canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
    }
}
