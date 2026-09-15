package com.costonomy.mp.trust.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.trust.domain.Receiving;
import com.costonomy.mp.trust.domain.ReceivingItem;
import com.costonomy.mp.trust.domain.ReceivingStatus;
import com.costonomy.mp.trust.repository.ReceivingItemRepository;
import com.costonomy.mp.trust.repository.ReceivingRepository;
import com.costonomy.mp.trust.web.dto.TrustDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checking a delivery in. Doc 01 §22, doc 03 §11, §23A.22.
 *
 * <p><b>Every line must be answered, and the numbers must add up.</b>
 * {@code received + damaged + missing = accepted}. §23A.22 forbids a blind
 * "Complete" button, and defaulting the quantities would produce exactly that: a
 * tap-through that records a perfect delivery nobody counted. A mismatch is
 * refused with the arithmetic in the message, because the usual cause is a typo
 * rather than a dispute.
 *
 * <p><b>Receiving adds to the order; it never rewrites it.</b> Doc 03 §11. The
 * accepted quantities stay as the supplier committed to them — that is what was
 * paid for and what a dispute is argued from — and what arrived is written to
 * {@code fulfilled_quantity}, which V10 left null for this moment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceivingService {

    private final ReceivingRepository receivings;
    private final ReceivingItemRepository receivingItems;
    private final TrustDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public TrustDtos.ReceivingResponse receive(Long actorId, Long supplierOrderId,
                                               TrustDtos.ReceiveRequest request) {

        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        accessControl.requireScoped(actorId, Permissions.ORDER_RECEIVE,
                ScopeType.OUTLET, order.outletId(), "SupplierOrder");

        var existing = receivings.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            // A retry of a receiving that already landed. Returning it is the true
            // answer; a second would double every delivered quantity the fill rate
            // reads.
            return toResponse(existing, order);
        }

        if (!"DELIVERED".equals(order.status())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order hasn't been delivered yet.");
        }

        var lines = directory.linesOf(supplierOrderId);
        Map<Long, TrustDirectory.OrderLine> byId = new HashMap<>();
        lines.forEach(line -> byId.put(line.itemId(), line));

        Map<Long, TrustDtos.ReceiveItemRequest> answers = new HashMap<>();
        request.items().forEach(item -> answers.put(item.supplierOrderItemId(), item));

        // Every line, because an unanswered one is ambiguous between "arrived
        // fine" and "nobody looked", and those are very different facts.
        for (var line : lines) {
            if (!answers.containsKey(line.itemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Check in every line — including the ones that arrived in full.");
            }
        }

        var receiving = new Receiving();
        receiving.setSupplierOrderId(supplierOrderId);
        receiving.setOutletId(order.outletId());
        receiving.setSupplierStoreId(order.supplierStoreId());
        receiving.setNotes(request.notes());
        receiving.setReceivedBy(actorId);
        receiving.setReceivedAt(Instant.now());
        receiving.setStatus(ReceivingStatus.RECEIVED);
        receivings.save(receiving);

        BigDecimal totalAccepted = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        BigDecimal totalDamaged = BigDecimal.ZERO;
        BigDecimal totalMissing = BigDecimal.ZERO;
        boolean discrepancy = false;

        List<ReceivingItem> items = new ArrayList<>();
        for (var answer : request.items()) {
            var line = byId.get(answer.supplierOrderItemId());
            if (line == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That line isn't part of this order.");
            }

            BigDecimal accepted = line.acceptedQuantity() == null
                    ? BigDecimal.ZERO : line.acceptedQuantity();
            BigDecimal counted = answer.receivedQuantity()
                    .add(answer.damagedQuantity())
                    .add(answer.missingQuantity());

            if (counted.compareTo(accepted) != 0) {
                // Refused rather than reconciled for them. Over-delivery included:
                // the restaurant paid for the accepted quantity, and quietly
                // recording more would put stock on the books that nobody priced.
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        ("Received, damaged and missing must add up to the %s %s accepted "
                                + "for %s — you entered %s.")
                                .formatted(accepted.stripTrailingZeros().toPlainString(),
                                        line.unit(), line.productName(),
                                        counted.stripTrailingZeros().toPlainString()));
            }

            var item = new ReceivingItem();
            item.setReceivingId(receiving.getId());
            item.setSupplierOrderItemId(answer.supplierOrderItemId());
            item.setAcceptedQuantity(accepted);
            item.setReceivedQuantity(answer.receivedQuantity());
            item.setDamagedQuantity(answer.damagedQuantity());
            item.setMissingQuantity(answer.missingQuantity());
            item.setUnit(line.unit());
            item.setNote(answer.note());
            items.add(item);

            totalAccepted = totalAccepted.add(accepted);
            totalReceived = totalReceived.add(answer.receivedQuantity());
            totalDamaged = totalDamaged.add(answer.damagedQuantity());
            totalMissing = totalMissing.add(answer.missingQuantity());
            discrepancy |= item.isShort();

            // Added to the order line, not over it. This is what makes a real fill
            // rate possible (D-019's last gap).
            directory.recordFulfilled(answer.supplierOrderItemId(), answer.receivedQuantity());
        }
        receivingItems.saveAll(items);

        receiving.setTotalAcceptedQuantity(totalAccepted);
        receiving.setTotalReceivedQuantity(totalReceived);
        receiving.setTotalDamagedQuantity(totalDamaged);
        receiving.setTotalMissingQuantity(totalMissing);
        receiving.setHasDiscrepancy(discrepancy);
        receivings.save(receiving);

        // DELIVERED → COMPLETED. The order is finished because the restaurant says
        // the goods are in, which is the only party that can know.
        directory.completeOrder(supplierOrderId);

        auditService.record(actorId, null, "ORDER_RECEIVED", "SUPPLIER_ORDER",
                supplierOrderId, "DELIVERED", "COMPLETED",
                discrepancy ? "Received with discrepancies" : "Received in full", "API");

        outbox.publish("ReceivingCompleted", "SUPPLIER_ORDER", supplierOrderId,
                Map.of("outletId", order.outletId(),
                        "supplierStoreId", order.supplierStoreId(),
                        "orderNumber", order.orderNumber(),
                        "hasDiscrepancy", discrepancy),
                actorId);

        return toResponse(receiving, order);
    }

    @Transactional(readOnly = true)
    public TrustDtos.ReceivingResponse forOrder(Long actorId, Long supplierOrderId) {
        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        requireEitherSide(actorId, order, supplierOrderId);

        var receiving = receivings.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("Receiving", supplierOrderId));
        return toResponse(receiving, order);
    }

    /** Both parties may read a receiving record; the supplier is being judged by it. */
    private void requireEitherSide(Long actorId, TrustDirectory.OrderInfo order, Long orderId) {
        if (accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, order.outletId())
                || accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, order.supplierStoreId())) {
            return;
        }
        log.warn("Scope violation: user={} SupplierOrder={} — reported as not found",
                actorId, orderId);
        throw new NotFoundException("SupplierOrder", orderId);
    }

    private TrustDtos.ReceivingResponse toResponse(Receiving receiving,
                                                   TrustDirectory.OrderInfo order) {
        Map<Long, TrustDirectory.OrderLine> byId = new HashMap<>();
        directory.linesOf(receiving.getSupplierOrderId())
                .forEach(line -> byId.put(line.itemId(), line));

        var items = receivingItems.findByReceivingIdOrderByIdAsc(receiving.getId()).stream()
                .map(item -> {
                    var line = byId.get(item.getSupplierOrderItemId());
                    return new TrustDtos.ReceivingItemResponse(
                            item.getId(), item.getSupplierOrderItemId(),
                            line == null ? null : line.productName(),
                            line == null ? null : line.requestedQuantity(),
                            item.getAcceptedQuantity(), item.getReceivedQuantity(),
                            item.getDamagedQuantity(), item.getMissingQuantity(),
                            item.getUnit(), item.getNote());
                })
                .toList();

        return new TrustDtos.ReceivingResponse(
                receiving.getId(), receiving.getSupplierOrderId(), order.orderNumber(),
                receiving.getStatus(), receiving.getHasDiscrepancy(),
                receiving.getTotalAcceptedQuantity(), receiving.getTotalReceivedQuantity(),
                receiving.getTotalDamagedQuantity(), receiving.getTotalMissingQuantity(),
                receiving.getNotes(), receiving.getReceivedAt(), items);
    }
}
