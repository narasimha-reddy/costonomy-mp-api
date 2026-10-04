package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.procurement.domain.DeliveryMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * What a supplier-carried or pickup order costs to deliver, from the store's own delivery policy.
 *
 * <p>One place for it so the intent path and subscriptions cannot drift: a supplier who quotes their own
 * delivery is quoting their own delivery, and it never becomes the platform's figure. Costonomy delivery
 * is a stored courier quote and is not computed here.
 */
@Component
public class DeliveryCharges {

    private final DeliveryDirectory policies;

    public DeliveryCharges(DeliveryDirectory policies) {
        this.policies = policies;
    }

    /** Whether the store waives carriage for an order of this goods value. */
    public static boolean waivedByThreshold(DeliveryDirectory.DeliveryPolicy policy, BigDecimal subtotal) {
        return policy.freeDeliveryThreshold() != null
                && policy.freeDeliveryThreshold().compareTo(BigDecimal.ZERO) > 0
                && subtotal != null
                && subtotal.compareTo(policy.freeDeliveryThreshold()) >= 0;
    }

    /**
     * The fee for pickup or supplier delivery, refusing a supplier that does not deliver or an order below
     * their delivery minimum.
     */
    public BigDecimal supplierCarriedFee(DeliveryDirectory.DeliveryPolicy policy, DeliveryMode mode,
                                         BigDecimal subtotal) {
        return switch (mode) {
            case PICKUP -> BigDecimal.ZERO;
            case SUPPLIER_DELIVERY -> {
                if (!policy.ownDeliveryEnabled()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "This supplier doesn't deliver. Choose pickup or our delivery.");
                }
                if (policy.ownDeliveryMinOrderValue() != null
                        && subtotal != null
                        && subtotal.compareTo(policy.ownDeliveryMinOrderValue()) < 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Supplier delivery requires at least ₹%s of goods."
                                    .formatted(policy.ownDeliveryMinOrderValue().stripTrailingZeros().toPlainString()));
                }
                yield policy.ownDeliveryFee() == null ? BigDecimal.ZERO : policy.ownDeliveryFee();
            }
            case COSTONOMY_DELIVERY -> throw new IllegalArgumentException(
                    "Costonomy delivery is a stored courier quote, not a policy fee");
        };
    }

    /** The fee for a store and mode, applying the free-delivery threshold. Subscriptions use this. */
    public BigDecimal feeFor(Long supplierStoreId, DeliveryMode mode, BigDecimal subtotal) {
        var policy = policies.deliveryPolicy(supplierStoreId);
        if (waivedByThreshold(policy, subtotal)) {
            return BigDecimal.ZERO;
        }
        return supplierCarriedFee(policy, mode, subtotal);
    }
}
