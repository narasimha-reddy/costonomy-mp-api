package com.costonomy.mp.delivery.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a delivery module tells the payable about one delivery (D-204). Built by each module from its own entities, so
 * the payable never reaches into another module's tables to find out what happened.
 *
 * @param currentProviderDeliveryId the courier the delivery is with now; null when none holds it (released, cancelled,
 *                                  or the supplier carries it)
 * @param finalFee                  the courier's fee as booked
 * @param goodsWithCourier          the goods were picked up by the current courier
 * @param delivered                 the current courier delivered
 * @param failedAfterPickup         the current courier had the goods and the delivery failed
 * @param buyerFee                  what the restaurant was charged for this delivery
 * @param supplierFunded            what the supplier agreed to pay towards it at checkout
 * @param customersRefunded         the order was cancelled and the restaurant refunded, so neither the restaurant nor the
 *                                  supplier is to bear any of the courier's cost: it is wholly the platform's
 */
public record CourierFacts(
        String sourceType, Long deliveryId, Long supplierOrderId, Long outletId, Long supplierStoreId, String currency,
        String currentProviderDeliveryId, BigDecimal finalFee,
        boolean goodsWithCourier, boolean delivered, boolean failedAfterPickup,
        BigDecimal buyerFee, BigDecimal supplierFunded, boolean customersRefunded,
        List<Attempt> attempts) {

    /**
     * One booking attempt. Only those that reached the provider ({@code providerDeliveryId} set) can cost money.
     *
     * @param endedCause why the attempt ended, in the vocabulary of D-197 (RIDER_CANCELLED, DELIVERY_FAILED, COMPLETED...)
     */
    public record Attempt(Long attemptId, String providerCode, String providerDeliveryId, String outcome,
                          BigDecimal quotedAmount, String endedCause) {
    }
}
