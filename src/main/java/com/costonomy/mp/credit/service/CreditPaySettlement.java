package com.costonomy.mp.credit.service;

import com.costonomy.mp.payment.provider.PaymentProvider;
import org.springframework.stereotype.Service;

/**
 * The one place a captured credit payment is applied (D-196). Shared by verify (source CONFIRM), the webhook
 * (WEBHOOK) and the poller (POLLER), so the same capture arriving by any path, in any order, concurrently, is applied
 * once. Not transactional itself: it checks the payment outside any transaction and applies it in one short one.
 */
@Service
public class CreditPaySettlement {

    /** What {@link #settle} did with the payment it was given. */
    public enum Outcome { APPLIED, ALREADY_APPLIED, PROCESSING, DECLINED, REJECTED, DUPLICATE_REFUNDING }

    public Outcome settle(Long attemptId, PaymentProvider.ProviderPayment payment, String source) {
        throw new UnsupportedOperationException("A0 seed: implemented in step 2");
    }
}
