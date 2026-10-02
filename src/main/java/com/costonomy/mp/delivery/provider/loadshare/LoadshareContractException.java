package com.costonomy.mp.delivery.provider.loadshare;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Thrown when LoadShare returns a response that violates the expected Hyperlocal v2 contract
 * or when required fields are missing. Non-retryable.
 */
public class LoadshareContractException extends DeliveryProviderException {

    public LoadshareContractException(String message) {
        super("LOADSHARE", message, false);
    }
}
