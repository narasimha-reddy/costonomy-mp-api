package com.costonomy.mp.delivery.provider.delhivery;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Delhivery returns a response that violates the expected API contract
 * or when required fields are missing. Non-retryable.
 */
public class DelhiveryContractException extends DeliveryProviderException {

    public DelhiveryContractException(String message) {
        super("DELHIVERY", message, false);
    }
}
