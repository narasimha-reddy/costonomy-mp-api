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

    /** What the restaurant pays for a Costonomy delivery, and what the supplier pays towards it. They sum to the courier's fee. */
    public record FundedFee(BigDecimal buyerFee, BigDecimal supplierFunded) {
        public static FundedFee buyerPays(BigDecimal fee) {
            return new FundedFee(fee, BigDecimal.ZERO);
        }
    }

    /**
     * Split a courier's fee between the supplier and the restaurant (D-203). Only when the supplier opted in, and the
     * goods (before GST) reach their free-delivery threshold: the supplier pays up to their cap and the restaurant
     * pays whatever is left, so a quote above the cap is not free but is cheaper by the cap. Anything else leaves the
     * whole fee with the restaurant, as before.
     */
    public static FundedFee fundCostonomyFee(DeliveryDirectory.DeliveryPolicy policy, BigDecimal subtotal,
                                             BigDecimal courierFee) {
        if (courierFee == null || courierFee.signum() <= 0
                || !policy.fundCostonomyDelivery()
                || policy.fundedDeliveryCap() == null || policy.fundedDeliveryCap().signum() <= 0
                || !waivedByThreshold(policy, subtotal)) {
            return FundedFee.buyerPays(courierFee);
        }
        BigDecimal funded = courierFee.min(policy.fundedDeliveryCap());
        return new FundedFee(courierFee.subtract(funded), funded);
    }

    /**
     * The fee for pickup or supplier delivery, refusing a supplier that does not deliver or an order below
     * their delivery minimum.
     */
    public BigDecimal supplierCarriedFee(DeliveryDirectory.DeliveryPolicy policy, DeliveryMode mode,
                                         BigDecimal subtotal) {
        return supplierCarriedFee(policy, mode, subtotal, false);
    }

    /**
     * As above. {@code offeredOnAnswer} is true when the supplier offered to deliver on this very request (D-141):
     * that is their decision for this order and does not depend on the store's standing own-delivery switch.
     */
    public BigDecimal supplierCarriedFee(DeliveryDirectory.DeliveryPolicy policy, DeliveryMode mode,
                                         BigDecimal subtotal, boolean offeredOnAnswer) {
        return switch (mode) {
            case PICKUP -> BigDecimal.ZERO;
            case SUPPLIER_DELIVERY -> {
                if (!policy.ownDeliveryEnabled() && !offeredOnAnswer) {
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
        // Refusals first: waiving the fee must not also waive whether the supplier delivers at all.
        BigDecimal fee = supplierCarriedFee(policy, mode, subtotal);
        return waivedByThreshold(policy, subtotal) ? BigDecimal.ZERO : fee;
    }
}
