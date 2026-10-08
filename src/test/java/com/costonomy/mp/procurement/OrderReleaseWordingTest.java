package com.costonomy.mp.procurement;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.service.OrderFunding;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import com.costonomy.mp.procurement.service.ProcurementDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wording of the supplier's "Order confirmed" is notification-only: a lookup that feeds it may fail, and the
 * release (the money) must go ahead regardless, announced neutrally (flow review 6).
 */
class OrderReleaseWordingTest {

    private SupplierOrderRepository orders;
    private OrderFunding funding;
    private OutboxService outbox;
    private ProcurementDirectory directory;
    private OrderReleaseService service;
    private SupplierOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(SupplierOrderRepository.class);
        funding = mock(OrderFunding.class);
        outbox = mock(OutboxService.class);
        directory = mock(ProcurementDirectory.class);
        service = new OrderReleaseService(orders, funding, mock(AuditService.class), outbox, directory);

        order = new SupplierOrder();
        order.setId(7L);
        order.setOrderNumber("MP-261007-000007");
        order.setSupplierStoreId(3L);
        order.setOutletId(5L);
        order.setStatus(SupplierOrderStatus.DRAFT);
        order.setTotalAmount(BigDecimal.TEN);
        when(orders.lockById(7L)).thenReturn(Optional.of(order));
        when(funding.isFundingSecured(7L)).thenReturn(true);
        when(funding.paymentState(order)).thenReturn("AUTHORIZED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> published() {
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq("SupplierOrderConfirmed"), eq("SUPPLIER_ORDER"), eq(7L), payload.capture(),
                any(), any());
        return (Map<String, Object>) payload.getValue();
    }

    private void lookupFindsRestaurant() {
        when(directory.outletSummary(5L)).thenReturn(new ProcurementDirectory.OutletSummary(
                "Indiranagar", "Spice Route", null, null, null, null));
    }

    @Test
    @DisplayName("each funded method sends its own variant and the restaurant's name")
    void variantPerMethod() {
        lookupFindsRestaurant();
        for (var method : new String[]{"WALLET", "PREPAID"}) {
            org.mockito.Mockito.clearInvocations(outbox);
            order.setStatus(SupplierOrderStatus.DRAFT);
            order.setPaymentMethod(method);

            assertThat(service.releaseIfFunded(7L)).isTrue();

            assertThat(published()).containsEntry("notificationVariant", method)
                    .containsEntry("restaurantName", "Spice Route");
        }
    }

    @Test
    @DisplayName("a credit order sends CREDIT_DUE with the date as '7 Nov 2026'")
    void creditWithDate() {
        lookupFindsRestaurant();
        order.setPaymentMethod("CREDIT");
        when(directory.creditDueDateOf(7L)).thenReturn(LocalDate.of(2026, 11, 7));

        service.releaseIfFunded(7L);

        assertThat(published()).containsEntry("notificationVariant", "CREDIT_DUE")
                .containsEntry("dueDate", "7 Nov 2026");
    }

    @Test
    @DisplayName("no restaurant found: the name falls back to 'A restaurant', never left out")
    void nameFallback() {
        order.setPaymentMethod("WALLET");
        when(directory.outletSummary(anyLong())).thenReturn(null);

        service.releaseIfFunded(7L);

        assertThat(published()).containsEntry("restaurantName", "A restaurant");
    }

    @Test
    @DisplayName("a wording lookup that throws cannot stop the release: the order is released and announced neutrally")
    void lookupFailureNeverBlocksRelease() {
        order.setPaymentMethod("CREDIT");
        when(directory.outletSummary(anyLong())).thenThrow(new IllegalStateException("db hiccup"));
        when(directory.creditDueDateOf(anyLong())).thenThrow(new IllegalStateException("db hiccup"));

        assertThat(service.releaseIfFunded(7L)).isTrue();

        assertThat(order.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
        verify(orders).save(order);
        verify(funding).onOrderAccepted(eq(7L), any());
        var payload = published();
        assertThat(payload).doesNotContainKey("notificationVariant").doesNotContainKey("dueDate")
                .containsEntry("restaurantName", "A restaurant");
    }
}
