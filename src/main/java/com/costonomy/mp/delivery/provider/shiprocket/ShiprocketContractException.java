package com.costonomy.mp.delivery.provider.shiprocket;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when Shiprocket returns a response that violates the expected contract schema
 * or when required fields are missing. Non-retryable.
 */
public class ShiprocketContractException extends DeliveryProviderException {

    public ShiprocketContractException(String message) {
        super("SHIPROCKET", message, false);
    }
}
