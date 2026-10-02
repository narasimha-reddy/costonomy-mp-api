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
    private final OrderFunding funding;
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
        // Locked, so "only one release happens" holds when two routes fire at the
        // same moment, not only when they arrive in turn. Unlocked, a confirm and
        // a webhook both read DRAFT and both released; one lost on the version
        // check and threw — a 500 for the customer if it was the confirm (D-099).
        var order = orders.lockById(supplierOrderId).orElse(null);
        if (order == null || order.getStatus() != SupplierOrderStatus.DRAFT) {
            return false;
        }
        if (!funding.isFundingSecured(supplierOrderId)) {
            return false;
        }

        Instant now = Instant.now();

        // Where it goes depends on whether anyone still has to agree to it, and
        // that is a fact about the order rather than about who is calling.
        //
        // It has to be read off the order, not passed in. Release is triggered
        // from wherever funding lands first — the confirm call, a provider
        // webhook, or the reconciliation sweep — and none of those know or should
        // know how the order was built. An earlier version took the target as an
        // argument and the webhook path quietly kept the old default, so an
        // already-accepted order was sent back to the supplier to accept again.
        //
        // A null procurement means it came from an intent (V23), where the
        // supplier committed to these quantities at these prices before any money
        // moved. There is nothing left to accept, so no countdown starts — one
        // against an order nobody needs to answer would expire an agreed order.
        // D-091 finished the job: there is no second acceptance at all now, so a
        // funded order is confirmed whatever created it, and no acceptance clock
        // starts. One against an order nobody needs to answer would expire an
        // order both sides had already agreed to and been paid for.
        SupplierOrderStatus target = SupplierOrderStatus.CONFIRMED;

        order.setStatus(target);
        order.setAcceptanceDeadline(null);
        // In the funding method's words — "AUTHORIZED" for a held card, "PAID" for a
        // wallet, "ON_CREDIT" for credit. It used to write "AUTHORIZED" for all three.
        // Responses read the live value (OrderFunding.paymentState); this copy is
        // for the reports that read the column.
        order.setPaymentStatus(funding.paymentState(order));
        orders.save(order);

        // The supplier's commitment is now an order. For credit this draws the
        // accepted value (D-091). Prepaid money is no longer taken here: since
        // D-103 it stays held until the supplier marks the order ready, so a
        // cancellation before then drops the hold instead of refunding a charge.
        //
        // Doc 01 §14 is unchanged: only the accepted commercial value is taken,
        // and under this flow the accepted amount is known at creation because
        // the order was built from what the supplier offered.
        funding.onOrderAccepted(order.getId(), order.getAcceptedAmount());

        auditService.record(null, null, "SUPPLIER_ORDER_RELEASED", "SUPPLIER_ORDER",
                order.getId(), SupplierOrderStatus.DRAFT.name(), target.name(),
                "Payment secured", "SYSTEM");

        var payload = new java.util.HashMap<String, Object>();
        payload.put("orderNumber", order.getOrderNumber());
        payload.put("supplierStoreId", order.getSupplierStoreId());
        payload.put("outletId", order.getOutletId());
        if (order.getAcceptanceDeadline() != null) {
            payload.put("acceptanceDeadline", order.getAcceptanceDeadline().toString());
        }
        // Two events, because the supplier is being told two different things.
        // "Released" has always meant "this needs your answer, and the clock has
        // started". An order built from an accepted request needs no answer --
        // notifying it as one told a supplier to go and accept an order they had
        // already agreed to, with a countdown that does not exist.
        // One event now: every released order is confirmed. "Released" used to
        // mean "this needs your answer, and the clock has started", which after
        // D-091 is never true.
        outbox.publish("SupplierOrderConfirmed",
                "SUPPLIER_ORDER", order.getId(), payload, null, now);

        log.info("Released order {} to supplier {} as {} — deadline {}",
                order.getOrderNumber(), order.getSupplierStoreId(), target,
                order.getAcceptanceDeadline());
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
