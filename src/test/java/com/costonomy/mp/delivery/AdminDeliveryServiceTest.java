package com.costonomy.mp.delivery;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryLedgerEntry;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryLedgerRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderStatsRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.AdminDeliveryService;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.delivery.service.DeliveryWaterfallService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDeliveryServiceTest {

    @Mock
    private AccessControlService accessControl;

    @Mock
    private DeliveryRepository deliveries;

    @Mock
    private DeliveryLedgerRepository ledgerRepository;

    @Mock
    private DeliveryProviderStatsRepository statsRepository;

    @Mock
    private com.costonomy.mp.delivery.repository.DeliveryProviderMetricsRepository metricsRepository;

    @Mock
    private DeliveryWaterfallService waterfallService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private AdminDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new AdminDeliveryService(accessControl, deliveries, ledgerRepository, statsRepository, metricsRepository, waterfallService, deliveryService, jdbc);
    }


    @Test
    void getLedgerChecksPermissionAndReturnsEntries() {
        Long actorId = 1L;
        Long deliveryId = 100L;

        when(deliveries.existsById(deliveryId)).thenReturn(true);

        var entry = new DeliveryLedgerEntry();
        entry.setId(10L);
        entry.setDeliveryId(deliveryId);
        entry.setProviderCode("PIDGE");
        entry.setProviderDeliveryId("pidg_123");
        entry.setEntryType("BOOKED");
        entry.setAmount(BigDecimal.valueOf(75.50));
        entry.setCurrency("INR");
        entry.setDescription("Booked carrier");
        entry.setCreatedAt(Instant.now());

        when(ledgerRepository.findByDeliveryIdOrderByCreatedAtAsc(deliveryId)).thenReturn(List.of(entry));

        var response = service.getLedger(actorId, deliveryId);

        verify(accessControl).require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        assertThat(response).hasSize(1);
        assertThat(response.get(0).providerCode()).isEqualTo("PIDGE");
        assertThat(response.get(0).amount()).isEqualByComparingTo("75.50");
    }

    @Test
    void forceWaterfallTriggersEscalation() {
        Long actorId = 2L;
        Long deliveryId = 200L;

        var delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
        delivery.setMode(DeliveryMode.COSTONOMY);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));

        service.forceWaterfall(actorId, deliveryId, "Driver stalled");

        verify(accessControl).require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        verify(waterfallService).forceEscalate(deliveryId, "Driver stalled");
    }

    @Test
    void listDeliveriesExecutesQueryWithPagination() {
        Long actorId = 1L;
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(25L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var result = service.listDeliveries(actorId, 0, 10, null, null, null);

        verify(accessControl).require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void listLateDeliveriesFiltersByBreachedEta() {
        Long actorId = 1L;
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var result = service.listLateDeliveries(actorId, true, 0, 10);

        verify(accessControl).require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.hasNext()).isFalse();
    }
}
