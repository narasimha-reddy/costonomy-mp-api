package com.costonomy.mp.delivery.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A second owner for provider events (D-197).
 *
 * <p>A provider webhook names a consignment by the provider's own id. The supplier delivery flow looks that id up in
 * {@code delivery}; when it finds nothing, the event is offered to every handler registered here before it is
 * acknowledged and dropped. Food delivery registers one, because its consignments live in {@code food_delivery}.
 *
 * <p>This is an interface in {@code delivery} so that {@code delivery} never depends on a module that sits beside it.
 * It is consulted only on the "no supplier delivery" branch, so the supplier path is unchanged when it finds its own.
 */
public interface UnclaimedDeliveryEventHandler {

    /**
     * Process the event if this handler owns the consignment.
     *
     * @param providerCode       the provider the event came from, e.g. {@code PIDGE}
     * @param providerDeliveryId the provider's id for the consignment
     * @param payload            the provider's own object, unparsed
     * @return true if this handler owns the consignment and has dealt with the event (including deliberately
     *         ignoring it); false to let the next handler try
     */
    boolean handle(String providerCode, String providerDeliveryId, JsonNode payload);
}
