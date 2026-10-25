package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;

/**
 * Unifies supplier-own delivery milestone transitions across order and delivery routes (Issue 6).
 * Ensures both supplier_order and delivery rows transition together in one transaction,
 * enforces supplier-own mode, audits and publishes coherent events, rejects courier-carried orders,
 * and handles legacy orders without a delivery row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierDeliveryMovementService {

    private final DeliveryRepository deliveries;
    private final DeliveryTimeline timeline;
    private final AuditService auditService;
    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final SupplierCourierPayableSync courierPayables;

    private record OrderSnapshot(
            Long id,
            String orderNumber,
            Long supplierStoreId,
            Long outletId,
            SupplierOrderStatus status,
            com.costonomy.mp.procurement.domain.DeliveryMode deliveryMode,
            String supplierName
    ) {}

    @Transactional
    public void reportByOrder(Long actorId, Long supplierOrderId, SupplierOrderStatus target) {
        OrderSnapshot order = loadOrder(supplierOrderId);

        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, order.supplierStoreId(), "SupplierOrder");

        // Validate mode
        if (order.deliveryMode().isCourierCarried()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "A courier is carrying this order, so its movement is tracked for you.");
        }
        if (order.deliveryMode() == com.costonomy.mp.procurement.domain.DeliveryMode.PICKUP) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order is for pickup; it cannot be dispatched or delivered.");
        }

        DeliveryStatus targetDeliveryStatus = target == SupplierOrderStatus.OUT_FOR_DELIVERY
                ? DeliveryStatus.PICKED_UP : DeliveryStatus.DELIVERED;

        // Transition order if not already in target status
        if (order.status() != target) {
            if (!order.status().canTransitionTo(target, order.deliveryMode())) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This order can't move from %s to %s.".formatted(order.status(), target));
            }
            int updated = jdbc.update("""
                    UPDATE supplier_order
                       SET status = ?, version = version + 1, updated_at = now(6)
                     WHERE id = ? AND status = ?
                    """, target.name(), supplierOrderId, order.status().name());

            if (updated > 0) {
                auditService.record(actorId, null, "SUPPLIER_ORDER_" + target.name(), "SUPPLIER_ORDER",
                        supplierOrderId, order.status().name(), target.name(), null, "API");

                publishOrderEvent(order, target, actorId);
            }
        }

        // Transition linked delivery row if one exists
        var optDelivery = deliveries.findBySupplierOrderId(supplierOrderId);
        if (optDelivery.isPresent()) {
            var delivery = optDelivery.get();
            if (delivery.getMode() == DeliveryMode.COSTONOMY) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "A delivery partner is carrying this order, so its progress comes from them.");
            }
            transitionDelivery(actorId, delivery, targetDeliveryStatus);
        } else {
            log.info("Supplier order {} marked {} without a delivery row (legacy order)",
                    supplierOrderId, target);
        }
    }

    @Transactional
    public Delivery reportByDelivery(Long actorId, Long deliveryId, DeliveryStatus target) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, delivery.getSupplierStoreId(), "Delivery");

        if (!delivery.getMode().isSupplierReported()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "A delivery partner is carrying this order, so its progress comes from them.");
        }

        // Transition delivery
        transitionDelivery(actorId, delivery, target);

        // Transition linked order if present
        Long orderId = delivery.getSupplierOrderId();
        if (orderId != null) {
            OrderSnapshot order = loadOrder(orderId);
            SupplierOrderStatus targetOrderStatus = target == DeliveryStatus.PICKED_UP
                    ? SupplierOrderStatus.OUT_FOR_DELIVERY : SupplierOrderStatus.DELIVERED;

            if (order.status() != targetOrderStatus) {
                if (order.status().canTransitionTo(targetOrderStatus, order.deliveryMode())) {
                    int updated = jdbc.update("""
                            UPDATE supplier_order
                               SET status = ?, version = version + 1, updated_at = now(6)
                             WHERE id = ? AND status = ?
                            """, targetOrderStatus.name(), orderId, order.status().name());

                    if (updated > 0) {
                        auditService.record(actorId, null, "SUPPLIER_ORDER_" + targetOrderStatus.name(), "SUPPLIER_ORDER",
                                orderId, order.status().name(), targetOrderStatus.name(), null, "API");

                        publishOrderEvent(order, targetOrderStatus, actorId);
                    }
                }
            }
        }

        courierPayables.syncSoon(deliveryId);
        return delivery;
    }

    private void transitionDelivery(Long actorId, Delivery delivery, DeliveryStatus target) {
        if (delivery.getStatus() == target) {
            return;
        }
        if (delivery.getMode() == DeliveryMode.SUPPLIER_OWN
                && (delivery.getStatus() == DeliveryStatus.DELIVERY_REQUESTED
                || delivery.getStatus() == DeliveryStatus.QUOTE_RECEIVED
                || delivery.getStatus() == DeliveryStatus.PROVIDER_SELECTED)) {
            delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
        }
        if (!delivery.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery can't move from %s to %s.".formatted(delivery.getStatus(), target));
        }

        var previous = delivery.getStatus();
        delivery.setStatus(target);
        if (target == DeliveryStatus.PICKED_UP) {
            delivery.setPickedUpAt(Instant.now());
        }
        if (target == DeliveryStatus.DELIVERED) {
            if (delivery.getPickedUpAt() == null) {
                delivery.setPickedUpAt(Instant.now());
            }
            delivery.setDeliveredAt(Instant.now());
        }
        deliveries.save(delivery);

        timeline.record(delivery, target.eventName(), target, null);
        auditService.record(actorId, null, "DELIVERY_" + target.name(), "DELIVERY",
                delivery.getId(), previous.name(), target.name(), "Supplier own delivery", "API");
    }

    private OrderSnapshot loadOrder(Long orderId) {
        try {
            return jdbc.queryForObject("""
                    SELECT so.id, so.order_number, so.supplier_store_id, so.outlet_id, so.status, so.delivery_mode, s.name as supplier_name
                      FROM supplier_order so
                      JOIN supplier_store s ON s.id = so.supplier_store_id
                     WHERE so.id = ?
                    """, (rs, rowNum) -> new OrderSnapshot(
                    rs.getLong("id"),
                    rs.getString("order_number"),
                    rs.getLong("supplier_store_id"),
                    rs.getLong("outlet_id"),
                    SupplierOrderStatus.valueOf(rs.getString("status")),
                    com.costonomy.mp.procurement.domain.DeliveryMode.valueOf(rs.getString("delivery_mode")),
                    rs.getString("supplier_name")
            ), orderId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("SupplierOrder", orderId);
        }
    }

    private void publishOrderEvent(OrderSnapshot order, SupplierOrderStatus target, Long actorId) {
        String event = target == SupplierOrderStatus.OUT_FOR_DELIVERY ? "SupplierOrderDispatched" : "SupplierOrderDelivered";
        var payload = new HashMap<String, Object>();
        payload.put("orderNumber", order.orderNumber());
        payload.put("supplierName", order.supplierName());
        payload.put("supplierStoreId", order.supplierStoreId());
        payload.put("deliveryMode", order.deliveryMode().name());
        payload.put("outletId", order.outletId());
        outbox.publish(event, "SUPPLIER_ORDER", order.id(), payload, actorId);
    }
}
