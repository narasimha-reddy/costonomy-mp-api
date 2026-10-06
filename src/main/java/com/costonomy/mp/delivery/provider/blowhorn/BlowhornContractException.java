package com.costonomy.mp.delivery.provider.blowhorn;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Blowhorn returns a response that violates the expected API contract
 * or when required fields are missing. Non-retryable.
 */
public class BlowhornContractException extends DeliveryProviderException {

    public BlowhornContractException(String message) {
        super("BLOWHORN", message, false);
    }
}
