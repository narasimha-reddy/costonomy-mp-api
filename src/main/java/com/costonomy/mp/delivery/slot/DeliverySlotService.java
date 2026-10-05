package com.costonomy.mp.delivery.slot;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliverySlotService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final DeliverySlotRepository slotRepository;
    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<DeliverySlotDtos.DeliverySlotResponse> listSlots(Long actorId, Long supplierStoreId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        return slotRepository.findBySupplierStoreId(supplierStoreId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeliverySlotDtos.AvailableSlotResponse> getAvailableSlots(Long supplierStoreId, LocalDate targetDate) {
        LocalDate date = (targetDate != null) ? targetDate : LocalDate.now(ZONE);
        LocalDate today = LocalDate.now(ZONE);
        LocalTime nowTime = LocalTime.now(ZONE);

        List<DeliverySlot> slots = slotRepository.findBySupplierStoreIdAndActiveTrue(supplierStoreId);

        // Map slotId -> count of active booked orders for date
        Map<Long, Integer> bookedCounts = new HashMap<>();
        jdbc.query("""
                select delivery_slot_id, count(*)
                  from supplier_order
                 where supplier_store_id = ?
                   and scheduled_delivery_date = ?
                   and status not in ('CANCELLED', 'REJECTED')
                   and delivery_slot_id is not null
                 group by delivery_slot_id
                """,
                rs -> {
                    bookedCounts.put(rs.getLong(1), rs.getInt(2));
                },
                supplierStoreId, java.sql.Date.valueOf(date));

        return slots.stream()
                .map(slot -> {
                    int booked = bookedCounts.getOrDefault(slot.getId(), 0);
                    int remaining = Math.max(0, slot.getMaxOrdersPerDay() - booked);

                    boolean pastDate = date.isBefore(today);
                    boolean cutoffPassed = date.isEqual(today) && nowTime.isAfter(slot.getOrderCutoffTime());
                    // A slot that has already started today is not offered: it cannot be met (D-142).
                    boolean started = date.isEqual(today) && nowTime.isAfter(slot.getStartTime());
                    boolean capacityFull = remaining <= 0;

                    boolean isAvailable = !pastDate && !cutoffPassed && !started && !capacityFull;
                    String reason = null;
                    if (pastDate) {
                        reason = "Delivery date is in the past";
                    } else if (started) {
                        reason = "This slot has already started today";
                    } else if (cutoffPassed) {
                        reason = "Order cutoff (" + slot.getOrderCutoffTime() + ") has passed for today";
                    } else if (capacityFull) {
                        reason = "Slot capacity full (" + slot.getMaxOrdersPerDay() + " orders)";
                    }

                    return new DeliverySlotDtos.AvailableSlotResponse(
                            slot.getId(),
                            slot.getSlotName(),
                            slot.getStartTime(),
                            slot.getEndTime(),
                            slot.getOrderCutoffTime(),
                            slot.getMaxOrdersPerDay(),
                            booked,
                            remaining,
                            isAvailable,
                            reason
                    );
                })
                .toList();
    }

    /**
     * Refuse a slot the buyer cannot have (D-142): one that is not this store's, is switched off, or is not available
     * on that date (past, started, past its cutoff, or full). Order creation calls it, so the picker is not the only
     * thing standing between a buyer and a slot that cannot be met.
     */
    @Transactional(readOnly = true)
    public void requireBookable(Long supplierStoreId, Long slotId, LocalDate date) {
        if (date == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose a delivery day for that slot.");
        }
        var slot = getAvailableSlots(supplierStoreId, date).stream()
                .filter(candidate -> candidate.id().equals(slotId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That delivery slot isn't offered by this supplier."));
        if (!slot.available()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    slot.unavailableReason() == null ? "That delivery slot is not available."
                            : slot.unavailableReason() + ". Choose another slot.");
        }
    }

    @Transactional
    public DeliverySlotDtos.DeliverySlotResponse createSlot(
            Long actorId, Long supplierStoreId, DeliverySlotDtos.CreateDeliverySlotRequest request) {

        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        if (!request.startTime().isBefore(request.endTime())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Start time must be before end time");
        }

        DeliverySlot slot = new DeliverySlot();
        slot.setSupplierStoreId(supplierStoreId);
        slot.setSlotName(request.slotName());
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setOrderCutoffTime(request.orderCutoffTime());
        slot.setMaxOrdersPerDay(request.maxOrdersPerDay());
        slot.setActive(true);

        slotRepository.save(slot);
        return toResponse(slot);
    }

    @Transactional
    public DeliverySlotDtos.DeliverySlotResponse updateSlot(
            Long actorId, Long supplierStoreId, Long slotId, DeliverySlotDtos.UpdateDeliverySlotRequest request) {

        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        DeliverySlot slot = slotRepository.findByIdAndSupplierStoreId(slotId, supplierStoreId)
                .orElseThrow(() -> new NotFoundException("DeliverySlot", slotId));

        if (!request.startTime().isBefore(request.endTime())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Start time must be before end time");
        }

        slot.setSlotName(request.slotName());
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setOrderCutoffTime(request.orderCutoffTime());
        slot.setMaxOrdersPerDay(request.maxOrdersPerDay());
        if (request.active() != null) {
            slot.setActive(request.active());
        }

        slotRepository.save(slot);
        return toResponse(slot);
    }

    @Transactional
    public void deleteSlot(Long actorId, Long supplierStoreId, Long slotId) {
        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        DeliverySlot slot = slotRepository.findByIdAndSupplierStoreId(slotId, supplierStoreId)
                .orElseThrow(() -> new NotFoundException("DeliverySlot", slotId));

        slot.setActive(false);
        slotRepository.save(slot);
    }

    private DeliverySlotDtos.DeliverySlotResponse toResponse(DeliverySlot slot) {
        return new DeliverySlotDtos.DeliverySlotResponse(
                slot.getId(),
                slot.getSupplierStoreId(),
                slot.getSlotName(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getOrderCutoffTime(),
                slot.getMaxOrdersPerDay(),
                slot.isActive()
        );
    }
}
