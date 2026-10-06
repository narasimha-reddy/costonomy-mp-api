package com.costonomy.mp.delivery.provider.porter;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Porter responds with an unparseable body or missing required contract fields.
 */
public class PorterContractException extends DeliveryProviderException {

    public PorterContractException(String message) {
        super("PORTER", message, false);
    }
}
