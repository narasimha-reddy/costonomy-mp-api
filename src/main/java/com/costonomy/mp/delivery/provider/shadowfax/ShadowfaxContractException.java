package com.costonomy.mp.delivery.provider.shadowfax;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Shadowfax responds with an unparseable body or missing required contract fields.
 */
public class ShadowfaxContractException extends DeliveryProviderException {

    public ShadowfaxContractException(String message) {
        super("SHADOWFAX", message, false);
    }
}
