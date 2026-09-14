package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.*;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Turns a validated procurement into supplier orders. Doc 04 §10, doc 03 §5.
 *
 * <p>Submission is the moment a cart becomes a commitment, and doc 04 §10 lists
 * what has to happen in one transaction: revalidate, compute authoritative values,
 * check approval, create the orders, emit the events.
 *
 * <p>Three things worth knowing.
 *
 * <p><b>It revalidates rather than trusting the last validation.</b> The client
 * saw {@code submittable: true}, but that was a moment ago and offers move.
 * Guardrail 4 — a client-observed state is never the authority — so everything the
 * validation checked is checked again here, inside the transaction that creates
 * the orders.
 *
 * <p><b>The acceptance deadline is snapshotted, not referenced.</b> Doc 13: the
 * SLA is per-store and configurable, and a supplier changing it must not move the
 * deadline on an order already in flight. The countdown the supplier sees
 * (§23A.34) and the timeout job both read this one column.
 *
 * <p><b>It is idempotent.</b> Doc 04 §21 requires it, and a duplicate submission
 * would otherwise place two real orders with two suppliers. Two guards: the
 * {@code Idempotency-Key} at the API layer, and
 * {@code uk_supplier_order_procurement_store} underneath — because the two
 * requests may not carry the same key (a retry after a client crash often does
 * not), and the database constraint does not care.
 *
 * <p><b>Known gap — payment is not yet enforced.</b> Doc 01 §14 and guardrail 16
 * require payment authorization to succeed before a supplier sees an order.
 * Payments arrive in Phase 9. Until then a submitted order carries
 * {@code paymentStatus = PENDING} and still reaches the supplier. The insertion
 * point is marked below, and this is recorded as OPEN-004 so it cannot be lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcurementSubmissionService {

    private final ProcurementRepository procurements;
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderMapper mapper;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final ProcurementSubmitter submitter;

    /**
     * Submit, at most once per idempotency key.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}. The idempotency claim has
     * to commit in its own transaction before the work runs (see
     * {@code IdempotencyStore}), and the work itself runs in
     * {@link ProcurementSubmitter} — a separate bean, because a
     * {@code @Transactional} method invoked through {@code this} does not pass
     * through Spring's proxy and the annotation is silently ignored. Calling
     * {@code doSubmit} from the lambda below would have left the whole submission
     * — several supplier orders and their items — running without a transaction.
     */
    public ProcurementDtos.SubmitResponse submit(
            Long actorId, Long procurementId, String idempotencyKey) {

        return idempotency.execute(actorId, "procurement.submit", idempotencyKey,
                Map.of("procurementId", procurementId),
                ProcurementDtos.SubmitResponse.class,
                () -> submitter.submit(actorId, procurementId));
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProcurementDtos.SupplierOrderResponse> ordersForProcurement(
            Long actorId, Long procurementId) {

        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");

        return supplierOrders.findByProcurementId(procurementId).stream()
                .map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ProcurementDtos.SupplierOrderResponse> ordersForOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");
        return supplierOrders.findByOutletIdOrderByCreatedAtDesc(outletId).stream()
                .map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProcurementDtos.SupplierOrderResponse getOrder(Long actorId, Long orderId) {
        var order = supplierOrders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        // Visible to both sides — the restaurant that placed it and the supplier
        // that received it — and to nobody else. Two scopes, either sufficient.
        boolean restaurantSide = accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, order.getOutletId());
        boolean supplierSide = accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, order.getSupplierStoreId());

        if (!restaurantSide && !supplierSide) {
            throw new NotFoundException("SupplierOrder", orderId);
        }
        return mapper.toResponse(order);
    }

}
