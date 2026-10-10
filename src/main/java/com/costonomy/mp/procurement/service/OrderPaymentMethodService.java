package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;

/**
 * Pay an unpaid card order another way (D-186).
 *
 * <p>A card order waits, unreleased, for a payment. If the restaurant would rather use the wallet or credit, the
 * order is funded that way and the card payment is retired the way a cancellation retires it, so it can never fund
 * the order and money that still arrives is sent back.
 *
 * <p>One transaction, locks in the documented order: the order, then the wallet (or credit agreement), then the
 * payment. A card authorisation arriving at the same moment takes the payment lock first and commits before it asks
 * for the order, so there is no cycle, and exactly one of the two wins.
 */
@Service
@RequiredArgsConstructor
public class OrderPaymentMethodService {

    private static final Set<String> TARGETS = Set.of("WALLET", "CREDIT");

    private final SupplierOrderRepository orders;
    private final OrderFunding funding;
    private final OrderReleaseService release;
    private final SupplierOrderMapper mapper;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;

    public ProcurementDtos.SupplierOrderResponse change(Long actorId, Long orderId, String method,
                                                        String idempotencyKey) {
        return idempotency.execute(actorId, "supplierOrder.paymentMethod", idempotencyKey,
                Map.of("orderId", orderId, "method", String.valueOf(method)),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> doChange(actorId, orderId, method)));
    }

    private ProcurementDtos.SupplierOrderResponse doChange(Long actorId, Long orderId, String method) {
        var order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));
        accessControl.requireScoped(actorId, Permissions.PAYMENT_CREATE, ScopeType.OUTLET, order.getOutletId(),
                "SupplierOrder");

        if (method == null || !TARGETS.contains(method) || !funding.supports(method)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose the wallet or credit.");
        }
        if (order.getStatus() != SupplierOrderStatus.DRAFT || !"PREPAID".equals(order.getPaymentMethod())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can't be paid another way any more.");
        }

        // Funded first: the wallet or credit checks, locks and messages are the ones used at creation.
        funding.arrangeFundingVia(method, order);
        // Then the card is retired. Its payment lock comes after the wallet's, as everywhere else.
        funding.relinquishUnfunded("PREPAID", orderId);

        order.setPaymentMethod(method);
        orders.saveAndFlush(order);
        release.releaseIfFunded(orderId);

        auditService.record(actorId, null, "SUPPLIER_ORDER_PAYMENT_METHOD_CHANGED", "SUPPLIER_ORDER", orderId,
                "PREPAID", method, "Paid another way before paying by card", "API");
        return mapper.toResponse(orders.findById(orderId).orElseThrow());
    }
}
