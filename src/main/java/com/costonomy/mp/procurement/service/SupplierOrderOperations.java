package com.costonomy.mp.procurement.service;

import com.costonomy.mp.delivery.service.OrderCancellationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Procurement's side of {@link OrderCancellationPort}. */
@Component
@RequiredArgsConstructor
public class SupplierOrderOperations implements OrderCancellationPort {

    private final SupplierOrderTransitions transitions;

    @Override
    public void cancelForOperations(Long actorId, Long supplierOrderId, String reason) {
        transitions.cancelByOperations(actorId, supplierOrderId, reason);
    }
}
