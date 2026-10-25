package com.costonomy.mp.delivery.domain;

import java.math.BigDecimal;

/**
 * The supplier's share of a courier's cost is now lower than it was when the supplier was charged (D-204): the provider's
 * final charge came in under what the supplier agreed to. Published inside the transaction that learned it, for the
 * settlement module to give the excess back. Never published for a courier that has not yet had the goods, since nothing
 * has been charged for it.
 */
public record SupplierShareReduced(Long deliveryId, Long supplierOrderId, BigDecimal newShare) {
}
