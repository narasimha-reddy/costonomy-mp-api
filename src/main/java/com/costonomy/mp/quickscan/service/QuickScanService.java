package com.costonomy.mp.quickscan.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.quickscan.domain.QuickScanMethod;
import com.costonomy.mp.quickscan.domain.QuickScanPayment;
import com.costonomy.mp.quickscan.domain.QuickScanStatus;
import com.costonomy.mp.quickscan.provider.PayoutProvider;
import com.costonomy.mp.quickscan.provider.PayoutProviderException;
import com.costonomy.mp.quickscan.repository.QuickScanPaymentRepository;
import com.costonomy.mp.quickscan.web.dto.QuickScanDtos;
import com.costonomy.mp.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * QuickScan wallet payments — pay any UPI merchant from the wallet. D-106,
 * part one: sandbox only, wallet only.
 *
 * <p>The wallet is debited for the whole amount plus the fee <b>before</b> the
 * payout provider is asked to do anything, in the same transaction the payment
 * row is created in — {@link WalletService#lock} first, so the debit never
 * races a second click. The payout then catches up with what the wallet already
 * did, the same claim → call provider outside the transaction → record outcome
 * shape as {@code RefundService.process}: {@link #sendPayout} claims the row,
 * calls the provider with no database transaction open, and writes the outcome
 * against a fresh read.
 *
 * <p>A payout that is refused or reversed returns the money
 * ({@link WalletService#returnQuickScan}); one whose outcome we never learn
 * after {@link #MAX_ATTEMPTS} tries goes to {@link QuickScanStatus#NEEDS_REVIEW}
 * with the money left where it is, because it may already have reached the
 * shop — the same reasoning as a refund's NEEDS_REVIEW (D-101).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuickScanService {

    private final QuickScanPaymentRepository payments;
    private final WalletService wallet;
    private final PayoutProvider provider;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    @Value("${costonomy.mp.quickscan.enabled:false}")
    private boolean enabled;

    @Value("${costonomy.mp.quickscan.max-amount:10000}")
    private BigDecimal maxAmount;

    @Value("${costonomy.mp.quickscan.fee:0}")
    private BigDecimal fee;

    /** Sends before a payout whose outcome we never learn goes to a person (D-101's shape). */
    static final int MAX_ATTEMPTS = 5;

    /** How long a claimed payout may sit with no provider id before it is assumed stuck. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    /**
     * How long the job leaves a brand-new row alone before treating it as its
     * own to claim. {@code payFromWallet} always makes its own synchronous send
     * right after the debit commits; a fresh row younger than this is still that
     * request's, and the job racing it for the claim is D-106's charged-then-
     * errored bug.
     */
    static final Duration JOB_CLAIM_DELAY = Duration.ofSeconds(30);

    /** How long a PENDING payout is left alone between checks with the provider. */
    static final Duration PENDING_CHECK_EVERY = Duration.ofMinutes(1);

    /**
     * How long after going PAID a payout is still worth asking about. A
     * reversal, if it happens, usually shows up within hours of the payout
     * going through — a later one is an ops matter, not something to poll for
     * forever.
     */
    static final Duration PAID_WATCH_FOR = Duration.ofHours(48);

    /** How often a PAID payout still inside {@link #PAID_WATCH_FOR} is re-checked. */
    static final Duration PAID_CHECK_EVERY = Duration.ofHours(6);

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_NOTE_LENGTH = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal maxAmount() {
        return maxAmount;
    }

    public BigDecimal fee() {
        return fee;
    }

    /** What the request must supply. Validated and normalised by {@link #payFromWallet}. */
    public record PayRequest(String payeeVpa, String payeeName, BigDecimal amount, String note) {
    }

    /**
     * Pay a UPI merchant from the outlet's wallet.
     *
     * <p>One transaction locks the wallet, checks it can cover the amount plus
     * the fee, inserts the payment row and debits the wallet — all before any
     * payout is attempted. Then, after that commits and with no transaction
     * open, {@link #sendPayout} runs once so the common case finishes within the
     * request; {@code QuickScanJobs} is the safety net for whatever that call
     * does not settle.
     */
    public QuickScanDtos.PaymentResponse payFromWallet(
            Long actorId, Long outletId, PayRequest request, String idempotencyKey) {

        if (!enabled) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "QuickScan isn't available yet.");
        }
        PayRequest normalised = validate(request);

        Long paymentId = txTemplate.execute(status -> {
            var walletRow = wallet.lock(outletId);
            if (!walletRow.isUsable()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "This wallet is on hold. Please contact support.");
            }
            BigDecimal total = normalised.amount().add(fee);
            if (total.compareTo(walletRow.getBalance()) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Your wallet doesn't have ₹%s.".formatted(Rupees.of(total)));
            }

            var payment = new QuickScanPayment();
            payment.setOutletId(outletId);
            payment.setCreatedBy(actorId);
            payment.setPayeeVpa(normalised.payeeVpa());
            payment.setPayeeName(normalised.payeeName());
            payment.setNote(normalised.note());
            payment.setAmount(normalised.amount());
            payment.setFeeAmount(fee);
            payment.setMethod(QuickScanMethod.WALLET);
            payment.setStatus(QuickScanStatus.PAYOUT_PENDING);
            payment.setIdempotencyKey("qs-" + outletId + "-" + idempotencyKey);
            payments.saveAndFlush(payment);

            wallet.debitQuickScan(outletId, payment.getId(), total);

            auditService.record(actorId, null, "QUICKSCAN_PAYMENT_CREATED", "QUICKSCAN_PAYMENT",
                    payment.getId(), null, QuickScanStatus.PAYOUT_PENDING.name(),
                    normalised.amount().toPlainString(), "API");
            outbox.publish("QuickScanPaymentCreated", "QUICKSCAN_PAYMENT", payment.getId(),
                    Map.of("outletId", outletId, "amount", normalised.amount().toPlainString(),
                            "fee", fee.toPlainString(), "payeeVpa", mask(normalised.payeeVpa())),
                    actorId);

            log.info("QuickScan payment {} created for outlet {}: {} to {}",
                    payment.getId(), outletId, Rupees.of(total), mask(normalised.payeeVpa()));
            return payment.getId();
        });

        try {
            sendPayout(paymentId);
        } catch (RuntimeException ex) {
            // The debit above already committed — the money is out of the
            // wallet. The most likely cause here is the job winning the claim
            // first (an optimistic-lock failure on our own saveAndFlush inside
            // sendPayout), in which case the job is already finishing this
            // payout; any other failure leaves the row PAYOUT_PENDING for the
            // job to pick up next run regardless. Either way, throwing from here
            // would tell the restaurant its payment failed when the money has
            // already moved — and at a shop counter, that invites a second
            // payment on top of one that already happened. Report what actually
            // happened instead: read the row fresh and return it.
            log.warn("QuickScan payment {} payout attempt failed right after the debit committed; "
                    + "the job will finish it", paymentId, ex);
        }

        return toResponse(payments.findById(paymentId).orElseThrow());
    }

    /**
     * Send a claimed payout to the provider. Called once after
     * {@link #payFromWallet} commits, and again by {@code QuickScanJobs} for
     * whatever a synchronous call did not settle (a PENDING answer, a transient
     * failure, a process that died mid-call).
     */
    public void sendPayout(Long paymentId) {
        QuickScanPayment claimed = txTemplate.execute(status -> {
            var payment = payments.findById(paymentId).orElse(null);
            if (payment == null || !claimable(payment)) {
                return null;
            }
            payment.setAttempts(payment.getAttempts() + 1);
            return payments.saveAndFlush(payment);
        });
        if (claimed == null) {
            return;
        }

        try (var trace = TraceScope.of("quickscan", paymentId)) {
            PayoutProvider.ProviderPayout result = null;
            PayoutProviderException failure = null;
            try {
                result = provider.createPayout(claimed.getPayeeVpa(), claimed.getPayeeName(),
                        claimed.getAmount(), "QuickScan payment " + claimed.getId(),
                        "mandi-qs-" + claimed.getId());
            } catch (PayoutProviderException ex) {
                failure = ex;
            }

            final var outcome = result;
            final var error = failure;
            txTemplate.executeWithoutResult(status -> {
                var payment = payments.findById(paymentId).orElseThrow();
                if (payment.getStatus() != QuickScanStatus.PAYOUT_PENDING) {
                    return;
                }
                if (error != null) {
                    if (error.isRetryable() && payment.getAttempts() < MAX_ATTEMPTS) {
                        // Stays PAYOUT_PENDING; the attempt was already counted at
                        // the claim, and the job will try again.
                        log.warn("QuickScan payment {} payout attempt {} failed: {} {}",
                                payment.getId(), payment.getAttempts(), error.code(), error.getMessage());
                    } else {
                        needsReview(payment, error.code(), error.getMessage());
                    }
                    return;
                }
                applyOutcome(payment, outcome);
            });
        }
    }

    /**
     * Ask the provider about a payout it has already accepted. Called by the
     * job for rows with a provider id, whether still waiting
     * ({@link QuickScanStatus#PAYOUT_PENDING}) or already {@link QuickScanStatus#PAID}
     * — a PROCESSED payout can still come back REVERSED, so a PAID row is asked
     * about too until it ages out of the job's window.
     */
    public void settlePending(Long paymentId) {
        var pending = payments.findById(paymentId).orElse(null);
        if (pending == null || pending.getProviderPayoutId() == null || !settleable(pending.getStatus())) {
            return;
        }

        PayoutProvider.ProviderPayout answer;
        try (var trace = TraceScope.of("quickscan", paymentId)) {
            try {
                answer = provider.fetchPayout(pending.getProviderPayoutId());
            } catch (PayoutProviderException ex) {
                log.debug("Could not ask about QuickScan payment {}: {}", paymentId, ex.getMessage());
                return;
            }

            final var result = answer;
            txTemplate.executeWithoutResult(status -> {
                var payment = payments.findById(paymentId).orElseThrow();
                if (payment.getProviderPayoutId() == null || !settleable(payment.getStatus())) {
                    return;
                }
                // The provider answered, whatever the answer — record that this
                // row was checked just now. A plain save() on an otherwise
                // unchanged entity is a no-op (no dirty field, no UPDATE,
                // updated_at never moves), which is why a PENDING "touch" and an
                // already-PAID row confirmed still PAID used to be fetched from
                // the provider on every job run forever. Setting checkedAt makes
                // every branch below dirty, so findSettleable's window actually
                // takes effect.
                payment.setCheckedAt(Instant.now());
                switch (result.status()) {
                    case PROCESSED -> {
                        if (payment.getStatus() == QuickScanStatus.PAYOUT_PENDING) {
                            markPaid(payment);
                        } else {
                            // Already PAID: save so checked_at moves and the row
                            // does not come back until PAID_CHECK_EVERY passes.
                            payments.save(payment);
                        }
                    }
                    case FAILED, REVERSED -> markFailedAndReturn(payment, result.failureCode(),
                            result.failureReason() == null
                                    ? "The provider reversed the payout" : result.failureReason());
                    case PENDING -> payments.save(payment); // touch checked_at, wait its turn
                }
            });
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private void applyOutcome(QuickScanPayment payment, PayoutProvider.ProviderPayout outcome) {
        switch (outcome.status()) {
            case PROCESSED -> {
                payment.setProviderPayoutId(outcome.providerPayoutId());
                markPaid(payment);
            }
            case PENDING -> {
                payment.setProviderPayoutId(outcome.providerPayoutId());
                payments.save(payment);
                log.info("QuickScan payment {} accepted by the provider as {}; waiting for it to finish",
                        payment.getId(), outcome.providerPayoutId());
            }
            case FAILED, REVERSED -> {
                payment.setProviderPayoutId(outcome.providerPayoutId());
                markFailedAndReturn(payment, outcome.failureCode(), outcome.failureReason());
            }
        }
    }

    private void markPaid(QuickScanPayment payment) {
        var previous = payment.getStatus();
        payment.setStatus(QuickScanStatus.PAID);
        payment.setPaidAt(Instant.now());
        payments.save(payment);
        auditService.recordTransition(null, "QUICKSCAN_PAYMENT_PAID", "QUICKSCAN_PAYMENT",
                payment.getId(), previous.name(), QuickScanStatus.PAID.name());
        outbox.publish("QuickScanPaymentPaid", "QUICKSCAN_PAYMENT", payment.getId(),
                Map.of("outletId", payment.getOutletId(), "amount", payment.getAmount().toPlainString()),
                null);
        log.info("QuickScan payment {} {} → PAID", payment.getId(), previous);
    }

    private void markFailedAndReturn(QuickScanPayment payment, String code, String reason) {
        var previous = payment.getStatus();
        payment.setStatus(QuickScanStatus.FAILED);
        payment.setFailureCode(code);
        payment.setFailureReason(reason);
        payments.save(payment);
        auditService.recordTransition(null, "QUICKSCAN_PAYMENT_FAILED", "QUICKSCAN_PAYMENT",
                payment.getId(), previous.name(), QuickScanStatus.FAILED.name());
        // The wallet, last: it clears the persistence context (see WalletService),
        // and nothing loaded before it may be written after it.
        wallet.returnQuickScan(payment.getOutletId(), payment.getId(), payment.total(),
                "QuickScan payment failed" + (reason == null ? "" : ": " + reason));
        outbox.publish("QuickScanPaymentFailed", "QUICKSCAN_PAYMENT", payment.getId(),
                Map.of("outletId", payment.getOutletId(), "amount", payment.getAmount().toPlainString(),
                        "reason", String.valueOf(reason)),
                null);
        log.info("QuickScan payment {} {} → FAILED: {} {}", payment.getId(), previous, code, reason);
    }

    private void needsReview(QuickScanPayment payment, String code, String reason) {
        payment.setStatus(QuickScanStatus.NEEDS_REVIEW);
        payment.setFailureCode(code);
        payment.setFailureReason(reason);
        payments.save(payment);
        // Error, not warn: the money is out of the wallet and its outcome is
        // unknown — it may have reached the shop — and a person has to act.
        log.error("QuickScan payment {} → NEEDS_REVIEW after {} attempt(s): {} {}",
                payment.getId(), payment.getAttempts(), code, reason);
    }

    /** Waiting to be sent, or claimed by a process that died before a provider id was recorded. */
    private static boolean claimable(QuickScanPayment payment) {
        if (payment.getStatus() != QuickScanStatus.PAYOUT_PENDING || payment.getProviderPayoutId() != null) {
            return false;
        }
        return payment.getAttempts() == 0
                || (payment.getUpdatedAt() != null && payment.getUpdatedAt().isBefore(Instant.now().minus(STUCK_AFTER)));
    }

    private static boolean settleable(QuickScanStatus status) {
        return status == QuickScanStatus.PAYOUT_PENDING || status == QuickScanStatus.PAID;
    }

    private PayRequest validate(PayRequest request) {
        if (request == null || !QuickScanValidation.isValidVpa(request.payeeVpa())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Enter a valid UPI ID.");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Enter an amount greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount with at most two decimal places.");
        }
        if (amount.compareTo(maxAmount) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "QuickScan payments are capped at ₹%s.".formatted(Rupees.of(maxAmount)));
        }
        String payeeName = cap(trim(request.payeeName()), MAX_NAME_LENGTH);
        String note = cap(trim(request.note()), MAX_NOTE_LENGTH);
        return new PayRequest(request.payeeVpa().trim(), payeeName, amount, note);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String cap(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Keeps the first two characters and the handle; everything between becomes stars. */
    static String mask(String vpa) {
        if (vpa == null) {
            return null;
        }
        int at = vpa.indexOf('@');
        if (at < 0) {
            return "***";
        }
        String local = vpa.substring(0, at);
        String prefix = local.length() >= 2 ? local.substring(0, 2) : local;
        return prefix + "***@" + vpa.substring(at + 1);
    }

    public QuickScanDtos.PaymentResponse toResponse(QuickScanPayment payment) {
        BigDecimal total = payment.total();
        return new QuickScanDtos.PaymentResponse(
                payment.getId(), payment.getOutletId(), payment.getPayeeVpa(), payment.getPayeeName(),
                payment.getNote(), payment.getAmount(), payment.getFeeAmount(), total,
                payment.getMethod().name(), payment.getStatus(), payment.getFailureReason(),
                payment.getCreatedAt(), payment.getPaidAt());
    }
}
