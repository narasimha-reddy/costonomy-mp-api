package com.costonomy.mp.procurement.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Releases a funded order to its supplier, and starts its clock.
 *
 * <p>The moment guardrail 16 protects. An order sits in {@code DRAFT} with no
 * acceptance deadline from the instant it is created until its payment is
 * authorised; only then does the supplier see it, and only then does the
 * countdown begin.
 *
 * <p><b>Setting the deadline here rather than at submission is the point.</b>
 * A deadline stamped at submission would run while the customer was still typing
 * their card details, and a supplier could be handed an order that expired before
 * it appeared — a 60-second SLA leaves no room for a slow checkout. Doc 13 makes
 * the deadline the supplier's commitment window, which cannot start before they
 * can act.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderReleaseService {

    private final SupplierOrderRepository orders;
    private final OrderFundingPort funding;
    private final AuditService auditService;
    private final OutboxService outbox;

    /**
     * Release an order if its funding is secured.
     *
     * <p>Idempotent and safe to call from anywhere the funding state might have
     * changed — a payment confirmation, a webhook, or the reconciliation job. All
     * three routes can fire for the same payment, and only one release happens.
     *
     * @return true if this call released it
     */
    @Transactional
    public boolean releaseIfFunded(Long supplierOrderId) {
        var order = orders.findById(supplierOrderId).orElse(null);
        if (order == null || order.getStatus() != SupplierOrderStatus.DRAFT) {
            return false;
        }
        if (!funding.isFundingSecured(supplierOrderId)) {
            return false;
        }

        Instant now = Instant.now();
        order.setStatus(SupplierOrderStatus.PENDING_ACCEPTANCE);
        // The clock starts now, not at submission.
        order.setAcceptanceDeadline(now.plusSeconds(order.getResponseSlaSeconds()));
        order.setPaymentStatus("AUTHORIZED");
        orders.save(order);

        auditService.record(null, null, "SUPPLIER_ORDER_RELEASED", "SUPPLIER_ORDER",
                order.getId(), SupplierOrderStatus.DRAFT.name(),
                SupplierOrderStatus.PENDING_ACCEPTANCE.name(),
                "Payment secured", "SYSTEM");

        outbox.publish("SupplierOrderReleased", "SUPPLIER_ORDER", order.getId(),
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "outletId", order.getOutletId(),
                        "acceptanceDeadline", order.getAcceptanceDeadline().toString()),
                null, now);

        log.info("Released order {} to supplier {} — deadline {}",
                order.getOrderNumber(), order.getSupplierStoreId(), order.getAcceptanceDeadline());
        return true;
    }

    /**
     * Abandon an order whose payment failed.
     *
     * <p>Cancelled rather than left in DRAFT, so the restaurant sees what happened
     * instead of an order that simply never moved. Doc 01 §14: a payment failure
     * means the supplier receives nothing — and here, never received anything.
     */
    @Transactional
    public void abandonUnfunded(Long supplierOrderId, String reason) {
        var order = orders.findById(supplierOrderId).orElse(null);
        if (order == null || order.getStatus() != SupplierOrderStatus.DRAFT) {
            return;
        }

        order.setStatus(SupplierOrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setPaymentStatus("FAILED");
        order.setRejectionReason(reason);
        orders.save(order);

        auditService.record(null, null, "SUPPLIER_ORDER_ABANDONED", "SUPPLIER_ORDER",
                order.getId(), SupplierOrderStatus.DRAFT.name(),
                SupplierOrderStatus.CANCELLED.name(), reason, "SYSTEM");
    }

    /** Release every order of a procurement whose funding is now secured. */
    @Transactional
    public int releaseProcurement(Long procurementId) {
        int released = 0;
        for (SupplierOrder order : orders.findByProcurementId(procurementId)) {
            if (releaseIfFunded(order.getId())) {
                released++;
            }
        }
        return released;
    }
}
