package com.costonomy.mp.payment.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Completes a mock checkout on the customer's behalf. Doc 19's simulation pattern.
 *
 * <p>A real checkout happens in the provider's own UI, which no mock has. Without
 * this, a local or staging environment can reach the payment step and then stop
 * dead: the order sits in {@code DRAFT}, no supplier ever sees it, and every
 * screen past checkout — acceptance, tracking, receiving — is unreachable by
 * anything except the Java test suite.
 *
 * <p><b>Two gates, like {@code DeliverySimulationService}.</b> The caller must
 * hold {@code PAYMENT_CREATE} on the payment's own outlet — this simulates that
 * user's own action at the provider, so it grants nothing they could not already
 * do — <em>and</em> the configured provider must actually be a mock. The second
 * gate is what makes this safe to ship: against a real provider the endpoint
 * refuses, so it cannot become a way to mark a real payment authorised.
 *
 * <p>It deliberately stops at authorisation and returns the provider's payment
 * id. The caller then goes through the real {@code /payments/{id}/confirm},
 * which is the path production takes — short-circuiting to a confirmed payment
 * here would leave the one step that matters untested by everything but the
 * suite.
 */
@Service
@RequiredArgsConstructor
public class PaymentSimulationService {

    private final PaymentService payments;
    private final PaymentProvider provider;
    private final AccessControlService accessControl;

    /** @return the provider payment id to pass to {@code /payments/{id}/confirm} */
    public String completeCheckout(Long actorId, Long paymentId) {
        var payment = payments.load(paymentId);

        accessControl.requireScoped(actorId, Permissions.PAYMENT_CREATE,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");

        if (!(provider instanceof MockPaymentProvider mock)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This payment isn't running on a mock provider.");
        }

        if (payment.getProviderOrderId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This payment has no checkout to complete.");
        }

        return mock.completeCheckout(payment.getProviderOrderId()).providerPaymentId();
    }
}
