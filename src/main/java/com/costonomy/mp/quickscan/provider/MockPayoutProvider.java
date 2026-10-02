package com.costonomy.mp.quickscan.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development and test payout provider. D-106.
 *
 * <p>Mandatory rather than convenient, the same as {@code MockPaymentProvider}:
 * local development and CI run entirely on mocks, and this is the only adapter
 * that exists today — a RazorpayX adapter is the natural next one, once a test
 * account exists to build it against.
 *
 * <p>Driven by the <b>amount</b>, so a test asks for a scenario by ordering it:
 *
 * <ul>
 *   <li>amount ending {@code .13} — the payout is refused immediately (FAILED)</li>
 *   <li>amount ending {@code .23} — accepted as PENDING, then PROCESSED the first
 *       time it is fetched</li>
 *   <li>amount ending {@code .31} — accepted as PENDING, then REVERSED the first
 *       time it is fetched (a payout the provider accepted and then pulled back)</li>
 *   <li>anything else — PROCESSED immediately</li>
 * </ul>
 *
 * <p>State is in memory and per-process, right for a mock: a test that wants a
 * payout in a particular state creates it, rather than depending on what an
 * earlier test left behind. The same idempotency key always returns the same
 * payout, whatever state it has reached since — never a second send.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.payout", havingValue = "MOCK", matchIfMissing = true)
public class MockPayoutProvider implements PayoutProvider {

    private static final String FAILED_SUFFIX = "13";
    private static final String PENDING_THEN_PROCESSED_SUFFIX = "23";
    private static final String PENDING_THEN_REVERSED_SUFFIX = "31";

    private final Map<String, String> payoutIdByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, ProviderPayout> payouts = new ConcurrentHashMap<>();
    /** What a PENDING payout resolves to on its first fetch. */
    private final Map<String, PayoutStatus> pendingOutcome = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public ProviderPayout createPayout(String vpa, String payeeName, BigDecimal amount,
                                       String reference, String idempotencyKey) {
        String existingId = payoutIdByIdempotencyKey.get(idempotencyKey);
        if (existingId != null) {
            // Same key: the payout already sent, in whatever state it has
            // reached since — never a second send.
            return payouts.get(existingId);
        }

        String payoutId = "mock_payout_" + UUID.randomUUID().toString().replace("-", "");
        ProviderPayout payout;
        if (endsWith(amount, FAILED_SUFFIX)) {
            payout = new ProviderPayout(payoutId, PayoutStatus.FAILED,
                    "PAYOUT_DECLINED", "The payout was declined by the provider.");
        } else if (endsWith(amount, PENDING_THEN_PROCESSED_SUFFIX)) {
            payout = new ProviderPayout(payoutId, PayoutStatus.PENDING, null, null);
            pendingOutcome.put(payoutId, PayoutStatus.PROCESSED);
        } else if (endsWith(amount, PENDING_THEN_REVERSED_SUFFIX)) {
            payout = new ProviderPayout(payoutId, PayoutStatus.PENDING, null, null);
            pendingOutcome.put(payoutId, PayoutStatus.REVERSED);
        } else {
            payout = new ProviderPayout(payoutId, PayoutStatus.PROCESSED, null, null);
        }

        payouts.put(payoutId, payout);
        payoutIdByIdempotencyKey.put(idempotencyKey, payoutId);
        return payout;
    }

    @Override
    public ProviderPayout fetchPayout(String providerPayoutId) {
        var current = payouts.get(providerPayoutId);
        if (current == null) {
            throw new PayoutProviderException("Unknown mock payout " + providerPayoutId, false, "NOT_FOUND");
        }
        if (current.status() != PayoutStatus.PENDING) {
            return current;
        }

        PayoutStatus outcome = pendingOutcome.get(providerPayoutId);
        var resolved = outcome == PayoutStatus.REVERSED
                ? new ProviderPayout(providerPayoutId, PayoutStatus.REVERSED,
                        "PAYOUT_REVERSED", "The payout was reversed after being accepted.")
                : new ProviderPayout(providerPayoutId, PayoutStatus.PROCESSED, null, null);
        payouts.put(providerPayoutId, resolved);
        return resolved;
    }

    /** Whether the amount's paise match a scenario trigger. */
    private static boolean endsWith(BigDecimal amount, String suffix) {
        if (amount == null) {
            return false;
        }
        String paise = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return paise.endsWith("." + suffix);
    }
}
