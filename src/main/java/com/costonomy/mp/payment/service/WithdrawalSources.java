package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * What each source payment of a withdrawal can really give back, asked of the provider before any
 * money leaves the wallet (D-110).
 *
 * <p><b>The check is advisory; the reversal is what makes it safe.</b> The provider can change its
 * answer between this read and the send, so nothing rests on it being right. It exists so that the
 * ordinary cases (a payment the provider does not know, one refunded by hand, one part-refunded)
 * are refused up front, with the amount that can go back, instead of debiting the wallet and
 * reversing it minutes later. It can only ever <em>under</em>-estimate: an over-estimate is caught
 * by the provider's own refusal and put back by {@link WithdrawalReversalService}.
 *
 * <p>Three phases, and the provider is never called with a connection held (D-099):
 * <ol>
 *   <li>{@link #candidates}: read the outlet's sources, no lock;</li>
 *   <li>{@link #inspect}: ask the provider about them in order (oldest first, see {@link #inAskingOrder}), until enough is covered, ten sources or
 *       twenty seconds. A source it says can never be refunded is blocked at once;</li>
 *   <li>{@link #allowed}: under the wallet lock, recompute from the database and cap each checked
 *       source by what the provider said is left less what we may have sent since.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalSources {

    private final RefundService refunds;
    private final RefundRepository refundRepository;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;
    private final AlertThrottle alerts;

    /** Sources asked about per withdrawal. */
    static final int MAX_SOURCES = 10;

    /** Longest the provider is asked, in all, for one withdrawal. A field, so a test can spend it at once. */
    Duration budget = Duration.ofSeconds(20);

    /**
     * A payment older than this is not asked to take a refund (D-110): the provider's window is
     * unverified (V-6), so the default is deliberately not the longest it could be. Not persisted: a
     * later, longer window would simply lift it.
     */
    @Value("${costonomy.mp.razorpay.refund-window-days:180}")
    int refundWindowDays = 180;

    /**
     * Local testing only (D-110): switch the check off to watch a debit be reversed. Refused
     * under a production profile at start-up by ProductionProviderGuard.
     */
    @Value("${costonomy.mp.wallet.withdraw-precheck:true}")
    boolean precheckEnabled = true;

    /** How often "the provider does not know two payments in a row" is repeated while it lasts. */
    static final Duration CONFIGURATION_ALERT_EVERY = Duration.ofMinutes(15);

    /** How long refunds refused for a reason on our side keep withdrawals paused. */
    static final Duration BREAKER_WINDOW = Duration.ofMinutes(30);

    /** What one read of one source found. */
    public enum Verdict {
        /** Can take a refund, up to {@code capacity}. */
        OK,
        /** Will never take one; recorded on the payment. */
        BLOCKED,
        /** Could not be read, or is past the refund window: not used now, not blocked. */
        UNAVAILABLE,
        /** The provider does not know it: held back inside {@link #inspect} until it is clear whether that is the payment or our keys. Never returned. */
        UNKNOWN
    }

    /**
     * @param available what the wallet's ledger says can go back from this source
     * @param capacity  what the provider says is left to refund, for OK
     * @param fetchedAt when the provider was read, taken before the read
     */
    public record Checked(Long paymentId, BigDecimal available, Verdict verdict, BigDecimal capacity,
                          Instant fetchedAt, String reason) {
    }

    /** What a withdrawal may take, by source, and what it cannot. */
    /**
     * @param checkedSources   sources of the outlet that were asked about in this request (their money is in
     *                         {@code withdrawableNow}, {@code blocked} or {@code unavailable} for a reason of their own)
     * @param uncheckedSources sources not asked about because enough was covered, or ten sources or twenty seconds were
     *                         used up first: their money is in {@code unavailable}, and more may be withdrawable in a
     *                         further step. Sources already known to be blocked are in neither count
     */
    public record Plan(List<Part> parts, BigDecimal withdrawableNow, BigDecimal blocked, BigDecimal unavailable,
                       int checkedSources, int uncheckedSources) {

        /** A plan that does not count sources. */
        public Plan(List<Part> parts, BigDecimal withdrawableNow, BigDecimal blocked, BigDecimal unavailable) {
            this(parts, withdrawableNow, blocked, unavailable, 0, 0);
        }
    }

    public record Part(Long paymentId, BigDecimal allowed) {
    }

    /**
     * Refuse new withdrawals while refunds are failing for a reason on our side (the circuit breaker), and,
     * for this outlet, while a refund of its that was put back has turned up at the provider (a double credit
     * waiting for a person). Spending stays allowed; nothing has been asked of the provider and nothing debited.
     */
    public void requireOpen(Long outletId) {
        if (refundRepository.countLateSuccessOpen(outletId) > 0) {
            log.warn("Withdrawals of outlet {} paused: a refund put back in its wallet was also sent by the provider", outletId);
            throw new BusinessException(ErrorCode.WITHDRAWALS_PAUSED,
                    "Withdrawals are paused for this restaurant while we check a refund. "
                            + "Your money is safe in your wallet and can be spent on orders.");
        }
        long failing = refundRepository.countFailuresSince(
                EnumSet.of(ProviderFailureKind.INSUFFICIENT_BALANCE, ProviderFailureKind.CONFIG),
                Instant.now().minus(BREAKER_WINDOW));
        if (failing > 0) {
            log.warn("Withdrawals paused: {} refund(s) refused in the last {} for a reason on our side "
                    + "(the provider account's balance or credentials)", failing, BREAKER_WINDOW);
            throw new BusinessException(ErrorCode.WITHDRAWALS_PAUSED);
        }
    }

    /** Phase 1: the outlet's sources, oldest credit first, blocked ones marked. */
    public List<RefundService.Withdrawable> candidates(Long outletId) {
        return refunds.withdrawable(outletId);
    }

    /** Phase 2: ask the provider. No transaction, no lock. */
    public List<Checked> inspect(List<RefundService.Withdrawable> candidates, BigDecimal amount) {
        var checked = new ArrayList<Checked>();
        BigDecimal covered = BigDecimal.ZERO;
        Instant started = Instant.now();
        int asked = 0;
        // Sources the provider said it does not know, held back until it is clear whether that is about the
        // payment or about our keys: two different payments in a row that it has never heard of, and none
        // answered normally, is a configuration fault (another account's keys or mode), not two dead
        // payments. Blocking them then would take every restaurant's refund money out of reach (D-110).
        var unknown = new ArrayList<Checked>();
        boolean keysWork = false;
        for (var source : inAskingOrder(candidates, amount)) {
            if (source.blocked()) {
                continue;
            }
            if (!precheckEnabled) {
                // No capacity known: the send is the check, and a refusal is put back.
                checked.add(new Checked(source.paymentId(), source.available(), Verdict.OK,
                        source.available(), started, null));
                continue;
            }
            if (covered.compareTo(amount) >= 0 || asked >= MAX_SOURCES
                    || Duration.between(started, Instant.now()).compareTo(budget) > 0) {
                break;
            }
            asked++;
            var result = check(source);
            if (result.verdict() == Verdict.UNKNOWN) {
                if (keysWork) {
                    checked.add(blocked(result));
                } else {
                    unknown.add(result);
                }
                // On to the next source, whatever this one was: it is what says whether the payment is gone or
                // the keys are wrong. Stopping here would lock an outlet out for good when its two oldest
                // sources are dead payments, with healthy ones behind them never asked.
                continue;
            }
            if (answered(result)) {
                keysWork = true;
                unknown.forEach(u -> checked.add(blocked(u)));
                unknown.clear();
            }
            checked.add(result);
            if (result.verdict() == Verdict.OK) {
                // The same figure the plan will use (see allowed), so that what a refusal offers is what asking
                // for it will get: stopping on the ledger's figure alone would leave the sources after this one
                // unread and report them unavailable, and the offered amount would be refused in its turn.
                covered = covered.add(source.available().min(result.capacity().subtract(unconfirmed(result)).max(BigDecimal.ZERO)));
            }
        }
        if (unknown.size() >= 2) {
            // Two different payments in a row that it has never heard of, and none answered normally: a
            // configuration fault (another account's keys or mode), not two dead payments.
            if (alerts.due("withdrawal-unknown-config", Instant.now(), CONFIGURATION_ALERT_EVERY)) {
                log.error("Configuration fault: the provider does not know {} payments ({} and {}) and answered about "
                        + "no other when checking a withdrawal: check the API keys, mode and base URL. "
                        + "Nothing is blocked and nothing is sent", unknown.size(),
                        unknown.get(0).paymentId(), unknown.get(1).paymentId());
            }
            unknown.forEach(u -> checked.add(unavailable(u, "PROVIDER_UNREACHABLE")));
        } else {
            // One unknown payment and nothing else said otherwise: a payment that is gone.
            unknown.forEach(u -> checked.add(blocked(u)));
        }
        return checked;
    }

    /**
     * The order the provider is asked in: <b>oldest credit first, exactly as the ledger lists them</b> (D-110: a withdrawal
     * uses the oldest credit while it can still go back, so that small old credits do not age past the refund window).
     * Which sources a fully checked request draws from never depends on anything else.
     *
     * <p>The one exception is a request the ten reads and twenty seconds could not satisfy in that order: when the
     * ledger says the oldest {@value #MAX_SOURCES} sources that are not blocked hold less than the amount, asking them
     * can never cover it, however the provider answers, so the sources that can give back the most are asked first
     * instead (ties keep the ledger's order) and the request has a chance. That only changes which sources are
     * <em>asked</em> in a request that would otherwise have been refused; the plan is still worked out afterwards from the
     * database, under the wallet's lock, in the ledger's order, from the sources that were checked.
     */
    static List<RefundService.Withdrawable> inAskingOrder(List<RefundService.Withdrawable> candidates, BigDecimal amount) {
        var open = candidates.stream().filter(c -> !c.blocked()).toList();
        if (open.size() <= MAX_SOURCES) {
            return candidates;
        }
        BigDecimal oldestTen = BigDecimal.ZERO;
        for (var source : open.subList(0, MAX_SOURCES)) {
            oldestTen = oldestTen.add(source.available());
        }
        if (oldestTen.compareTo(amount) >= 0) {
            return candidates;
        }
        var ordered = new ArrayList<>(candidates);
        ordered.sort(java.util.Comparator.comparing(RefundService.Withdrawable::available).reversed());
        return ordered;
    }

    /** Whether the provider answered about a payment, as opposed to being unreachable: proof that our keys are the right ones. */
    private static boolean answered(Checked result) {
        return result.verdict() != Verdict.UNAVAILABLE
                || "MISMATCH".equals(result.reason()) || "WINDOW_PASSED".equals(result.reason());
    }

    /** Refunds of ours the provider's {@code amount_refunded}, read for this source, may not yet count. */
    private BigDecimal unconfirmed(Checked result) {
        return refundRepository.unconfirmedOn(result.paymentId(), result.fetchedAt().minusSeconds(5));
    }

    /** A source the provider does not know, once it is known that it is the payment and not our keys: blocked for good. */
    private Checked blocked(Checked unknown) {
        block(unknown.paymentId(), "PAYMENT_UNKNOWN");
        return new Checked(unknown.paymentId(), unknown.available(), Verdict.BLOCKED, null, unknown.fetchedAt(),
                "PAYMENT_UNKNOWN");
    }

    private static Checked unavailable(Checked was, String reason) {
        return new Checked(was.paymentId(), was.available(), Verdict.UNAVAILABLE, null, was.fetchedAt(), reason);
    }

    private Checked check(RefundService.Withdrawable source) {
        Instant fetchedAt = Instant.now();
        var payment = payments.findById(source.paymentId()).orElse(null);
        if (payment == null || payment.getProviderPaymentId() == null) {
            return unavailable(source, fetchedAt, "no provider payment");
        }
        PaymentProvider.ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            if (ex.isNotFound()) {
                return new Checked(source.paymentId(), source.available(), Verdict.UNKNOWN, null, fetchedAt,
                        "PAYMENT_UNKNOWN");
            }
            if (ex.isCredentialsRefused()) {
                log.error("Razorpay refused our credentials checking payment {} for a withdrawal", payment.getId());
            } else {
                log.warn("Could not check payment {} for a withdrawal: {} {}", payment.getId(), ex.providerCode(),
                        ex.getMessage());
            }
            return unavailable(source, fetchedAt, "PROVIDER_UNREACHABLE");
        }
        if (facts.providerOrderId() != null && payment.getProviderOrderId() != null
                && !facts.providerOrderId().equals(payment.getProviderOrderId())) {
            // Our books and the provider's name different orders for one payment id. Not used, not blocked: a person must look.
            log.error("Payment {} is provider payment {} in our books, but the provider says it belongs to order {} "
                    + "and not {}; not used for a withdrawal", payment.getId(), payment.getProviderPaymentId(),
                    facts.providerOrderId(), payment.getProviderOrderId());
            return unavailable(source, fetchedAt, "MISMATCH");
        }
        boolean taken = facts.captured() && (facts.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED
                || facts.status() == PaymentProvider.ProviderPaymentStatus.REFUNDED);
        if (!taken) {
            // Our books say captured and wallet money came from it; the provider says the money was never taken.
            log.error("Payment {} is CAPTURED in our books but the provider reports {} (captured={}): our records "
                    + "and the provider's disagree", payment.getId(), facts.status(), facts.captured());
            block(source.paymentId(), "NOT_CAPTURED");
            return new Checked(source.paymentId(), source.available(), Verdict.BLOCKED, null, fetchedAt, "NOT_CAPTURED");
        }
        BigDecimal refunded = facts.amountRefunded() == null ? BigDecimal.ZERO : facts.amountRefunded();
        BigDecimal capacity = facts.amount().subtract(refunded);
        if (capacity.signum() <= 0) {
            block(source.paymentId(), "REFUNDED_ELSEWHERE");
            return new Checked(source.paymentId(), source.available(), Verdict.BLOCKED, null, fetchedAt,
                    "REFUNDED_ELSEWHERE");
        }
        if (facts.createdAt() != null
                && facts.createdAt().isBefore(fetchedAt.minus(Duration.ofDays(refundWindowDays)))) {
            return unavailable(source, fetchedAt, "WINDOW_PASSED");
        }
        return new Checked(source.paymentId(), source.available(), Verdict.OK, capacity, fetchedAt, null);
    }

    private static Checked unavailable(RefundService.Withdrawable source, Instant fetchedAt, String reason) {
        return new Checked(source.paymentId(), source.available(), Verdict.UNAVAILABLE, null, fetchedAt, reason);
    }

    /** Record that a source will never take a refund, in its own short transaction. */
    private void block(Long paymentId, String reason) {
        txTemplate.executeWithoutResult(status -> {
            if (payments.block(paymentId, Instant.now(), reason) == 1) {
                auditService.record(null, null, "PAYMENT_REFUND_BLOCKED", "PAYMENT", paymentId, null, reason,
                        "Checked before a withdrawal; no more withdrawals are drawn from this payment", "SYSTEM");
                log.warn("Payment {} can no longer be refunded at the provider: {}", paymentId, reason);
            }
        });
    }

    /**
     * Phase 3: what the sources allow now. Call with the wallet held and the sources read
     * again ({@code fresh}); {@code walletBalance} is the balance under that lock.
     *
     * <p>A source counts only if it was checked in phase 2, so money credited since is not used
     * this time. For each: what the ledger says can go back, capped by what the provider said was
     * left, less the refunds of ours it may not have counted yet.
     */
    public Plan allowed(List<RefundService.Withdrawable> fresh, List<Checked> checked, BigDecimal walletBalance) {
        var byPayment = new java.util.HashMap<Long, Checked>();
        checked.forEach(c -> byPayment.put(c.paymentId(), c));
        var parts = new ArrayList<Part>();
        BigDecimal usable = BigDecimal.ZERO;
        BigDecimal blocked = BigDecimal.ZERO;
        BigDecimal unavailable = BigDecimal.ZERO;
        int checkedSources = 0;
        int uncheckedSources = 0;
        for (var source : fresh) {
            var c = byPayment.get(source.paymentId());
            if (!source.blocked()) {
                if (c == null) {
                    uncheckedSources++;
                } else {
                    checkedSources++;
                }
            }
            if (source.blocked() || (c != null && c.verdict() == Verdict.BLOCKED)) {
                blocked = blocked.add(source.available());
                continue;
            }
            if (c == null || c.verdict() != Verdict.OK) {
                unavailable = unavailable.add(source.available());
                continue;
            }
            BigDecimal unconfirmed = refundRepository.unconfirmedOn(source.paymentId(),
                    c.fetchedAt().minusSeconds(5));
            BigDecimal allowed = source.available().min(c.capacity().subtract(unconfirmed)).max(BigDecimal.ZERO);
            if (allowed.signum() > 0) {
                parts.add(new Part(source.paymentId(), allowed));
                usable = usable.add(allowed);
            }
            // What the ledger could give but the provider will not take now (part-refunded elsewhere, or
            // refunds of ours still to be counted) is money that stays in the wallet, and is neither
            // blocked nor unchecked: the difference between the request and withdrawableNow says it.
        }
        return new Plan(parts, usable.min(walletBalance), blocked, unavailable, checkedSources, uncheckedSources);
    }

    /** The response details of a refusal, and its words (D-110). */
    public static BusinessException exceeded(BigDecimal requested, Plan plan) {
        String now = com.costonomy.mp.common.text.Rupees.of(plan.withdrawableNow());
        String message;
        String reason;
        if (plan.withdrawableNow().signum() == 0) {
            reason = plan.blocked().signum() > 0 ? "SOURCE_BLOCKED"
                    : plan.unavailable().signum() > 0 ? "PROVIDER_UNREACHABLE" : "NO_REFUND_MONEY";
            message = "Nothing can go back to your card or bank right now. "
                    + (plan.blocked().signum() > 0
                    ? "The original payment can no longer be refunded; that money stays in your wallet for orders."
                    : plan.unavailable().signum() > 0
                    ? "We couldn't check with the payment provider just now. Try again in a few minutes."
                    : "This balance has no card or bank behind it; it can be spent on orders.");
        } else if (plan.blocked().signum() > 0) {
            reason = "SOURCE_BLOCKED";
            message = "₹%s can go back to your card or bank now. ₹%s can't: its original payment can no longer be "
                    .formatted(now, com.costonomy.mp.common.text.Rupees.of(plan.blocked()))
                    + "refunded. That money stays in your wallet for orders.";
        } else if (plan.unavailable().signum() > 0) {
            reason = "PROVIDER_UNREACHABLE";
            message = "₹%s can go back to your card or bank now. ₹%s couldn't be checked with the payment provider "
                    .formatted(now, com.costonomy.mp.common.text.Rupees.of(plan.unavailable()))
                    + "just now. Try again in a few minutes.";
        } else {
            reason = "NO_REFUND_MONEY";
            message = "₹%s can go back to your card or bank. The rest of your balance can be spent on orders."
                    .formatted(now);
        }
        if (plan.uncheckedSources() > 0) {
            // Not every source was asked about (enough covered, ten sources or twenty seconds used): the figures are
            // what was found, and there may be more.
            message += " We checked " + plan.checkedSources() + " of your " + (plan.checkedSources() + plan.uncheckedSources())
                    + " payments this time, so more may be withdrawable in a further step.";
        }
        return new BusinessException(ErrorCode.WITHDRAWAL_EXCEEDS_REFUNDABLE, message, Map.of(
                "requested", requested,
                "withdrawableNow", plan.withdrawableNow(),
                "blocked", plan.blocked(),
                "unavailable", plan.unavailable(),
                "reason", reason,
                "checkedSources", plan.checkedSources(),
                "uncheckedSources", plan.uncheckedSources()));
    }
}
