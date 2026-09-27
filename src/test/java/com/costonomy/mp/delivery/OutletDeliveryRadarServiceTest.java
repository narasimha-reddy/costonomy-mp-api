package com.costonomy.mp.delivery;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.service.OutletDeliveryRadarService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutletDeliveryRadarServiceTest {

    @Mock
    private AccessControlService accessControl;

    @Mock
    private JdbcTemplate jdbc;

    private OutletDeliveryRadarService radarService;

    @BeforeEach
    void setUp() {
        radarService = new OutletDeliveryRadarService(accessControl, jdbc);
    }

    @Test
    void getRadar_ranksByArrivalProximity_arrivedFirstThenApproaching() {
        Long actorId = 100L;
        Long outletId = 50L;

        Instant now = Instant.now();

        // 4 active deliveries:
        // 1. IN_TRANSIT with eta 25m (EN_ROUTE)
        // 2. IN_TRANSIT with eta 5m (APPROACHING)
        // 3. ARRIVED_AT_DESTINATION (AT_KITCHEN_DOOR)
        // 4. DRIVER_ASSIGNED (DRIVER_DISPATCHED)
        DeliveryDtos.OutletDeliveryRadarItemResponse itemEnRoute = buildRadarItem(
                101L, 201L, "ORD-201", outletId, DeliveryStatus.IN_TRANSIT,
                DeliveryDtos.ArrivalStage.EN_ROUTE, 2, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 25, now.plus(25, ChronoUnit.MINUTES),
                null, false, DeliveryDtos.KitchenAction.MONITOR
        );

        DeliveryDtos.OutletDeliveryRadarItemResponse itemApproaching = buildRadarItem(
                102L, 202L, "ORD-202", outletId, DeliveryStatus.IN_TRANSIT,
                DeliveryDtos.ArrivalStage.APPROACHING, 1, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 5, now.plus(5, ChronoUnit.MINUTES),
                null, false, DeliveryDtos.KitchenAction.PREPARE_DOCK
        );

        DeliveryDtos.OutletDeliveryRadarItemResponse itemAtDoor = buildRadarItem(
                103L, 203L, "ORD-203", outletId, DeliveryStatus.ARRIVED_AT_DESTINATION,
                DeliveryDtos.ArrivalStage.AT_KITCHEN_DOOR, 0, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 0, now,
                null, false, DeliveryDtos.KitchenAction.MEET_DRIVER
        );

        DeliveryDtos.OutletDeliveryRadarItemResponse itemDispatched = buildRadarItem(
                104L, 204L, "ORD-204", outletId, DeliveryStatus.DRIVER_ASSIGNED,
                DeliveryDtos.ArrivalStage.DRIVER_DISPATCHED, 5, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 45, now.plus(45, ChronoUnit.MINUTES),
                null, false, DeliveryDtos.KitchenAction.MONITOR
        );

        when(jdbc.query(anyString(), any(RowMapper.class), eq(outletId)))
                .thenReturn(List.of(itemEnRoute, itemApproaching, itemAtDoor, itemDispatched));

        DeliveryDtos.OutletDeliveryRadarResponse response = radarService.getRadar(
                actorId, outletId, null, null, null);

        verify(accessControl).requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        assertThat(response.outletId()).isEqualTo(outletId);
        assertThat(response.summary().totalActive()).isEqualTo(4);
        assertThat(response.summary().atDoorCount()).isEqualTo(1);
        assertThat(response.summary().approachingCount()).isEqualTo(1);
        assertThat(response.summary().enRouteCount()).isEqualTo(1);

        // Assert priority ranking: AT_KITCHEN_DOOR (rank 0) must be first, APPROACHING (rank 1) second!
        assertThat(response.items()).hasSize(4);
        assertThat(response.items().get(0).deliveryId()).isEqualTo(103L);
        assertThat(response.items().get(0).arrivalStage()).isEqualTo(DeliveryDtos.ArrivalStage.AT_KITCHEN_DOOR);
        assertThat(response.items().get(0).recommendedAction()).isEqualTo(DeliveryDtos.KitchenAction.MEET_DRIVER);

        assertThat(response.items().get(1).deliveryId()).isEqualTo(102L);
        assertThat(response.items().get(1).arrivalStage()).isEqualTo(DeliveryDtos.ArrivalStage.APPROACHING);
        assertThat(response.items().get(1).recommendedAction()).isEqualTo(DeliveryDtos.KitchenAction.PREPARE_DOCK);

        assertThat(response.items().get(2).deliveryId()).isEqualTo(101L);
        assertThat(response.items().get(2).arrivalStage()).isEqualTo(DeliveryDtos.ArrivalStage.EN_ROUTE);

        assertThat(response.items().get(3).deliveryId()).isEqualTo(104L);
        assertThat(response.items().get(3).arrivalStage()).isEqualTo(DeliveryDtos.ArrivalStage.DRIVER_DISPATCHED);
    }

    @Test
    void getRadar_detectsOverdueSupplierScheduleAndEscalationAction() {
        Long actorId = 100L;
        Long outletId = 50L;
        Instant now = Instant.now();

        DeliveryDtos.OutletDeliveryRadarItemResponse criticalLate = buildRadarItem(
                105L, 205L, "ORD-205", outletId, DeliveryStatus.IN_TRANSIT,
                DeliveryDtos.ArrivalStage.APPROACHING, 1, DeliveryDtos.ScheduleStatus.CRITICALLY_DELAYED,
                25, 10, now.minus(25, ChronoUnit.MINUTES),
                null, false, DeliveryDtos.KitchenAction.ESCALATE
        );

        when(jdbc.query(anyString(), any(RowMapper.class), eq(outletId)))
                .thenReturn(List.of(criticalLate));

        DeliveryDtos.OutletDeliveryRadarResponse response = radarService.getRadar(
                actorId, outletId, null, null, null);

        assertThat(response.summary().delayedCount()).isEqualTo(1);
        assertThat(response.summary().requiresEscalationCount()).isEqualTo(1);
        assertThat(response.items().get(0).scheduleStatus()).isEqualTo(DeliveryDtos.ScheduleStatus.CRITICALLY_DELAYED);
        assertThat(response.items().get(0).minutesOverdue()).isEqualTo(25);
        assertThat(response.items().get(0).recommendedAction()).isEqualTo(DeliveryDtos.KitchenAction.ESCALATE);
    }

    @Test
    void getRadar_identifiesDeliveredOrdersPendingCheckIn() {
        Long actorId = 100L;
        Long outletId = 50L;
        Instant now = Instant.now();

        DeliveryDtos.OutletDeliveryRadarItemResponse pendingCheckIn = buildRadarItem(
                106L, 206L, "ORD-206", outletId, DeliveryStatus.DELIVERED,
                DeliveryDtos.ArrivalStage.DELIVERED_UNCHECKED, 3, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 0, now,
                null, false, DeliveryDtos.KitchenAction.CHECK_IN
        );

        when(jdbc.query(anyString(), any(RowMapper.class), eq(outletId)))
                .thenReturn(List.of(pendingCheckIn));

        DeliveryDtos.OutletDeliveryRadarResponse response = radarService.getRadar(
                actorId, outletId, null, null, null);

        assertThat(response.summary().pendingCheckInCount()).isEqualTo(1);
        assertThat(response.items().get(0).recommendedAction()).isEqualTo(DeliveryDtos.KitchenAction.CHECK_IN);
        assertThat(response.items().get(0).isCheckedIn()).isFalse();
    }

    @Test
    void getRadar_filtersByActionAndStage() {
        Long actorId = 100L;
        Long outletId = 50L;
        Instant now = Instant.now();

        DeliveryDtos.OutletDeliveryRadarItemResponse itemAtDoor = buildRadarItem(
                103L, 203L, "ORD-203", outletId, DeliveryStatus.ARRIVED_AT_DESTINATION,
                DeliveryDtos.ArrivalStage.AT_KITCHEN_DOOR, 0, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 0, now,
                null, false, DeliveryDtos.KitchenAction.MEET_DRIVER
        );

        DeliveryDtos.OutletDeliveryRadarItemResponse itemApproaching = buildRadarItem(
                102L, 202L, "ORD-202", outletId, DeliveryStatus.IN_TRANSIT,
                DeliveryDtos.ArrivalStage.APPROACHING, 1, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 5, now.plus(5, ChronoUnit.MINUTES),
                null, false, DeliveryDtos.KitchenAction.PREPARE_DOCK
        );

        when(jdbc.query(anyString(), any(RowMapper.class), eq(outletId)))
                .thenReturn(List.of(itemAtDoor, itemApproaching));

        // Filter by MEET_DRIVER action
        DeliveryDtos.OutletDeliveryRadarResponse response = radarService.getRadar(
                actorId, outletId, DeliveryDtos.KitchenAction.MEET_DRIVER, null, null);

        assertThat(response.summary().totalActive()).isEqualTo(2); // summary maintains total count
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).deliveryId()).isEqualTo(103L);
        assertThat(response.items().get(0).recommendedAction()).isEqualTo(DeliveryDtos.KitchenAction.MEET_DRIVER);
    }

    @Test
    void getRadar_enforcesTenantIsolation() {
        Long actorId = 100L;
        Long outletId = 999L;

        doThrow(new NotFoundException("Outlet", outletId))
                .when(accessControl).requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        assertThatThrownBy(() -> radarService.getRadar(actorId, outletId, null, null, null))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void listDeliveries_paginatesCorrectly() {
        Long actorId = 100L;
        Long outletId = 50L;
        Instant now = Instant.now();

        when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"), eq(Long.class), any()))
                .thenReturn(25L);

        DeliveryDtos.OutletDeliveryRadarItemResponse item = buildRadarItem(
                101L, 201L, "ORD-201", outletId, DeliveryStatus.DELIVERED,
                DeliveryDtos.ArrivalStage.DELIVERED_UNCHECKED, 3, DeliveryDtos.ScheduleStatus.ON_SCHEDULE,
                0, 0, now,
                null, true, DeliveryDtos.KitchenAction.MONITOR
        );

        when(jdbc.query(startsWith("SELECT"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(item));

        DeliveryDtos.PagedResponse<DeliveryDtos.OutletDeliveryRadarItemResponse> page =
                radarService.listDeliveries(actorId, outletId, 0, 10, null);

        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).hasSize(1);
    }

    private DeliveryDtos.OutletDeliveryRadarItemResponse buildRadarItem(
            Long deliveryId, Long supplierOrderId, String orderNumber, Long outletId,
            DeliveryStatus status, DeliveryDtos.ArrivalStage stage, int rank,
            DeliveryDtos.ScheduleStatus scheduleStatus, int minutesOverdue, Integer etaMinutes,
            Instant estimatedArrivalAt, String failureCode, boolean isCheckedIn,
            DeliveryDtos.KitchenAction action) {

        DeliveryDtos.SupplierInfo supplier = new DeliveryDtos.SupplierInfo(
                10L, "Fresh Veggies Direct", "Farm Direct Agro", "+919876543210");
        DeliveryDtos.DriverInfo driver = new DeliveryDtos.DriverInfo(
                "Suresh Patel", "+919123456780", "Tata Ace MH-12-AB-9988");
        DeliveryDtos.ProblemDetails problem = new DeliveryDtos.ProblemDetails(
                failureCode != null || minutesOverdue > 0,
                failureCode != null ? DeliveryDtos.ProblemType.CARRIER_EXCEPTION
                        : (minutesOverdue > 0 ? DeliveryDtos.ProblemType.MISSED_ETA : DeliveryDtos.ProblemType.NONE),
                failureCode != null ? "Failed" : (minutesOverdue > 0 ? "Late" : null),
                failureCode,
                null
        );

        return new DeliveryDtos.OutletDeliveryRadarItemResponse(
                deliveryId, supplierOrderId, orderNumber, outletId, status, stage, rank,
                scheduleStatus, minutesOverdue, etaMinutes, estimatedArrivalAt,
                supplier, driver, problem, action, "Action description",
                isCheckedIn, null, false, null,
                Instant.now(), Instant.now(), Instant.now(), null
        );
    }
}
