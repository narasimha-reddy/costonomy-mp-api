package com.costonomy.mp.delivery.provider.xpressbees;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Xpressbees returns a response that violates the expected API contract
 * or when required fields are missing. Non-retryable.
 */
public class XpressbeesContractException extends DeliveryProviderException {

    public XpressbeesContractException(String message) {
        super("XPRESSBEES", message, false);
    }
}
