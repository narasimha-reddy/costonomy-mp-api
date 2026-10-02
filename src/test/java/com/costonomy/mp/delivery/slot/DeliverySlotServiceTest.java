package com.costonomy.mp.delivery.slot;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliverySlotServiceTest {

    @Mock
    private DeliverySlotRepository slotRepository;

    @Mock
    private AccessControlService accessControl;

    @Mock
    private JdbcTemplate jdbc;

    private DeliverySlotService service;

    @BeforeEach
    void setUp() {
        service = new DeliverySlotService(slotRepository, accessControl, jdbc);
    }

    @Test
    @DisplayName("Create slot succeeds when times are valid")
    void createSlotSuccess() {
        var req = new DeliverySlotDtos.CreateDeliverySlotRequest(
                "Morning Express",
                LocalTime.of(6, 0),
                LocalTime.of(9, 0),
                LocalTime.of(4, 0),
                25
        );

        when(slotRepository.save(any(DeliverySlot.class))).thenAnswer(inv -> {
            DeliverySlot slot = inv.getArgument(0);
            slot.setId(101L);
            return slot;
        });

        var res = service.createSlot(1L, 10L, req);

        verify(accessControl).requireScoped(1L, Permissions.STORE_EDIT, ScopeType.SUPPLIER_STORE, 10L, "SupplierStore");
        assertThat(res.id()).isEqualTo(101L);
        assertThat(res.slotName()).isEqualTo("Morning Express");
        assertThat(res.maxOrdersPerDay()).isEqualTo(25);
    }

    @Test
    @DisplayName("Create slot rejects when start time is after end time")
    void createSlotInvalidTimes() {
        var req = new DeliverySlotDtos.CreateDeliverySlotRequest(
                "Invalid Slot",
                LocalTime.of(10, 0),
                LocalTime.of(8, 0),
                LocalTime.of(7, 0),
                25
        );

        assertThatThrownBy(() -> service.createSlot(1L, 10L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start time must be before end time");
    }

    @Test
    @DisplayName("Available slots returns availability and remaining capacity")
    void availableSlotsCalculation() {
        DeliverySlot slot1 = new DeliverySlot();
        slot1.setId(1L);
        slot1.setSlotName("Morning Slot");
        slot1.setStartTime(LocalTime.of(6, 0));
        slot1.setEndTime(LocalTime.of(9, 0));
        slot1.setOrderCutoffTime(LocalTime.of(23, 59)); // won't cut off
        slot1.setMaxOrdersPerDay(10);
        slot1.setActive(true);

        when(slotRepository.findBySupplierStoreIdAndActiveTrue(10L))
                .thenReturn(List.of(slot1));

        // Mock booked orders count callback
        doAnswer(inv -> {
            RowCallbackHandler rch = inv.getArgument(1);
            // Simulate 3 booked orders
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong(1)).thenReturn(1L);
            when(rs.getInt(2)).thenReturn(3);
            rch.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(10L), any());

        LocalDate tomorrow = LocalDate.now(DeliverySlotService.ZONE).plusDays(1);
        var available = service.getAvailableSlots(10L, tomorrow);

        assertThat(available).hasSize(1);
        assertThat(available.get(0).bookedOrders()).isEqualTo(3);
        assertThat(available.get(0).availableCapacity()).isEqualTo(7);
        assertThat(available.get(0).available()).isTrue();
    }
}
