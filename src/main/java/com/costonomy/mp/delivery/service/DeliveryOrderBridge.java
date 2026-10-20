package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves the supplier order when the consignment moves. Doc 03 §5, §23A.38.
 *
 * <p>{@code READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED} are the only
 * supplier-order transitions no human can make: {@code SupplierOrderStatus} gives
 * the supplier nothing past {@code READY_FOR_PICKUP}, because a supplier must not
 * be able to claim a pickup or a delivery a courier performed. So the courier's
 * event is the only thing that can advance them, and this is where that happens.
 *
 * <p>A {@link JdbcTemplate} update rather than procurement's repositories: this is
 * a write across a module edge, and the alternative — delivery importing
 * procurement's aggregate — is the dependency the directories exist to avoid. The
 * update is guarded on the current status so it cannot skip a state or re-apply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryOrderBridge {

    private final JdbcTemplate jdbc;

    @Transactional
    public void onDeliveryStatus(Delivery delivery, DeliveryStatus status) {
        switch (status) {
            case PICKED_UP -> {
                advance(delivery, SupplierOrderStatus.READY_FOR_PICKUP, SupplierOrderStatus.OUT_FOR_DELIVERY);
                chargeFundedDelivery(delivery);
            }
            case DELIVERED -> advance(delivery,
                    SupplierOrderStatus.OUT_FOR_DELIVERY, SupplierOrderStatus.DELIVERED);
            default -> { }
        }
    }

    /**
     * The supplier's share of the rider (D-203), owed from the moment a courier has the goods: the cost is incurred
     * whether or not the food reaches the door. A deduction the settlement takes from the supplier's payout.
     *
     * <p>Once per delivery ({@code uk_supplier_deduction_delivery}), so a replayed pickup event charges nothing more.
     * Only for a Costonomy courier; the order's funded amount is cleared if the supplier carries it instead.
     */
    private void chargeFundedDelivery(Delivery delivery) {
        if (delivery.getMode() != DeliveryMode.COSTONOMY) {
            return;
        }
        int recorded = jdbc.update("""
                insert into supplier_deduction
                    (supplier_store_id, supplier_order_id, dispute_refund_id, amount, reason, status, kind, delivery_id)
                select so.supplier_store_id, so.id, null, so.delivery_funded_amount,
                       concat('Costonomy delivery for order ', so.order_number), 'PENDING', 'DELIVERY_FUNDING', ?
                  from supplier_order so
                 where so.id = ? and so.delivery_funded_amount > 0
                on duplicate key update delivery_id = delivery_id
                """, delivery.getId(), delivery.getSupplierOrderId());
        if (recorded > 0) {
            log.info("Supplier charged for the Costonomy delivery of order {} (delivery {})",
                    delivery.getSupplierOrderId(), delivery.getId());
        }
    }

    /**
     * Compare-and-set, named by the enum rather than by a literal.
     *
     * <p>The guard is the {@code where} clause and not
     * {@code canTransitionTo} — deliberately, because this runs on a provider's
     * webhook and a replayed event must be a no-op rather than an exception. What
     * the constants buy is that renaming a status breaks the build here instead of
     * silently stranding every delivery at pickup.
     */
    private void advance(Delivery delivery, SupplierOrderStatus from, SupplierOrderStatus to) {
        int applied = jdbc.update("""
                update supplier_order
                   set status = ?, version = version + 1, updated_at = now(6)
                 where id = ? and status = ?
                """, to.name(), delivery.getSupplierOrderId(), from.name());

        if (applied == 0) {
            // Normal on a duplicate or replayed event: the order is already there.
            // Logged rather than thrown, because the delivery's own state is
            // correct and failing here would reject a legitimate provider event.
            log.debug("Supplier order {} was not in {} when delivery moved to {}",
                    delivery.getSupplierOrderId(), from, to);
        }
    }
}
