package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.wallet.domain.WalletTopUp;
import com.costonomy.mp.wallet.domain.WalletTopUpStatus;
import com.costonomy.mp.wallet.repository.WalletTopUpRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Adding money to a wallet through Razorpay (D-107).
 *
 * <p><b>Where the money is.</b> A restaurant pays Razorpay; Razorpay captures it
 * into our balance and settles it to our bank. The wallet is a ledger in our
 * database saying how much of that we owe the restaurant. This class is the only
 * thing that turns "Razorpay captured N" into "the ledger says we owe N", and it
 * must do that exactly once for every captured payment and never for one that was
 * not captured.
 *
 * <p><b>One method credits, and everything goes through it.</b> The client's
 * confirm call and the background poller both end in {@link #settle}, so there is
 * one place the credit is decided and no way for the two to disagree. Inside it,
 * the wallet is locked, the top-up row is locked, and the row is moved out of
 * CREATED by a conditional update in the same transaction as the ledger credit:
 * of any number of concurrent callers exactly one gets past that update, and the
 * credit and the state change commit together or not at all. Two unique
 * constraints (one Razorpay payment id per top-up, one ledger reference per
 * top-up) stand behind it.
 *
 * <p><b>Nothing captured is ever left without a credit or a refund.</b> If the
 * confirm never arrives, the poller finds the payment at Razorpay. If crediting
 * would break a wallet limit — two top-ups racing past it, say — the payment is
 * returned to its source instead, by a refund that is retried until it lands.
 *
 * <p><b>No Razorpay call holds a database connection</b> (D-099): every provider
 * call is made with no transaction open, and each write is a short one after it.
 * The transactions here run at READ COMMITTED: under the default REPEATABLE READ,
 * a transaction that waited for the wallet lock would still read the month's
 * total as it stood before the wait, and two top-ups could each pass a limit
 * that together they break.
 */
@Service
@Slf4j
public class WalletTopUpService {

    /** How old a top-up is before the poller looks: the client's own confirm gets the first go. */
    static final Duration POLL_AFTER = Duration.ofMinutes(1);

    /** How long an unpaid top-up is watched before it is expired (after asking Razorpay once more). */
    static final Duration EXPIRE_AFTER = Duration.ofDays(1);

    /** A row with no Razorpay order this old never reached the provider: nothing can be paid against it. */
    static final Duration NEVER_OPENED_AFTER = Duration.ofMinutes(5);

    /** A top-up this young is asked about every minute; an older one every half hour. */
    static final Duration YOUNG_FOR = Duration.ofHours(1);
    static final Duration YOUNG_RECHECK = Duration.ofMinutes(1);
    static final Duration OLD_RECHECK = Duration.ofMinutes(30);

    /** How often a refund still to send or confirm is tried again. */
    static final Duration REFUND_RECHECK = Duration.ofMinutes(1);

    /** Refund sends before it goes to a person (D-101's shape). */
    static final int MAX_REFUND_ATTEMPTS = 5;

    /** What a settlement attempt came to. */
    public enum Settlement {
        /** The wallet has been credited, by this call or an earlier one. */
        CREDITED,
        /** Razorpay has the payment but has not captured it yet. Nothing to do but wait. */
        PROCESSING,
        /** That payment attempt failed. The customer may try again on the same order. */
        DECLINED,
        /** Captured, could not be credited, and is being returned; not confirmed yet. */
        REFUNDING,
        /** Captured, could not be credited, and has been returned. */
        REFUNDED,
        /** That payment is not this top-up's, or not for its amount. Nothing was credited. */
        REJECTED
    }

    /** What a top-up needs to open Razorpay's checkout. */
    public record Intent(Long topUpId, String razorpayOrderId, String keyId,
                         BigDecimal amount, String currency) {
    }

    /** The wallet limits as they stand for one outlet right now. */
    public record LimitsView(BigDecimal maxBalance, BigDecimal monthlyTopUpLimit,
                             BigDecimal addedThisMonth, BigDecimal remainingThisMonth,
                             BigDecimal minTopUp, BigDecimal maxTopUp) {
    }

    private final WalletTopUpRepository topUps;
    private final WalletService wallets;
    private final WalletLimits limits;
    private final PaymentProvider provider;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public WalletTopUpService(WalletTopUpRepository topUps, WalletService wallets, WalletLimits limits,
                              PaymentProvider provider, AuditService audit,
                              PlatformTransactionManager transactionManager) {
        this.topUps = topUps;
        this.wallets = wallets;
        this.limits = limits;
        this.provider = provider;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ── Limits ───────────────────────────────────────────────────────────

    /** This outlet's limits and how much of the month's is used. */
    public LimitsView limitsFor(Long outletId) {
        var now = Instant.now();
        var added = topUps.sumCredited(outletId, WalletTopUpStatus.CREDITED,
                limits.monthStart(now), limits.monthEnd(now));
        return new LimitsView(limits.maxBalance(), limits.monthlyTopUpLimit(), added,
                limits.monthlyTopUpLimit().subtract(added).max(BigDecimal.ZERO),
                limits.minTopUp(), limits.maxTopUp());
    }

    // ── Creating ─────────────────────────────────────────────────────────

    /**
     * Start a top-up: check the amount and the limits, record it, and open a
     * Razorpay order for exactly that amount, captured on payment.
     *
     * <p>The row is written <em>before</em> Razorpay is asked, so an order at
     * Razorpay always has a row to belong to. If Razorpay refuses, the row is
     * marked FAILED and nothing is payable; if the process dies in between, the
     * row has no order id, no client ever received one, and the poller closes it.
     */
    public Intent create(Long actorId, Long outletId, BigDecimal requested, String clientKey) {
        var amount = validAmount(requested);
        String key = "outlet-" + outletId + "-" + clientKey;

        var existing = topUps.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), amount);
        }
        checkLimits(outletId, amount);

        // The wallet exists before any credit path has to lock it, so the lock
        // never has to create the row it is about to hold.
        wallets.forOutlet(outletId);

        WalletTopUp row;
        try {
            row = tx.execute(status -> {
                var created = new WalletTopUp();
                created.setOutletId(outletId);
                created.setCreatedBy(actorId);
                created.setAmount(amount);
                created.setStatus(WalletTopUpStatus.CREATED);
                created.setIdempotencyKey(key);
                return topUps.saveAndFlush(created);
            });
        } catch (DataIntegrityViolationException raced) {
            // The same key, arriving twice at once (or after the idempotency
            // record expired). The unique key decided; answer with the winner.
            return replay(topUps.findByIdempotencyKey(key).orElseThrow(() -> raced), amount);
        }

        try (var trace = TraceScope.of("wallet_top_up", row.getId(), "outlet", outletId)) {
            PaymentProvider.AuthorizationIntent intent;
            try {
                intent = provider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                        // Both derived from the row: a retry of this call reaches the
                        // same intent on the provider's side (D-107). The receipt is
                        // Razorpay's own 40-character reference.
                        "topup-" + row.getId(), amount, "INR", "Mandi wallet top-up",
                        "topup-" + row.getId(), true));
            } catch (RuntimeException ex) {
                // Nothing was payable: the client never received an order id.
                tx.executeWithoutResult(status -> topUps.endFromCreated(row.getId(),
                        WalletTopUpStatus.FAILED, truncate("Could not open a Razorpay order: " + ex.getMessage())));
                log.warn("Wallet top-up {} FAILED: could not open a Razorpay order: {}",
                        row.getId(), ex.getMessage());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "We couldn't start the top-up. Nothing was charged. Please try again.");
            }

            tx.executeWithoutResult(status -> topUps.attachOrder(row.getId(), intent.providerOrderId()));
            log.info("Wallet top-up {} created for {} with Razorpay order {}",
                    row.getId(), amount.toPlainString(), intent.providerOrderId());
            return new Intent(row.getId(), intent.providerOrderId(), intent.publicKey(), amount, "INR");
        }
    }

    /** The same key again: the same top-up if it is the same request and still payable. */
    private Intent replay(WalletTopUp existing, BigDecimal amount) {
        if (existing.getAmount().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }
        if (existing.getStatus() != WalletTopUpStatus.CREATED || existing.getRazorpayOrderId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "That top-up has already ended. Start a new one.");
        }
        return new Intent(existing.getId(), existing.getRazorpayOrderId(), provider.createAuthorizationPublicKey(),
                amount, "INR");
    }

    private BigDecimal validAmount(BigDecimal requested) {
        if (requested == null || requested.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Enter an amount greater than zero.");
        }
        // Paise at most. Razorpay takes whole paise; rounding a third decimal
        // here would charge an amount the restaurant did not type.
        if (requested.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount in rupees and paise, like 500 or 500.50.");
        }
        var amount = requested.setScale(2);
        if (amount.compareTo(limits.minTopUp()) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The smallest top-up is ₹%s.".formatted(Rupees.of(limits.minTopUp())));
        }
        if (amount.compareTo(limits.maxTopUp()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The largest single top-up is ₹%s.".formatted(Rupees.of(limits.maxTopUp())));
        }
        return amount;
    }

    /**
     * The limits, as a refusal before anyone pays. Read without a lock: this is
     * a courtesy so nobody is sent to pay for something we would refuse. The check
     * that counts is the one at credit time, under the wallet lock.
     */
    private void checkLimits(Long outletId, BigDecimal amount) {
        var breach = breachOf(wallets.balanceOf(outletId), limitsFor(outletId).addedThisMonth(), amount);
        if (breach != null) {
            throw new BusinessException(ErrorCode.WALLET_LIMIT_EXCEEDED, breach);
        }
    }

    /** Why adding {@code amount} is not allowed, or null if it is. */
    private String breachOf(BigDecimal balance, BigDecimal addedThisMonth, BigDecimal amount) {
        if (balance.add(amount).compareTo(limits.maxBalance()) > 0) {
            return "Your wallet can hold at most ₹%s. You can add up to ₹%s right now."
                    .formatted(Rupees.of(limits.maxBalance()),
                            Rupees.of(limits.maxBalance().subtract(balance).max(BigDecimal.ZERO)));
        }
        if (addedThisMonth.add(amount).compareTo(limits.monthlyTopUpLimit()) > 0) {
            return "You can add ₹%s more to your wallet this month."
                    .formatted(Rupees.of(limits.monthlyTopUpLimit().subtract(addedThisMonth).max(BigDecimal.ZERO)));
        }
        return null;
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /** A top-up of this outlet. Another outlet's is not found, as never-existing would be. */
    public WalletTopUp get(Long outletId, Long topUpId) {
        return topUps.findById(topUpId)
                .filter(topUp -> topUp.getOutletId().equals(outletId))
                .orElseThrow(() -> new NotFoundException("WalletTopUp", topUpId));
    }

    // ── Confirming ───────────────────────────────────────────────────────

    /**
     * The client says it has paid. Verify that, then credit.
     *
     * <p>The client supplies a payment id and a signature and is trusted with
     * neither: the signature only shows the id came out of a checkout for this
     * order, and what the payment is — whose, for how much, captured or not — is
     * whatever Razorpay says when asked.
     *
     * @throws BusinessException TOP_UP_PROCESSING if the payment is not captured
     *         yet (nothing is lost: the poller credits it when it is); anything
     *         else says why nothing was credited
     */
    public void confirm(Long outletId, Long topUpId, String paymentId, String signature) {
        var topUp = get(outletId, topUpId);
        try (var trace = TraceScope.of("wallet_top_up", topUpId, "outlet", outletId,
                "rzp_order", topUp.getRazorpayOrderId(), "rzp_payment", paymentId)) {
            log.info("Wallet top-up {} confirm requested", topUpId);

            if (topUp.getRazorpayOrderId() == null) {
                throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "That top-up was never opened for payment.");
            }
            if (!provider.verifyCheckoutSignature(topUp.getRazorpayOrderId(), paymentId, signature)) {
                audit.record(null, null, "WALLET_TOP_UP_CONFIRM_REJECTED", "WALLET_TOP_UP", topUpId,
                        topUp.getStatus().name(), topUp.getStatus().name(),
                        "Checkout signature did not verify for payment " + paymentId, "CLIENT");
                log.warn("Wallet top-up {} confirm rejected: the checkout signature did not verify", topUpId);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "We couldn't verify that payment. Nothing was added to your wallet.");
            }

            switch (topUp.getStatus()) {
                case CREDITED -> {
                    return;
                }
                case REFUND_PENDING, REFUNDED -> throw refundedConflict(topUp.getStatus());
                case FAILED -> throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "That top-up has ended. Start a new one.");
                default -> { }
            }

            PaymentProvider.ProviderPayment payment;
            try {
                payment = provider.fetchPayment(paymentId);
            } catch (PaymentProviderException ex) {
                if (ex.isRetryable()) {
                    // We could not ask. The money may well be captured; the poller
                    // finds it by the order, so the restaurant loses nothing.
                    throw new BusinessException(ErrorCode.TOP_UP_PROCESSING);
                }
                log.warn("Wallet top-up {} confirm named a payment Razorpay refused: {}", topUpId, ex.getMessage());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "We couldn't find that payment. Nothing was added to your wallet.");
            }

            switch (settle(topUp, payment, "CONFIRM")) {
                case CREDITED -> { }
                case PROCESSING -> throw new BusinessException(ErrorCode.TOP_UP_PROCESSING);
                case DECLINED -> throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "That payment didn't go through. Nothing was charged.");
                case REFUNDING -> throw refundedConflict(WalletTopUpStatus.REFUND_PENDING);
                case REFUNDED -> throw refundedConflict(WalletTopUpStatus.REFUNDED);
                case REJECTED -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That payment doesn't match this top-up. Nothing was added to your wallet.");
            }
        }
    }

    private static BusinessException refundedConflict(WalletTopUpStatus status) {
        return new BusinessException(ErrorCode.WALLET_LIMIT_EXCEEDED,
                status == WalletTopUpStatus.REFUNDED
                        ? "That would have taken your wallet over its limit, so the payment was returned to where it came from."
                        : "That would take your wallet over its limit, so the payment is being returned to where it came from.");
    }

    // ── Settling: the one place a top-up is credited ─────────────────────

    /**
     * Turn a payment Razorpay reports into a credit, a wait, or a return.
     *
     * <p>Shared by {@link #confirm} and {@link #reconcile}. Runs no transaction
     * around the checks and none around the Razorpay refund; the credit itself
     * is {@link #creditOrHold}, one transaction.
     */
    Settlement settle(WalletTopUp topUp, PaymentProvider.ProviderPayment payment, String source) {
        if (topUp.getRazorpayOrderId() == null || !topUp.getRazorpayOrderId().equals(payment.providerOrderId())) {
            // A real payment, but for some other order. Never credited to this top-up,
            // whoever brought it: without this, any captured payment id would fill any wallet.
            log.error("Ignoring {} payment {} for wallet top-up {}: it belongs to order {}, not {}",
                    source, payment.providerPaymentId(), topUp.getId(),
                    payment.providerOrderId(), topUp.getRazorpayOrderId());
            return Settlement.REJECTED;
        }

        switch (payment.status()) {
            case CAPTURED -> { }
            case CREATED, AUTHORIZED -> {
                log.info("Wallet top-up {} payment {} is {} at Razorpay; waiting for capture",
                        topUp.getId(), payment.providerPaymentId(), payment.status());
                return Settlement.PROCESSING;
            }
            case FAILED -> {
                log.info("Wallet top-up {} payment attempt {} failed: {}",
                        topUp.getId(), payment.providerPaymentId(), payment.failureCode());
                return Settlement.DECLINED;
            }
            default -> {
                log.warn("Wallet top-up {} payment {} is {} at Razorpay, not captured; nothing credited",
                        topUp.getId(), payment.providerPaymentId(), payment.status());
                return Settlement.REJECTED;
            }
        }

        // The amount we stored, not the amount anyone claims. Razorpay fixes an
        // order's amount, so this should never differ; if it does, something is
        // badly wrong and a person, not this code, decides what to do with money
        // that does not match what was asked for.
        if (payment.capturedAmount() == null || payment.capturedAmount().compareTo(topUp.getAmount()) != 0) {
            log.error("Wallet top-up {}: {} payment {} captured {} but the top-up is for {}; "
                            + "NOT credited and NOT refunded — needs a person",
                    topUp.getId(), source, payment.providerPaymentId(),
                    payment.capturedAmount(), topUp.getAmount().toPlainString());
            return Settlement.REJECTED;
        }

        var outcome = tx.execute(status -> creditOrHold(topUp.getId(), topUp.getOutletId(), payment));
        if (outcome == Held.REFUND_DUE) {
            return sendRefund(topUp.getId());
        }
        return switch (outcome) {
            case CREDITED -> Settlement.CREDITED;
            case REFUNDING -> Settlement.REFUNDING;
            case REFUNDED -> Settlement.REFUNDED;
            default -> Settlement.REJECTED;
        };
    }

    private enum Held { CREDITED, REFUND_DUE, REFUNDING, REFUNDED, ENDED }

    /**
     * The credit, in one transaction: wallet lock, top-up lock, limits, the
     * conditional CREATED-to-CREDITED update, the ledger credit.
     *
     * <p>Wallet first, then the top-up, always — the order every other wallet
     * writer uses for the wallet, so nothing here can deadlock against them.
     * Locking reads see the latest committed data whatever the isolation level, so
     * the balance and the status below are what the last committer left, and the
     * month's total (an ordinary read, hence READ COMMITTED) is too.
     */
    private Held creditOrHold(Long topUpId, Long outletId, PaymentProvider.ProviderPayment payment) {
        var wallet = wallets.lock(outletId);
        var topUp = topUps.lockById(topUpId).orElseThrow();

        switch (topUp.getStatus()) {
            case CREDITED -> {
                return Held.CREDITED;
            }
            case REFUND_PENDING -> {
                return Held.REFUNDING;
            }
            case REFUNDED -> {
                return Held.REFUNDED;
            }
            case FAILED -> {
                return Held.ENDED;
            }
            default -> { }
        }

        var now = Instant.now();
        var added = topUps.sumCredited(outletId, WalletTopUpStatus.CREDITED,
                limits.monthStart(now), limits.monthEnd(now));
        var breach = breachOf(wallet.getBalance(), added, topUp.getAmount());
        if (breach != null) {
            // The money is captured and cannot be credited. It goes back — never
            // dropped, never over-credited (D-107).
            if (topUps.markRefundPending(topUpId, payment.providerPaymentId(), truncate(breach)) == 1) {
                audit.record(null, null, "WALLET_TOP_UP_REFUND_DUE", "WALLET_TOP_UP", topUpId,
                        topUp.getStatus().name(), WalletTopUpStatus.REFUND_PENDING.name(), breach, "SYSTEM");
                log.warn("Wallet top-up {} of {} captured as {} but would break a wallet limit ({}); refunding it",
                        topUpId, topUp.getAmount().toPlainString(), payment.providerPaymentId(), breach);
            }
            return Held.REFUND_DUE;
        }

        if (topUps.markCredited(topUpId, payment.providerPaymentId(), now) != 1) {
            // Cannot happen while both locks are held; if it ever does, crediting
            // anyway is exactly the double credit this exists to prevent.
            log.error("Wallet top-up {} could not move to CREDITED although locked; not credited", topUpId);
            return Held.ENDED;
        }
        wallets.creditTopUp(outletId, topUpId, topUp.getAmount());
        audit.record(null, null, "WALLET_TOP_UP_CREDITED", "WALLET_TOP_UP", topUpId,
                topUp.getStatus().name(), WalletTopUpStatus.CREDITED.name(),
                topUp.getAmount().toPlainString() + " via " + payment.providerPaymentId(), "SYSTEM");
        return Held.CREDITED;
    }

    // ── Returning money that could not be credited ───────────────────────

    /**
     * Return a captured payment to its source. Called straight after the credit
     * was refused, and again by the poller until it lands.
     *
     * <p>A provider refund, sent with no transaction open and a key derived from
     * the top-up, so a retry — after a timeout, or a crash between the send and
     * the write — reaches the same refund at Razorpay rather than a second one.
     */
    Settlement sendRefund(Long topUpId) {
        var topUp = topUps.findById(topUpId).orElse(null);
        if (topUp == null) {
            return Settlement.REJECTED;
        }
        if (topUp.getStatus() == WalletTopUpStatus.REFUNDED) {
            return Settlement.REFUNDED;
        }
        if (topUp.getStatus() != WalletTopUpStatus.REFUND_PENDING) {
            return Settlement.REJECTED;
        }

        try (var trace = TraceScope.of("wallet_top_up", topUpId, "outlet", topUp.getOutletId(),
                "rzp_order", topUp.getRazorpayOrderId(), "rzp_payment", topUp.getRazorpayPaymentId())) {
            PaymentProvider.ProviderRefund refund;
            try {
                refund = topUp.getProviderRefundId() != null
                        ? provider.fetchRefund(topUp.getProviderRefundId())
                        : provider.refund(topUp.getRazorpayPaymentId(), topUp.getAmount(),
                                "mandi-topup-refund-" + topUpId,
                                // Findable at Razorpay by our own reference, and not ours to the order-refund
                                // matching, whose receipts start "mandi-refund-": a top-up is not a payment row.
                                new PaymentProvider.RefundOptions("mandi-topup-refund-" + topUpId,
                                        java.util.Map.of("mandi_topup_id", String.valueOf(topUpId)), null));
            } catch (PaymentProviderException ex) {
                refundNotDone(topUp, null, 1, "Razorpay: " + ex.getMessage());
                return Settlement.REFUNDING;
            }

            switch (refund.status()) {
                case COMPLETED -> {
                    tx.executeWithoutResult(status -> {
                        topUps.noteRefundAttempt(topUpId, refund.providerRefundId(), 0, Instant.now());
                        topUps.markRefunded(topUpId);
                    });
                    audit.record(null, null, "WALLET_TOP_UP_REFUNDED", "WALLET_TOP_UP", topUpId,
                            WalletTopUpStatus.REFUND_PENDING.name(), WalletTopUpStatus.REFUNDED.name(),
                            topUp.getAmount().toPlainString() + " to source", "SYSTEM");
                    log.info("Wallet top-up {} REFUND_PENDING → REFUNDED: {} returned as refund {}",
                            topUpId, topUp.getAmount().toPlainString(), refund.providerRefundId());
                    return Settlement.REFUNDED;
                }
                case PENDING -> {
                    // Accepted, not finished: remembered, and asked about next time —
                    // never sent again, and not counted against the attempts.
                    tx.executeWithoutResult(status ->
                            topUps.noteRefundAttempt(topUpId, refund.providerRefundId(), 0, Instant.now()));
                    log.info("Wallet top-up {} refund {} accepted by Razorpay; waiting for it to finish",
                            topUpId, refund.providerRefundId());
                    return Settlement.REFUNDING;
                }
                default -> {
                    refundNotDone(topUp, refund.providerRefundId(), 1,
                            "Razorpay refused the refund: " + refund.failureCode());
                    return Settlement.REFUNDING;
                }
            }
        }
    }

    private void refundNotDone(WalletTopUp topUp, String refundId, int attempts, String why) {
        tx.executeWithoutResult(status ->
                topUps.noteRefundAttempt(topUp.getId(), refundId, attempts, Instant.now()));
        int done = topUp.getRefundAttempts() + attempts;
        if (done >= MAX_REFUND_ATTEMPTS) {
            // Error, for an alert to match: captured money is neither credited nor
            // going back, and a person has to act.
            log.error("Wallet top-up {} refund of {} failed {} times ({}); NEEDS A PERSON — money captured, "
                            + "not credited, not returned",
                    topUp.getId(), topUp.getAmount().toPlainString(), done, why);
        } else {
            log.warn("Wallet top-up {} refund attempt {} failed: {}", topUp.getId(), done, why);
        }
    }

    // ── Background ───────────────────────────────────────────────────────

    /**
     * Ask Razorpay what became of a CREATED top-up, and credit it if it was paid.
     *
     * <p>The safety net for a confirm that never arrived: the app died after
     * paying, or the network did. Also where an abandoned top-up ends — but only
     * after Razorpay has been asked, so a payment the client never reported is
     * credited rather than expired.
     */
    public void reconcile(Long topUpId) {
        var topUp = topUps.findById(topUpId).orElse(null);
        if (topUp == null || topUp.getStatus() != WalletTopUpStatus.CREATED) {
            return;
        }
        var now = Instant.now();
        var age = Duration.between(topUp.getCreatedAt(), now);

        try (var trace = TraceScope.of("wallet_top_up", topUpId, "outlet", topUp.getOutletId(),
                "rzp_order", topUp.getRazorpayOrderId())) {
            if (topUp.getRazorpayOrderId() == null) {
                if (age.compareTo(NEVER_OPENED_AFTER) > 0) {
                    tx.executeWithoutResult(status -> topUps.endFromCreated(topUpId,
                            WalletTopUpStatus.FAILED, "Never reached Razorpay"));
                    log.warn("Wallet top-up {} FAILED: it never reached Razorpay, so nothing was payable", topUpId);
                }
                return;
            }

            java.util.Optional<PaymentProvider.ProviderPayment> found;
            try {
                found = provider.findPaymentForOrder(topUp.getRazorpayOrderId());
            } catch (PaymentProviderException ex) {
                // Unreachable providers are normal; the next sweep asks again. Not
                // marked as asked, so it is not pushed to the back of the queue.
                log.warn("Could not ask Razorpay about wallet top-up {}: {}", topUpId, ex.getMessage());
                return;
            }
            tx.executeWithoutResult(status -> topUps.markChecked(topUpId, Instant.now()));

            if (found.isPresent()) {
                var settled = settle(topUp, found.get(), "POLLER");
                log.info("Wallet top-up {} polled: payment {} → {}",
                        topUpId, found.get().providerPaymentId(), settled);
                return;
            }
            if (age.compareTo(EXPIRE_AFTER) > 0
                    && tx.execute(status -> topUps.endFromCreated(topUpId, WalletTopUpStatus.EXPIRED,
                            "Not paid within a day")) == 1) {
                log.info("Wallet top-up {} EXPIRED: Razorpay has no payment for order {} after a day",
                        topUpId, topUp.getRazorpayOrderId());
            }
        }
    }

    /** The CREATED top-ups due to be asked about, oldest first. */
    public java.util.List<WalletTopUp> dueForPolling(int batch) {
        var now = Instant.now();
        return topUps.findDue(now.minus(POLL_AFTER), now.minus(YOUNG_FOR),
                now.minus(YOUNG_RECHECK), now.minus(OLD_RECHECK),
                org.springframework.data.domain.PageRequest.of(0, batch));
    }

    /** The refunds due to be sent or confirmed, oldest first. */
    public java.util.List<WalletTopUp> refundsDue(int batch) {
        return topUps.findRefundsDue(MAX_REFUND_ATTEMPTS, Instant.now().minus(REFUND_RECHECK),
                org.springframework.data.domain.PageRequest.of(0, batch));
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }
}
