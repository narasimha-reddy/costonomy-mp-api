package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.*;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Creates supplier orders from a validated procurement, in one transaction.
 *
 * <p>A <b>separate bean</b> from {@link ProcurementSubmissionService}, and that is
 * the mechanism rather than organisation. Spring's {@code @Transactional} works
 * through a proxy, so a method invoked through {@code this} — including from
 * inside a lambda, which is easy to miss — never reaches it and the annotation is
 * silently ignored. Submission creates a procurement transition, several supplier
 * orders and all their items; running that without a transaction would leave a
 * partial order set behind on any failure.
 *
 * <p>The same trap is documented on {@code IdempotencyStore}, {@code OtpAttemptStore}
 * and {@code RefreshTokenStore}. Do not merge this back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcurementSubmitter {

    private final ProcurementRepository procurements;
    private final ProcurementItemRepository procurementItems;
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderItemRepository supplierOrderItems;
    private final ProcurementService procurementService;
    private final RequirementService requirementService;
    private final SupplierOrderMapper mapper;
    private final ProcurementDirectory directory;
    private final OrderNumberGenerator orderNumbers;
    private final ProcurementStateStore stateStore;
    private final OrderFunding funding;
    private final OrderReleaseService orderRelease;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public ProcurementDtos.SubmitResponse submit(Long actorId, Long procurementId) {
        var procurement = procurements.findById(procurementId)
                .orElseThrow(() -> new NotFoundException("Procurement", procurementId));

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, procurement.getOutletId(), "Procurement");

        // Already submitted. Return what exists rather than failing: a retry whose
        // idempotency key was lost is a normal client outcome, and the caller's
        // question — "did my order go through?" — has a true answer.
        if (procurement.getStatus() == ProcurementStatus.SUBMITTED) {
            return mapper.submitResponse(procurement);
        }

        assertSubmittable(procurement);

        var items = procurementItems.findByProcurementIdAndStatus(procurement.getId(), "ACTIVE");
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Your cart is empty.");
        }

        // Re-checked here, inside the creating transaction, not trusted from the
        // earlier validation. Between then and now a store can have gone offline.
        var stores = directory.stores(items.stream()
                .map(ProcurementItem::getSupplierStoreId).distinct().toList());

        for (ProcurementItem item : items) {
            var store = stores.get(item.getSupplierStoreId());
            if (store == null || !store.tradeable()) {
                // FAILED, not CANCELLED: doc 03 §4 wants retry semantics, so the
                // cart survives and the restaurant revalidates rather than rebuilds.
                //
                // Written through ProcurementStateStore because the throw below
                // would otherwise roll it back, leaving the order looking READY
                // while the restaurant is told a supplier went offline.
                stateStore.markFailed(procurement.getId(),
                        "Supplier store %d stopped accepting orders".formatted(item.getSupplierStoreId()),
                        actorId);
                throw new BusinessException(ErrorCode.SUPPLIER_OFFLINE,
                        "A supplier in your order stopped accepting orders. Please review and try again.");
            }
        }

        if (!funding.supports(procurement.getPaymentMethod())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment method isn't available.");
        }

        Instant now = Instant.now();
        var byStore = new LinkedHashMap<Long, List<ProcurementItem>>();
        items.forEach(item -> byStore
                .computeIfAbsent(item.getSupplierStoreId(), key -> new ArrayList<>())
                .add(item));

        // Orders are created in DRAFT with **no acceptance deadline**. Neither the
        // supplier nor the clock starts until the money is secured — a deadline
        // ticking against an order the supplier cannot yet see would hand them an
        // already-expired order the moment it appeared.
        List<SupplierOrder> created = new ArrayList<>();
        for (var entry : byStore.entrySet()) {
            created.add(createSupplierOrder(procurement, entry.getKey(), entry.getValue(),
                    stores.get(entry.getKey()), now, actorId));
        }

        // Guardrail 16, doc 01 §14: the supplier sees nothing until the order is
        // funded. What that takes depends on the method — a prepaid order gets an
        // intent the customer must still complete, while credit is reserved right
        // here — so this asks for funding and then releases whatever is already
        // secured, rather than branching on the payment method.
        //
        // For prepaid nothing is secured yet, so the release is a no-op and the
        // orders wait for the confirm call, the webhook or the reconciliation
        // sweep. For credit every order is secured, so they all go out now.
        var intents = funding.arrangeFunding(created);
        orderRelease.releaseProcurement(procurement.getId());

        procurement.setStatus(ProcurementStatus.SUBMITTED);
        procurement.setSubmittedAt(now);
        procurements.save(procurement);

        // The requirements this order serves are now being sourced.
        //
        // Derived from the lines rather than read from procurement.requirementId:
        // a cart is usually built by adding offers one at a time, each linked to
        // the requirement item it serves, and there may be several. Relying on a
        // single header field would silently miss all but one.
        //
        // Note this credits *no* quantity. That happens only when a supplier
        // accepts (guardrail 14) — placing an order is a hope, and decrementing on
        // hope would make a rejected order look fulfilled.
        markRequirementsSourcing(items, actorId);
        if (procurement.getRequirementId() != null) {
            requirementService.markSourcing(procurement.getRequirementId(), actorId);
        }

        auditService.record(actorId, null, "PROCUREMENT_SUBMITTED", "PROCUREMENT",
                procurement.getId(), ProcurementStatus.READY.name(),
                ProcurementStatus.SUBMITTED.name(),
                "%d supplier orders".formatted(created.size()), "API");

        outbox.publish("ProcurementSubmitted", "PROCUREMENT", procurement.getId(),
                Map.of("outletId", procurement.getOutletId(),
                        "supplierOrderCount", created.size(),
                        "totalAmount", procurement.getTotalAmount().toPlainString()),
                actorId, now);

        return new ProcurementDtos.SubmitResponse(
                procurement.getId(), procurement.getStatus(),
                created.stream().map(mapper::toResponse).toList(),
                intents.stream()
                        .map(intent -> new ProcurementDtos.PaymentIntentResponse(
                                intent.supplierOrderId(), intent.paymentId(), intent.provider(),
                                intent.providerOrderId(), intent.amount(), intent.currency(),
                                intent.publicKey()))
                        .toList());
    }

    /** Move every requirement touched by these lines into SOURCING. */
    private void markRequirementsSourcing(List<ProcurementItem> items, Long actorId) {
        var requirementItemIds = items.stream()
                .map(ProcurementItem::getRequirementItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (requirementItemIds.isEmpty()) {
            return;
        }
        requirementService.markSourcingForItems(requirementItemIds, actorId);
    }

    private SupplierOrder createSupplierOrder(
            Procurement procurement, Long storeId, List<ProcurementItem> items,
            ProcurementDirectory.StoreInfo store, Instant now, Long actorId) {

        int slaSeconds = store.responseSlaSeconds() == null || store.responseSlaSeconds() <= 0
                ? 60 : store.responseSlaSeconds();

        var order = new SupplierOrder();
        order.setProcurementId(procurement.getId());
        order.setSupplierStoreId(storeId);
        order.setOutletId(procurement.getOutletId());
        order.setOrderNumber(orderNumbers.next());
        // DRAFT until funded. The SLA is snapshotted now — it is the store's
        // commitment at the time of ordering — but the deadline itself is set when
        // the order is released, because that is when the supplier can first act.
        order.setStatus(SupplierOrderStatus.DRAFT);
        order.setResponseSlaSeconds(slaSeconds);
        order.setAcceptanceDeadline(null);
        order.setPaymentMethod(procurement.getPaymentMethod());
        order.setPaymentStatus("PENDING");

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;
        for (ProcurementItem item : items) {
            subtotal = subtotal.add(item.getLineItemValue());
            gst = gst.add(item.getLineGst());
        }
        order.setSubtotal(Pricing.money(subtotal));
        order.setGstAmount(Pricing.money(gst));
        order.setDeliveryFee(BigDecimal.ZERO);
        order.setTotalAmount(Pricing.money(subtotal.add(gst)));
        // Zero until the supplier answers. Doc 01 §14: only the accepted value is
        // ever captured, so this stays zero for an order nobody accepted.
        order.setAcceptedAmount(BigDecimal.ZERO);

        supplierOrders.saveAndFlush(order);

        for (ProcurementItem item : items) {
            var orderItem = new SupplierOrderItem();
            orderItem.setSupplierOrderId(order.getId());
            orderItem.setProcurementItemId(item.getId());
            orderItem.setRequirementItemId(item.getRequirementItemId());
            orderItem.setCanonicalProductId(item.getCanonicalProductId());
            orderItem.setSupplierSkuId(item.getSupplierSkuId());
            orderItem.setRequestedQuantity(item.getRequestedQuantity());
            // Null, not zero: the supplier has not answered yet, and zero would
            // mean they declined this line.
            orderItem.setAcceptedQuantity(null);
            orderItem.setUnit(item.getUnit());
            orderItem.setUnitPriceSnapshot(item.getUnitPriceSnapshot());
            orderItem.setGstRateSnapshot(item.getGstRateSnapshot());
            orderItem.setLineItemValue(item.getLineItemValue());
            orderItem.setLineGst(item.getLineGst());
            orderItem.setLineTotal(item.getLineTotal());
            orderItem.setStatus("PENDING");
            supplierOrderItems.save(orderItem);
        }

        auditService.record(actorId, null, "SUPPLIER_ORDER_CREATED", "SUPPLIER_ORDER",
                order.getId(), null, SupplierOrderStatus.PENDING_ACCEPTANCE.name(),
                order.getOrderNumber(), "API");

        outbox.publish("SupplierOrderCreated", "SUPPLIER_ORDER", order.getId(),
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierStoreId", storeId,
                        "outletId", procurement.getOutletId(),
                        "totalAmount", order.getTotalAmount().toPlainString()),
                actorId, now);

        return order;
    }

    /**
     * Everything that must hold before a cart becomes orders.
     *
     * <p>Each check has its own error code, because "you can't submit this" is
     * useless to a restaurant staring at a blocked checkout — they need to know
     * whether to wait for an approver, revalidate, or fix a line.
     */
    private void assertSubmittable(Procurement procurement) {
        // Approval is checked first, deliberately. An order waiting on an approver
        // is also not in a submittable *status*, so checking status first would
        // report "please review your order" to someone whose order is fine and is
        // simply waiting on their manager — and they would review it again, and
        // again.
        if (!procurement.isApprovalSatisfied()) {
            throw new BusinessException(ErrorCode.APPROVAL_REQUIRED);
        }
        if (procurement.getStatus() != ProcurementStatus.READY
                && procurement.getStatus() != ProcurementStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Please review your order before placing it.");
        }
        if (procurement.isValidationStale(procurementService.validationTtl())) {
            // The prices were last confirmed too long ago to trust. Doc 01 §11
            // requires revalidation at checkout, and this is where "too long"
            // becomes a refusal rather than a warning.
            throw new BusinessException(ErrorCode.PRICE_CHANGED,
                    "Prices may have changed since you last checked. Please review your order.");
        }
    }

}
