package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.provider.DeliveryProviderException;

/**
 * Borzo answered, but not with the shape this client was built against.
 *
 * <p>A missing required field, an {@code is_successful: true} response that
 * still carries {@code parameter_warnings} (Borzo does this — confirmed
 * against the sandbox: a point with no {@code address} comes back
 * {@code is_successful: true} with an empty {@code points} array and the real
 * failure buried in {@code parameter_warnings}), or a field whose value is
 * outside what was ever observed live.
 *
 * <p>Never defaulted past. The Pidge integration's
 * {@code new BigDecimal(body.path("total_fare").asText("50.00"))} pattern —
 * silently substituting a plausible fake value for a missing field — is
 * exactly what this type exists to avoid repeating.
 */
public class BorzoContractException extends DeliveryProviderException {

    public BorzoContractException(String message) {
        super("BORZO", message, false);
    }
}
