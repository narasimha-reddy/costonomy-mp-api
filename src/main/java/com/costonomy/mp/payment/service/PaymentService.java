package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Payment state. Doc 03 §6, doc 21.
 *
 * <p>Two rules govern everything here.
 *
 * <p><b>The provider is the authority.</b> A payment becomes {@code AUTHORIZED}
 * because the provider says so — via a verified webhook, or because we asked them
 * — never because a client reported success. Doc 01 §14 and guardrail 3 make this
 * absolute, and it is the reason {@code confirm()} calls
 * {@link PaymentProvider#fetchPayment} instead of trusting its own request body.
 *
 * <p><b>Transitions are checked, never assumed.</b> Doc 03 §6 warns that webhooks
 * arrive out of order, so a late {@code authorized} event cannot drag a captured
 * payment backwards. {@link #transition} refuses rather than reorders.
 *
 * <p><b>No provider call holds a database connection.</b> {@link #confirm} and
 * {@link #performCapture} ask the provider first, with no transaction open, and
 * only then open a short one to write the answer — through {@link TransactionTemplate},
 * not {@code @Transactional}, because a self-invocation would bypass the proxy
 * (CLAUDE.md). The pool is ten connections and a provider read may take fifteen
 * seconds: ten slow checkouts holding connections would stall every endpoint,
 * not only payments (D-099).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentProvider provider;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;
    private final CancellationLedger ledger;
    private final PaymentHoldPolicy holdPolicy;

    // ── Creation ─────────────────────────────────────────────────────────

    /**
     * Record the payment an order will be paid with. Writes the row and nothing else: no provider call.
     *
     * <p>Joins the order's transaction. The provider call used to be made here, inside it, so a connection (and the
     * intent row's lock) were held across a network round trip to Razorpay, and a provider failure rolled the whole
     * order back (D-136). Opening the provider's checkout is {@link #openCheckout}, run after the order commits.
     *
     * <p>Idempotent by construction: {@code uk_payment_order} means one payment per order, so a resubmission returns
     * the existing row rather than creating a second one (doc 10 §2).
     */
    @Transactional
    public Payment recordForOrder(Long supplierOrderId, Long procurementId, Long outletId,
                                  BigDecimal amount, String paymentMethod) {

        var existing = payments.findBySupplierOrderId(supplierOrderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var payment = new Payment();
        payment.setSupplierOrderId(supplierOrderId);
        payment.setProcurementId(procurementId);
        payment.setOutletId(outletId);
        payment.setProvider(provider.name());
        payment.setPaymentMethod(paymentMethod);
        payment.setAuthorizedAmount(amount);
        payment.setStatus(PaymentStatus.CREATED);
        payments.saveAndFlush(payment);
        return payment;
    }

    /**
     * Open the provider's checkout for a payment that has none yet, outside any transaction (D-136).
     *
     * <p>Deliberately not {@code @Transactional}, and it must not be called from inside a transaction: the provider
     * call is a network round trip and holds nothing. The result is written with one conditional update, so two
     * callers racing to open the same checkout cannot overwrite each other: the first to write wins, the second finds
     * the checkout already there and keeps it. The provider is given a key derived from the order, so both reach the
     * same provider order anyway; if they did not, the one that lost is an unused provider order nobody was shown,
     * which is harmless and logged.
     *
     * <p>A provider failure leaves the payment CREATED and the order a DRAFT, so the supplier still sees nothing
     * (guardrail 16), and answers {@code PAYMENT_FAILED}: nothing was charged, and a retry opens the checkout. A
     * payment whose checkout is never opened is ended by the reconciliation sweep.
     *
     * @return the payment as it now stands, with its provider order id if there is one
     */
    public Payment openCheckout(Long paymentId) {
        var current = payments.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        if (current.getStatus() != PaymentStatus.CREATED
                || current.getProviderOrderId() != null
                || current.getCancelRequestedAt() != null) {
            return current;
        }

        int holdMinutes = (int) holdPolicy.holdLimit().toMinutes();
        PaymentProvider.AuthorizationIntent intent;
        try {
            // The hold is chosen here, once, and stored with the payment: the provider fixes an order's expiry when the
            // order is made, so what the guard on "ready" measures against later must be this figure, not whatever the
            // setting says by then (D-109).
            intent = provider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                    "order-" + current.getSupplierOrderId(), current.getAuthorizedAmount(), "INR",
                    "Mandi order " + current.getSupplierOrderId(),
                    // Derived from the order, not random: a retry of the same submission reaches the same intent on the
                    // provider's side.
                    "auth-order-" + current.getSupplierOrderId(), holdMinutes));
        } catch (PaymentProviderException ex) {
            log.warn("Payment {} checkout could not be opened ({}): {}", paymentId, ex.providerCode(), ex.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "Nothing was charged. Try again.");
        }

        int written = payments.openCheckoutIfUnopened(paymentId, intent.providerOrderId(),
                intent.holdMinutes() != null ? intent.holdMinutes() : holdMinutes);
        if (written == 0) {
            log.warn("Payment {} already had its checkout (or is no longer CREATED); provider order {} was not used",
                    paymentId, intent.providerOrderId());
        } else {
            log.info("Payment {} checkout opened with provider order {}, held {} minutes", paymentId,
                    intent.providerOrderId(), intent.holdMinutes() != null ? intent.holdMinutes() : holdMinutes);
        }
        // The update cleared the persistence context, so this reads what is in the database now.
        return payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
    }

    // ── Authorization ────────────────────────────────────────────────────

    /**
     * Confirm a payment by asking the provider what happened.
     *
     * <p>The client tells us <em>which</em> payment to look at and nothing more.
     * Doc 01 §14, guardrail 3, and §23A.19 all say the same thing from different
     * angles: a client callback is not financial truth.
     *
     * <p>This is also doc 46's recovery path. A client that timed out after paying
     * calls here — or never calls, and the reconciliation job asks on its behalf.
     */
    public Payment confirm(Long paymentId, String providerPaymentId) {
        var payment = load(paymentId);

        if (payment.getStatus().fundsSecured()) {
            // Already resolved, by a webhook or an earlier confirm. Returning the
            // payment is the true answer to "did my payment go through?".
            return payment;
        }

        PaymentProvider.ProviderPayment providerPayment;
        try {
            providerPayment = provider.fetchPayment(providerPaymentId);
        } catch (PaymentProviderException ex) {
            if (ex.isRetryable()) {
                // We could not ask. Leaving the payment alone is correct — the
                // reconciliation job will ask again. Claiming failure here could
                // release an authorisation the customer actually completed.
                throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                        "We couldn't confirm your payment just yet. We'll keep checking.");
            }
            // The provider refused to look it up: it does not know that id. That
            // says the client's claim is wrong, not that this payment failed —
            // and FAILED is terminal, so failing it here would let one stale or
            // garbled id make an order unpayable for good. Refuse the claim and
            // leave the payment waiting for its own money, as a mismatch does.
            log.warn("Confirm for payment {} named a payment the provider refused: {}",
                    paymentId, ex.getMessage());
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We couldn't find that payment. Nothing has changed on this order.");
        }

        if (!completes(payment, providerPayment)) {
            // A real payment id, but not this payment's. Without this check any
            // authorised payment — a cheaper one, someone else's — would fund
            // this order. Nothing changes; the payment is still waiting for its
            // own money.
            auditService.record(null, null, "PAYMENT_CONFIRM_MISMATCH", "PAYMENT",
                    payment.getId(), payment.getStatus().name(), payment.getStatus().name(),
                    "Provider payment " + providerPaymentId + " belongs to order "
                            + providerPayment.providerOrderId(), "CLIENT");
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment doesn't belong to this order.");
        }

        try {
            // Re-read inside the transaction: the copy above is detached, and a
            // webhook may have moved the payment while we were asking.
            return txTemplate.execute(status -> applyProviderState(
                    payments.lockById(paymentId).orElseThrow(), providerPayment, "CONFIRM"));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            // A webhook or the sweep wrote first. Report what won (D-018) rather
            // than a concurrency error: the payment is the answer to "did it work".
            log.info("Confirm for payment {} lost a race; returning the winning state", paymentId);
            return load(paymentId);
        }
    }

    /**
     * Whether a provider payment is the one completing this payment's intent.
     *
     * <p>The provider says a payment exists and what state it is in; only this
     * says it is <em>ours</em>. It must complete the intent we created — the
     * provider order id we minted — and, the first time money appears against
     * it, for the amount we asked for.
     */
    boolean completes(Payment payment, PaymentProvider.ProviderPayment providerPayment) {
        if (payment.getProviderOrderId() == null
                || !payment.getProviderOrderId().equals(providerPayment.providerOrderId())) {
            return false;
        }
        boolean moneyArrives = payment.getStatus() == PaymentStatus.CREATED
                && (providerPayment.status() == PaymentProvider.ProviderPaymentStatus.AUTHORIZED
                        || providerPayment.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED);
        return !moneyArrives
                || providerPayment.authorizedAmount().compareTo(payment.getAuthorizedAmount()) == 0;
    }

    /**
     * Reconcile our record against the provider's. Doc 21.
     *
     * <p>Used by webhooks and by the reconciliation job. Deliberately ignores
     * anything that would move the payment backwards, because an out-of-order
     * webhook is a statement about the past, not the present.
     */
    @Transactional
    public Payment applyProviderState(Payment given,
                                      PaymentProvider.ProviderPayment providerPayment,
                                      String source) {

        // Lock and re-read before deciding anything: the caller's copy may be
        // detached and stale, and a concurrent writer for the same payment must
        // wait here rather than deadlock further down.
        var payment = payments.lockById(given.getId()).orElseThrow();

        if (!completes(payment, providerPayment)) {
            // Never applied, whoever brought it: a webhook, the sweep or a
            // capture. Logged at error because it should not happen — the
            // provider describing a payment against someone else's intent means
            // a bug on one side, and a human should look.
            log.error("Ignoring {} provider payment {} for payment {}: it completes order {}, not {}",
                    source, providerPayment.providerPaymentId(), payment.getId(),
                    providerPayment.providerOrderId(), payment.getProviderOrderId());
            return payment;
        }

        if (payment.getStatus() == PaymentStatus.CANCEL_PENDING) {
            // The order was cancelled and the money is on its way back, by the
            // cancellation job and nothing else (D-109). A provider event about it —
            // above all the "captured" for the capture that job itself made — must
            // not move it: the job writes CAPTURED and the refund together, and a
            // CAPTURED written here first would leave a captured payment with no
            // refund and nothing to raise one. Only the method is worth keeping, and
            // only from the attempt that holds the money: a late declined card attempt
            // must not turn a UPI payment into "card", which the apps read as "you were
            // not charged" (F6). The same two tests as everywhere else: it is our
            // attempt, and it carries money.
            if (providerPayment.providerPaymentId() != null
                    && providerPayment.providerPaymentId().equals(payment.getProviderPaymentId())
                    && carriesMoney(providerPayment)) {
                recordMethod(payment, providerPayment);
            }
            payment.setReconciledAt(Instant.now());
            payments.save(payment);
            log.info("Payment {} is CANCEL_PENDING; {} state {} left to the cancellation job",
                    payment.getId(), source, providerPayment.status());
            return payment;
        }

        // One order, several attempts (D-101). Razorpay lets a customer retry
        // inside the same checkout, so a payment id we are handed may be an
        // attempt other than the one that holds the money.
        String current = payment.getProviderPaymentId();
        String incoming = providerPayment.providerPaymentId();

        if (providerPayment.status() == PaymentProvider.ProviderPaymentStatus.FAILED
                && payment.getStatus() == PaymentStatus.CREATED) {
            // A declined attempt is not the outcome while the order can still be
            // paid: the customer may try again against the same order, and
            // failing the payment here — FAILED is terminal — would abandon an
            // order they then pay for. Record the decline and stay payable; an
            // intent nobody completes is expired by the sweep instead.
            record(payment, "AUTHORIZE", payment.getAuthorizedAmount(), "FAILED", incoming,
                    providerPayment.failureCode(), providerPayment.failureReason());
            payment.setFailureCode(providerPayment.failureCode());
            payment.setFailureReason(providerPayment.failureReason());
            payment.setReconciledAt(Instant.now());
            payments.save(payment);
            log.info("Payment {} attempt {} declined via {}; still payable: {}",
                    payment.getId(), incoming, source, providerPayment.failureCode());
            return payment;
        }

        boolean carriesMoney = carriesMoney(providerPayment);
        // A payment abandoned for want of money (INTENT_EXPIRED) can still be paid:
        // a UPI payment may authorise days after it was created (D-109). It is the
        // one FAILED payment that can take money, and the money must not be ignored.
        boolean canTakeMoney = payment.getStatus() == PaymentStatus.CREATED || reopenable(payment);
        if (current != null && incoming != null && !current.equals(incoming)
                && !(canTakeMoney && carriesMoney)) {
            // Another attempt, describing itself. Once a payment holds or has
            // taken money it is that attempt's, and nothing about a different one
            // — a late decline, a retried delivery — may move it. Before, a
            // declined attempt arriving after authorisation failed the payment:
            // the order went ahead, capture never ran, and the supplier delivered
            // for nothing.
            log.warn("Ignoring {} attempt {} for payment {}: it tracks attempt {} ({})",
                    source, incoming, payment.getId(), current, payment.getStatus());
            return payment;
        }

        payment.setProviderPaymentId(incoming);
        payment.setReconciledAt(Instant.now());
        if (carriesMoney) {
            // Only the attempt that holds or has taken the money says how it was
            // paid, and it cannot change once it does. A declined or unfinished
            // attempt's method is not recorded: a stale "card" from an attempt that
            // never paid would have a UPI cancellation drop a hold that is not there.
            recordMethod(payment, providerPayment);
        }

        if (reopenable(payment) && carriesMoney) {
            return reopenForReturn(payment, providerPayment, source);
        }

        PaymentStatus target = switch (providerPayment.status()) {
            case AUTHORIZED -> PaymentStatus.AUTHORIZED;
            case CAPTURED -> PaymentStatus.CAPTURED;
            case FAILED -> PaymentStatus.FAILED;
            case RELEASED -> PaymentStatus.RELEASED;
            case REFUNDED -> PaymentStatus.FULLY_REFUNDED;
            case CREATED -> PaymentStatus.CREATED;
        };

        if (target == PaymentStatus.FULLY_REFUNDED
                && (payment.getStatus() == PaymentStatus.CAPTURED
                        || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED)) {
            // Refund rows are the truth about money we sent back, and refunded_amount
            // moves with them. A provider saying "refunded" before our refund has
            // completed — a refund webhook, the sweep — must not set FULLY_REFUNDED
            // behind them: the payment would read as returned while the refund was
            // still open, or had failed (D-109). complete() sets it when it is.
            log.warn("Ignoring {} \"refunded\" for payment {}: it is {} and its refunds decide "
                    + "when it is fully refunded", source, payment.getId(), payment.getStatus());
            payments.save(payment);
            return payment;
        }

        if (payment.getStatus() == PaymentStatus.CREATED && payment.getCancelRequestedAt() != null
                && target == PaymentStatus.CAPTURED) {
            // The order was cancelled and the payer's money arrived already taken.
            // Nothing else takes a CREATED payment to CAPTURED, so it goes the one
            // way a cancelled order's money goes: held here for a moment, then handed
            // to the cancellation job, which asks the provider what it really is.
            target = PaymentStatus.AUTHORIZED;
        }

        if (target == PaymentStatus.RELEASED && payment.getStatus() == PaymentStatus.AUTHORIZED) {
            // The provider gave an uncaptured authorisation back to the payer: its
            // hold ran out (D-109). Until now this was read as REFUNDED, which our
            // states do not allow from AUTHORIZED, so it was logged as out of order
            // and the payment stayed AUTHORIZED for good — an order still on offer
            // to be dispatched against money that was gone.
            if (payment.getCancelRequestedAt() == null) {
                log.error("Order {} can no longer be paid for: the hold on payment {} lapsed at the provider",
                        payment.getSupplierOrderId(), payment.getId());
            } else {
                log.warn("Provider returned payment {} itself; its order was cancelled", payment.getId());
            }
            ledger.release(payment, ReleaseReason.PROVIDER_AUTO_REFUND,
                    "The provider returned the authorisation unused", source);
            return payment;
        }

        if (target == payment.getStatus()) {
            payments.save(payment);
            return payment;
        }

        if (!payment.getStatus().canTransitionTo(target)) {
            // A late authorisation event arriving after capture, typically. The
            // provider is describing something we already moved past; recording
            // that we heard it is enough (doc 03 §6, doc 46).
            log.info("Ignoring out-of-order {} event for payment {}: {} cannot become {}",
                    source, payment.getId(), payment.getStatus(), target);
            payments.save(payment);
            return payment;
        }

        var previous = payment.getStatus();
        payment.setStatus(target);

        switch (target) {
            case AUTHORIZED -> {
                payment.setAuthorizedAt(Instant.now());
                payment.setAuthorizedAmount(providerPayment.authorizedAmount());
                record(payment, "AUTHORIZE", providerPayment.authorizedAmount(),
                        "SUCCESS", providerPayment.providerPaymentId(), null, null);
                outbox.publish("PaymentAuthorized", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "amount", providerPayment.authorizedAmount().toPlainString()),
                        null);
            }
            case CAPTURED -> {
                payment.setCapturedAt(Instant.now());
                payment.setCapturedAmount(providerPayment.capturedAmount());
                record(payment, "CAPTURE", providerPayment.capturedAmount(),
                        "SUCCESS", providerPayment.providerPaymentId(), null, null);
                outbox.publish("PaymentCaptured", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "amount", providerPayment.capturedAmount().toPlainString()),
                        null);
            }
            case FAILED -> {
                payment.setFailureCode(providerPayment.failureCode());
                payment.setFailureReason(providerPayment.failureReason());
                outbox.publish("PaymentFailed", "PAYMENT", payment.getId(),
                        Map.of("supplierOrderId", payment.getSupplierOrderId(),
                                "reason", String.valueOf(providerPayment.failureCode())),
                        null);
            }
            default -> { }
        }

        payments.save(payment);
        auditService.record(null, null, "PAYMENT_" + target.name(), "PAYMENT",
                payment.getId(), previous.name(), target.name(), source, "PROVIDER");
        // One line per step, the same shape everywhere, so a search for one
        // payment reads as its sequence (D-100). The trace ids come from the
        // caller's scope; the payment id is repeated for lines read without one.
        log.info("Payment {} {} → {} via {} (provider payment {})", payment.getId(),
                previous, target, source, providerPayment.providerPaymentId());

        if (target == PaymentStatus.AUTHORIZED && payment.getCancelRequestedAt() != null) {
            // Money arrived for an order already cancelled: a payment finished after
            // the restaurant backed out of a draft. Same transaction, so no reader
            // ever sees it AUTHORIZED and fundsSecured, and the order is never
            // released to a supplier. The job returns it (D-109).
            payment.setStatus(PaymentStatus.CANCEL_PENDING);
            payments.save(payment);
            auditService.record(null, null, "PAYMENT_CANCEL_PENDING", "PAYMENT", payment.getId(),
                    PaymentStatus.AUTHORIZED.name(), PaymentStatus.CANCEL_PENDING.name(),
                    "Money arrived after the order was cancelled (" + source + ")", "SYSTEM");
            log.warn("Payment {} AUTHORIZED → CANCEL_PENDING: money arrived via {} after order {} was cancelled",
                    payment.getId(), source, payment.getSupplierOrderId());
        }

        return payment;
    }

    /** Whether this attempt holds or has taken the money, rather than being declined or unfinished. */
    private static boolean carriesMoney(PaymentProvider.ProviderPayment providerPayment) {
        return providerPayment.status() == PaymentProvider.ProviderPaymentStatus.AUTHORIZED
                || providerPayment.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED;
    }

    /** Remember how the payer paid, once the attempt holding the money says. */
    private void recordMethod(Payment payment, PaymentProvider.ProviderPayment providerPayment) {
        if (providerPayment.method() == null) {
            return;
        }
        payment.setProviderMethod(providerPayment.method());
        payment.setProviderMethodDetail(providerPayment.methodDetail());
    }

    /**
     * Whether this FAILED payment is one an intent expired on, which money can still
     * reach. Other failures — a declined capture, a refused authorisation — are ends.
     */
    private static boolean reopenable(Payment payment) {
        return payment.getStatus() == PaymentStatus.FAILED
                && "INTENT_EXPIRED".equals(payment.getFailureCode());
    }

    /**
     * Money has reached an intent we had given up on (D-109). {@code FAILED} stays
     * terminal in the enum, so this is an explicit move rather than a generic
     * transition: it checks what expired it and that the money is ours, and sends
     * the payment to the cancellation job, which returns it. Until now the money
     * sat at the provider until its own expiry gave it back.
     */
    private Payment reopenForReturn(Payment payment, PaymentProvider.ProviderPayment providerPayment,
                                    String source) {
        if (providerPayment.authorizedAmount().compareTo(payment.getAuthorizedAmount()) != 0) {
            // completes() checks the amount only for a payment still waiting for its
            // first money. Not ours to take or return on a guess: left for a person.
            log.error("Ignoring {} money of {} on expired payment {}: it was for {}",
                    source, providerPayment.authorizedAmount().toPlainString(), payment.getId(),
                    payment.getAuthorizedAmount().toPlainString());
            payments.save(payment);
            return payment;
        }
        var previous = payment.getStatus();
        payment.setStatus(PaymentStatus.CANCEL_PENDING);
        payment.setAuthorizedAt(Instant.now());
        payment.setFailureCode(null);
        payment.setFailureReason(null);
        if (payment.getCancelRequestedAt() == null) {
            payment.setCancelRequestedAt(Instant.now());
        }
        payments.save(payment);
        record(payment, "AUTHORIZE", providerPayment.authorizedAmount(),
                "SUCCESS", providerPayment.providerPaymentId(), null, null);
        auditService.record(null, null, "PAYMENT_CANCEL_PENDING", "PAYMENT", payment.getId(),
                previous.name(), PaymentStatus.CANCEL_PENDING.name(),
                "Money arrived after the intent expired (" + source + ")", "SYSTEM");
        log.error("Payment {} {} → CANCEL_PENDING: money arrived via {} {} after its intent expired; "
                + "returning it", payment.getId(), previous, source, providerPayment.status());
        return payment;
    }

    // ── Capture and release ──────────────────────────────────────────────

    /**
     * Mark a payment for capture. Does not call the provider.
     *
     * <p>Runs inside the transaction that records the supplier's acceptance, so it
     * must not do network I/O: a slow gateway would hold that transaction open, and
     * a gateway failure would roll back an acceptance that really happened. The
     * capture itself is performed by {@link PaymentCaptureJob} afterwards and can be
     * retried until it succeeds — which is what {@code CAPTURE_PENDING} is for.
     */
    @Transactional
    public void markForCapture(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Locked: a webhook or the sweep writing the same payment between our read
        // and our save made this throw on the version check — the 500 D-099 was
        // meant to have removed, one step later (D-101).
        var payment = payments.lockBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        if (!payment.getStatus().canTransitionTo(PaymentStatus.CAPTURE_PENDING)) {
            log.warn("Cannot mark payment {} for capture from {} (order {})",
                    payment.getId(), payment.getStatus(), supplierOrderId);
            return;
        }

        // The accepted amount, never the authorised one (doc 01 §14). Clamped
        // because capturing more than was held would be rejected by the provider
        // and, if it were not, would be a charge nobody agreed to.
        BigDecimal toCapture = acceptedAmount.min(payment.getAuthorizedAmount());

        payment.setStatus(PaymentStatus.CAPTURE_PENDING);
        payment.setCapturedAmount(toCapture);
        payments.save(payment);

        auditService.record(null, null, "PAYMENT_CAPTURE_PENDING", "PAYMENT", payment.getId(),
                PaymentStatus.AUTHORIZED.name(), PaymentStatus.CAPTURE_PENDING.name(),
                "Accepted " + toCapture.toPlainString(), "SYSTEM");
        log.info("Payment {} AUTHORIZED → CAPTURE_PENDING for {}",
                payment.getId(), toCapture.toPlainString());
    }

    /**
     * Perform a pending capture against the provider. Called by the capture job.
     *
     * <p>Not {@code @Transactional}: the provider call happens with no transaction
     * open, and the outcome is written in a short one on a fresh read.
     *
     * <p><b>A retryable failure leaves the payment {@code CAPTURE_PENDING}.</b> It
     * used to return it to {@code AUTHORIZED} "so the job tries again" — but the
     * job only reads {@code CAPTURE_PENDING}, and nothing else marks a confirmed
     * order for capture a second time. One gateway blip stranded the payment until
     * the authorisation lapsed, with the order confirmed and the supplier never
     * paid (D-099). Staying pending is what makes the next run retry, with the
     * same key.
     *
     * <p><b>So does a rate limit or a refusal of our keys (429, 401, 403).</b> They say
     * something about the call, not about the payment, and this is the one place where
     * getting that wrong is unrecoverable: the payment used to go to FAILED, nothing reads
     * a FAILED payment again, the authorisation lapsed back to the payer and the goods had
     * already left. A busy minute at Razorpay or a key being rotated must cost a delay,
     * never the order's money. The run is told, so it can stop instead of adding load.
     *
     * @return what the job should do next: carry on, or stop because the provider is not
     *         accepting our calls
     */
    public CaptureOutcome performCapture(Long paymentId) {
        var pending = load(paymentId);
        if (pending.getStatus() != PaymentStatus.CAPTURE_PENDING) {
            return CaptureOutcome.DONE;
        }

        PaymentProvider.ProviderPayment captured = null;
        PaymentProviderException failure = null;
        try {
            captured = provider.capture(pending.getProviderPaymentId(),
                    pending.getCapturedAmount(),
                    // Stable per payment: a retry after a network failure reaches
                    // the same capture on the provider's side rather than a second one.
                    "capture-payment-" + paymentId);
        } catch (PaymentProviderException ex) {
            failure = ex;
        }

        final var result = captured;
        final var error = failure;
        final CaptureOutcome[] outcome = {CaptureOutcome.DONE};
        txTemplate.executeWithoutResult(status -> {
            var payment = payments.lockById(paymentId).orElseThrow();
            if (payment.getStatus() != PaymentStatus.CAPTURE_PENDING) {
                return;
            }

            if (error == null) {
                applyProviderState(payment, result, "CAPTURE_JOB");

                // The unaccepted remainder was never taken, so it is released rather
                // than refunded — faster for the customer, and not a reversal on their
                // statement.
                BigDecimal remainder = payment.getAuthorizedAmount()
                        .subtract(payment.getCapturedAmount());
                if (remainder.signum() > 0) {
                    payment.setReleasedAmount(remainder);
                    record(payment, "RELEASE", remainder, "SUCCESS",
                            payment.getProviderPaymentId(), null, null);
                    payments.save(payment);
                }
                if (payment.getCancelRequestedAt() != null
                        && payment.getStatus() == PaymentStatus.CAPTURED) {
                    // Cannot happen: an order is not cancellable once ready, which is
                    // where capture is marked. If it ever did, the order is cancelled
                    // and the money has just been taken for it, so it goes straight
                    // back — the one thing worse than that is a refund nobody raises.
                    log.error("Payment {} was captured for order {}, which was cancelled; refunding it",
                            payment.getId(), payment.getSupplierOrderId());
                    ledger.requestRefund(payment, "CAPTURE_JOB");
                }
                return;
            }

            record(payment, "CAPTURE", payment.getCapturedAmount(), "FAILED",
                    null, error.providerCode(), error.getMessage());

            if (error.isRetryable() || error.isRateLimited() || error.isCredentialsRefused()) {
                // Still CAPTURE_PENDING, so the next run tries again. Saved to move
                // updated_at, which keeps the oldest-first ordering fair.
                payment.setReconciledAt(Instant.now());
                payments.save(payment);
                log.warn("Capture of payment {} failed, will retry: {}", paymentId, error.getMessage());
                if (error.isRateLimited()) {
                    outcome[0] = CaptureOutcome.RATE_LIMITED;
                } else if (error.isCredentialsRefused()) {
                    outcome[0] = CaptureOutcome.CREDENTIALS_REFUSED;
                }
            } else {
                fail(payment, error.providerCode(), error.getMessage());
                // The goods have gone (the order is ready), the payment is FAILED and nothing reads
                // a FAILED payment again: the supplier is not paid unless a person acts. Once, as
                // it is never attempted again, and an ERROR for an alert, not the WARN in fail().
                log.error("Payment {} for order {}: capture refused, supplier not paid, a person must act "
                        + "(provider {}: {})", payment.getId(), payment.getSupplierOrderId(),
                        error.providerCode(), error.getMessage());
            }
        });
        return outcome[0];
    }

    /** What a capture attempt tells the job that ran it. */
    public enum CaptureOutcome {
        /** Handled, one way or another: the next payment can be tried. */
        DONE,
        /** The provider is limiting our calls: stop this run, the next one carries on. */
        RATE_LIMITED,
        /** The provider refuses our keys: stop this run, and someone must fix the keys. */
        CREDENTIALS_REFUSED
    }

    /**
     * The order was cancelled: decide what happens to its payment, and record it.
     * No provider call, ever (D-099, D-109).
     *
     * <p>Runs inside the transaction that cancelled the order, so the order is
     * CANCELLED and the payment says what is owed, atomically. It used to fetch the
     * payment from Razorpay here — a fifteen-second read with the order transaction
     * open — and then mark it RELEASED whatever the answer, which for a UPI payment
     * was a lie: the money had left the payer's account and nothing gave it back.
     *
     * <p>What the payment becomes depends on whether the payer was debited:
     * <ul>
     *   <li><b>A card hold</b> is dropped: RELEASED, nothing taken, nothing to
     *       refund, the bank lets the hold go.</li>
     *   <li><b>Anything else, or a method we have not read</b>, is money already
     *       debited. It goes to CANCEL_PENDING, and the cancellation job captures
     *       and refunds it, asking the provider what it really is first. Choosing
     *       by "is a card" means a new or unknown method is returned, never left to
     *       lapse.</li>
     *   <li><b>Still waiting for its money</b> stays CREATED, marked cancelled: if
     *       the payer finishes paying, that money is sent straight back.</li>
     * </ul>
     * Money already taken is refunded by {@link RefundService#refundCancelled}, which
     * the caller has run first; here it only notes the cancellation.
     */
    @Transactional
    public void onOrderCancelled(Long supplierOrderId, String reason) {
        var payment = payments.lockBySupplierOrderId(supplierOrderId).orElse(null);
        if (payment == null) {
            return;
        }
        if (payment.getCancelRequestedAt() == null) {
            payment.setCancelRequestedAt(Instant.now());
        }

        switch (payment.getStatus()) {
            case CREATED -> {
                payments.save(payment);
                log.info("Payment {} CREATED: order {} cancelled before any money arrived; "
                        + "money that arrives later is returned", payment.getId(), supplierOrderId);
            }
            case AUTHORIZED -> {
                if ("card".equals(payment.getProviderMethod())) {
                    ledger.release(payment, ReleaseReason.CARD_HOLD_DROPPED, reason, "SYSTEM");
                } else {
                    payment.setStatus(PaymentStatus.CANCEL_PENDING);
                    payments.save(payment);
                    auditService.record(null, null, "PAYMENT_CANCEL_PENDING", "PAYMENT",
                            payment.getId(), PaymentStatus.AUTHORIZED.name(),
                            PaymentStatus.CANCEL_PENDING.name(), truncate(reason), "SYSTEM");
                    log.info("Payment {} AUTHORIZED → CANCEL_PENDING (order {} cancelled; method {})",
                            payment.getId(), supplierOrderId, payment.getProviderMethod());
                }
            }
            case CAPTURE_PENDING -> {
                payments.save(payment);
                // Not reachable: capture is marked at "ready", after which the order
                // cannot be cancelled. If it happens, the capture job returns the money.
                log.error("Cancel of order {} found payment {} CAPTURE_PENDING; it will be refunded "
                        + "once captured", supplierOrderId, payment.getId());
            }
            // A duplicate cancel, or money already handled: only the mark is written.
            case CANCEL_PENDING, RELEASED, FAILED, CAPTURED, PARTIALLY_REFUNDED, FULLY_REFUNDED ->
                    payments.save(payment);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 480 ? text : text.substring(0, 480);
    }

    /**
     * End an intent nobody completed (D-101).
     *
     * <p>A declined attempt no longer fails a payment, so something has to: an
     * intent past the lookup window, asked once more and still without money,
     * becomes FAILED and its order is abandoned — the "payment incomplete" state
     * the app already shows. Without this, abandoned intents stayed CREATED for
     * ever and, oldest first, filled every reconciliation batch.
     *
     * @return true if this call expired it
     */
    @Transactional
    public boolean expireIntent(Long paymentId) {
        var payment = payments.lockById(paymentId).orElseThrow();
        if (payment.getStatus() != PaymentStatus.CREATED) {
            return false;
        }
        String reason = payment.getFailureReason() != null
                ? "No payment completed. Last attempt: " + payment.getFailureReason()
                : "No payment completed.";
        fail(payment, "INTENT_EXPIRED", reason);
        auditService.record(null, null, "PAYMENT_EXPIRED", "PAYMENT", payment.getId(),
                PaymentStatus.CREATED.name(), PaymentStatus.FAILED.name(), reason, "SYSTEM");
        return true;
    }

    // ── Reads and helpers ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Payment load(Long paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public Payment loadForOrder(Long supplierOrderId) {
        return payments.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("Payment", supplierOrderId));
    }

    private void fail(Payment payment, String code, String reason) {
        if (payment.getStatus().canTransitionTo(PaymentStatus.FAILED)) {
            log.warn("Payment {} {} → FAILED: {} {}", payment.getId(), payment.getStatus(), code, reason);
            payment.setStatus(PaymentStatus.FAILED);
        }
        payment.setFailureCode(code);
        payment.setFailureReason(reason);
        payments.save(payment);
    }

    void record(Payment payment, String type, BigDecimal amount, String status,
                String providerReference, String failureCode, String failureReason) {

        transactions.save(PaymentTransaction.builder()
                .paymentId(payment.getId())
                .transactionType(type)
                .amount(amount)
                .currency(payment.getCurrency())
                .status(status)
                .providerReference(providerReference)
                .failureCode(failureCode)
                .failureReason(failureReason)
                .build());
    }

    @Transactional
    public void transition(Payment payment, PaymentStatus target) {
        if (!payment.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This payment has already moved on.");
        }
        payment.setStatus(target);
        payments.save(payment);
    }
}
