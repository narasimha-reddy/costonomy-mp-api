package com.costonomy.mp.procurement.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes an order to whatever funds it. Prepaid money, or supplier credit.
 *
 * <p>Procurement depends on this rather than on an {@link OrderFundingPort}
 * directly, because from Phase 10 there is more than one — and the choice is a
 * property of the order, not of the caller. Every caller in procurement asks the
 * same four questions regardless of how an order is paid for, which is exactly
 * what makes a router the right shape here rather than a branch at each site.
 *
 * <p>Deliberately <b>not</b> an {@code OrderFundingPort} itself. Implementing the
 * interface it collects would make Spring inject this bean into its own list of
 * implementations, and every call would recurse.
 */
@Service
@Slf4j
public class OrderFunding {

    private final Map<String, OrderFundingPort> byMethod;
    private final SupplierOrderRepository orders;

    public OrderFunding(List<OrderFundingPort> implementations, SupplierOrderRepository orders) {
        this.byMethod = implementations.stream().collect(Collectors.toMap(
                OrderFundingPort::paymentMethod, Function.identity()));
        this.orders = orders;
        log.info("Funding methods available: {}", byMethod.keySet());
    }

    /** Whether an order paid for this way can be funded at all. */
    public boolean supports(String paymentMethod) {
        return byMethod.containsKey(paymentMethod);
    }

    /**
     * Arrange funding, grouping the orders by how each is paid for.
     *
     * <p>Grouped rather than dispatched one at a time so an implementation can see
     * the whole batch — a checkout covering three suppliers is one decision for the
     * customer even though it is three payments.
     */
    public List<OrderFundingPort.FundingIntent> arrangeFunding(List<SupplierOrder> created) {
        var byPaymentMethod = new LinkedHashMap<String, List<SupplierOrder>>();
        created.forEach(order -> byPaymentMethod
                .computeIfAbsent(order.getPaymentMethod(), key -> new ArrayList<>())
                .add(order));

        var intents = new ArrayList<OrderFundingPort.FundingIntent>();
        byPaymentMethod.forEach((method, group) -> intents.addAll(port(method).arrangeFunding(group)));
        return intents;
    }

    public boolean isFundingSecured(Long supplierOrderId) {
        return forOrder(supplierOrderId).isFundingSecured(supplierOrderId);
    }

    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        forOrder(supplierOrderId).onOrderAccepted(supplierOrderId, acceptedAmount);
    }

    public void onOrderUnfulfilled(Long supplierOrderId, String reason) {
        forOrder(supplierOrderId).onOrderUnfulfilled(supplierOrderId, reason);
    }

    private OrderFundingPort forOrder(Long supplierOrderId) {
        var order = orders.findById(supplierOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return port(order.getPaymentMethod());
    }

    private OrderFundingPort port(String paymentMethod) {
        var port = byMethod.get(paymentMethod);
        if (port == null) {
            // Refused rather than defaulted. Guessing a funding method for an order
            // is how an order reaches a supplier with nothing behind it, which is
            // the one thing guardrail 16 forbids.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment method isn't available.");
        }
        return port;
    }
}
