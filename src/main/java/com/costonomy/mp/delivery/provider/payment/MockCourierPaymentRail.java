package com.costonomy.mp.delivery.provider.payment;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An API rail for local runs and tests (D-204), like the other mock providers. Honest by default: it pays and says so.
 * Tests arm it to fail, to time out after taking the money, or to time out without taking it, and read back how many
 * times each key actually moved money.
 */
@Component
public class MockCourierPaymentRail implements CourierPaymentRail {

    public static final String CODE = "MOCK_API";

    public enum Script { PAY, FAIL, LOSE_ANSWER_AFTER_PAYING, TIME_OUT_WITHOUT_PAYING }

    private final Map<String, String> paidKeys = new ConcurrentHashMap<>();
    private final List<PayRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<Script> script = new ArrayDeque<>();
    private final AtomicInteger moneyMoved = new AtomicInteger();
    private volatile Found lookupOverride;
    /** Test-facing: hold the next answer (after any money moved) until released, so a lease can expire mid-call. */
    private volatile java.util.concurrent.CountDownLatch holdRelease;
    private volatile java.util.concurrent.CountDownLatch holdEntered;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean automatic() {
        return true;
    }

    @Override
    public PayResult pay(PayRequest request) {
        var result = payNow(request);
        var release = holdRelease;
        if (release != null) {
            holdRelease = null;
            var entered = holdEntered;
            if (entered != null) {
                entered.countDown();
            }
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private synchronized PayResult payNow(PayRequest request) {
        requests.add(request);
        var already = paidKeys.get(request.idempotencyKey());
        if (already != null) {
            return PayResult.paid(already);       // asked again under the same key: the money moves once
        }
        Script next = script.isEmpty() ? Script.PAY : script.poll();
        switch (next) {
            case FAIL:
                return PayResult.failed("Mock rail declined the payment");
            case TIME_OUT_WITHOUT_PAYING:
                return PayResult.ambiguous("Mock rail timed out");
            case LOSE_ANSWER_AFTER_PAYING:
                moneyMoved.incrementAndGet();
                paidKeys.put(request.idempotencyKey(), "MOCK-" + request.idempotencyKey());
                return PayResult.ambiguous("Mock rail timed out after paying");
            default:
                moneyMoved.incrementAndGet();
                paidKeys.put(request.idempotencyKey(), "MOCK-" + request.idempotencyKey());
                return PayResult.paid("MOCK-" + request.idempotencyKey());
        }
    }

    @Override
    public LookupResult lookup(String idempotencyKey) {
        if (lookupOverride != null) {
            return new LookupResult(lookupOverride, lookupOverride == Found.PAID ? "MOCK-" + idempotencyKey : null);
        }
        var ref = paidKeys.get(idempotencyKey);
        return ref == null ? LookupResult.notFound() : LookupResult.paid(ref);
    }

    // ── test controls ──────────────────────────────────────────────────────

    public synchronized void arm(Script... next) {
        script.addAll(List.of(next));
    }

    /**
     * The next payment is made, then its answer is held: {@code entered} counts down when the call reaches the hold and the
     * call returns once {@code release} does. Test-facing (D-207).
     */
    public void holdNextAnswer(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) {
        this.holdEntered = entered;
        this.holdRelease = release;
    }

    public void lookupAlwaysReturns(Found found) {
        this.lookupOverride = found;
    }

    public synchronized void reset() {
        script.clear();
        paidKeys.clear();
        requests.clear();
        moneyMoved.set(0);
        lookupOverride = null;
        holdRelease = null;
        holdEntered = null;
    }

    /** How many times money actually moved (a repeat under one key moves it once). */
    public int moneyMoved() {
        return moneyMoved.get();
    }

    public List<PayRequest> requests() {
        return List.copyOf(requests);
    }
}
