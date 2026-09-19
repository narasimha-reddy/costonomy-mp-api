package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
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
            case PICKED_UP -> advance(delivery,
                    SupplierOrderStatus.READY_FOR_PICKUP, SupplierOrderStatus.OUT_FOR_DELIVERY);
            case DELIVERED -> advance(delivery,
                    SupplierOrderStatus.OUT_FOR_DELIVERY, SupplierOrderStatus.DELIVERED);
            default -> { }
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
