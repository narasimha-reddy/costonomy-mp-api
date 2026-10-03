package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.payment.domain.RefundStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Putting a withdrawal part back in the wallet when the provider did not send it (D-110), and
 * every read of the provider's refunds that decides it.
 *
 * <p><b>The rule, in one line: the system puts money back only on proof that the payer was not
 * refunded.</b> The proof is three things together, and none of them is the provider's wording:
 * the provider refused the send definitely (a 4xx it answered, not a timeout); the provider's list
 * of the payment's refunds shows no refund of ours that moved money; and it shows no refund of
 * anyone else's either, and the payment's own {@code amount_refunded} is no more than our refunds
 * explain. Anything else (an ambiguous send, a payment someone refunded by hand, a payment the
 * provider does not know, a list or a payment that cannot be read) is never put back by the system;
 * it waits for a person, who can put it back with a fresh verification and a reason. What is
 * guaranteed is exactly this: no automatic re-credit while the provider shows the payer was, or may
 * have been, paid another way. It is not a guarantee about a refund the provider has not made yet,
 * which the late success watch looks for (see {@link #auditReversed}).
 *
 * <p><b>One reversal per refund, three ways.</b> The refund row is locked and its status
 * re-checked, so of any number of callers exactly one finds it REJECTED (or NEEDS_REVIEW) and
 * moves it to REVERSED; the wallet reference {@code withdrawal-reversal-{refundId}} is unique;
 * and the wallet is locked first. The credit goes through the wallet's atomic update like every
 * other balance change.
 *
 * <p><b>Lock order: wallet, then refund, then payment</b> (D-104). The provider is read with no
 * connection held (D-099).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalReversalService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final RefundWalletPort wallet;
    private final RefundService refundService;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;
    private final AlertThrottle alerts;

    /**
     * How long after the first send the provider's list is trusted to show a refund we made: it
     * may lag a just-created refund (unverified). A field, so a test can set it to nothing.
     */
    @Value("${costonomy.mp.refunds.reversal-min-age:PT2M}")
    private Duration minAge = Duration.ofMinutes(2);

    /** A refund REJECTED for longer than this is an error line for someone to see (D-110). */
    static final Duration REJECTED_ALERT_AFTER = Duration.ofMinutes(30);

    /** What one run decided. */
    public enum Result {
        /** Not in a state this can act on: someone else already did, or it is not this kind of refund. */
        NOT_APPLICABLE,
        /** Waiting: too soon after the send, or the provider could not be read. Still REJECTED; the next run tries again. */
        WAITING,
        /** The provider showed nothing of ours; recorded, and nothing else done (a verification only). */
        NONE_OF_OURS,
        /** The provider already holds our refund: adopted; nothing is put back. */
        ADOPTED,
        /** The provider does not know the payment. Nothing is decided: see {@link #reviewUnknown}. */
        UNKNOWN_AT_PROVIDER,
        /**
         * A person's read: the provider does not know the payment (its list answers nothing, its payment read says
         * unknown) while its keys were just proven to work. Recorded as such, which is not "none of ours": it is
         * what a re-credit of this kind of part stands on, with more gates than any other (see {@link #run}).
         * Returned without recording anything to a re-credit that was not approved as this kind.
         */
        UNKNOWN_PAYMENT,
        /** Put back in the wallet, once. */
        REVERSED,
        /**
         * The provider refused the part as "unknown payment", but its own reads just answered about that payment:
         * the refusal was wrong (a mix-up of keys or mode, since fixed). Not put back and not blocked: the refund
         * goes back to the refund job to be sent again, with the look at the provider's list that comes first.
         */
        REQUEUED,
        /**
         * A person asked, and the provider shows the payer was, or may have been, refunded another way (a refund
         * that is not ours, or more refunded than ours explain). Recorded; nothing is put back.
         */
        FOREIGN_REFUND,
        /**
         * A person asked, and the payer has already been refunded at the provider for at least what this part would add
         * (see {@link #payerAlreadyCovered}), whatever was recorded as "not this part's". Recorded as
         * {@code FOREIGN_REFUND}; nothing is put back.
         */
        ALREADY_COVERED,
        /** Not something the system may decide: left for a person. */
        NEEDS_REVIEW
    }

    /** What the caller wants done with what the read shows. */
    public enum Intent {
        /** The system, on a REJECTED refund: adopt ours, or put a withdrawal part back where it is safe to. */
        SYSTEM,
        /** Read and record only (adopt ours if found). Puts nothing back. */
        VERIFY,
        /** A person puts a withdrawal part back: only if the read shows none of ours. */
        RECREDIT
    }

    /** Why the system sent a refund to a person: what {@code refund.review_cause} holds (D-110). */
    public static final String REFUNDED_ANOTHER_WAY = "REFUNDED_ANOTHER_WAY";
    public static final String CONTRADICTED_REFUSAL = "CONTRADICTED_REFUSAL";
    public static final String PAYMENT_GONE = "PAYMENT_GONE";
    public static final String NOT_A_WITHDRAWAL = "NOT_A_WITHDRAWAL";
    /**
     * Recorded by two people, not by the system: the provider refunds named in {@code review_ref} (comma-separated)
     * are not this part's. The proof that the payer was not refunded another way then leaves out exactly those,
     * and their amounts, and nothing else (see {@link RefundOperations#foreignRefundNotThisPart}).
     */
    public static final String FOREIGN_NOT_THIS_PART = "FOREIGN_NOT_THIS_PART";
    /**
     * Set when a person closed the part as completed by a refund made outside Mandi that covers it (and maybe other
     * parts): {@code review_ref} is that provider refund's id. Not {@code provider_refund_id}, which is unique and a
     * one-to-one claim; this is a claim of an amount, counted against the foreign refund's own.
     */
    public static final String COMPLETED_BY_OTHER_REFUND = "COMPLETED_BY_OTHER_REFUND";
    /**
     * Set when two people closed a part that carries a provider refund id on a payment the provider does not know as
     * processed on another account (see {@link RefundOperations#markCompleted(Long, Long, String, String, boolean, String, boolean)});
     * {@code review_ref} is that refund's id.
     */
    public static final String COMPLETED_OTHER_ACCOUNT = "COMPLETED_OTHER_ACCOUNT";
    /** {@code verified_result} of a part on a payment the provider does not know while its keys work (16 characters at most). */
    public static final String UNKNOWN_PAYMENT_VERIFIED = "UNKNOWN_PAYMENT";
    /** How many payments made later are read, at most, to prove that the provider's keys work. */
    static final int KEY_PROOF_READS = 5;
    /** How many foreign refunds one part can have recorded as not its own ({@code review_ref} is 200 characters). */
    static final int MAX_RECORDED_NOT_THIS_PART = 6;

    /** The provider refunds recorded as not this refund's (and no other refund's): its own record, nothing shared. */
    static Set<String> recordedNotThisPart(Refund refund) {
        if (!FOREIGN_NOT_THIS_PART.equals(refund.getReviewCause()) || refund.getReviewRef() == null) {
            return Set.of();
        }
        var ids = new java.util.LinkedHashSet<String>();
        for (String id : refund.getReviewRef().split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private static final Set<RefundStatus> SYSTEM_STATES = Set.of(RefundStatus.REJECTED);
    private static final Set<RefundStatus> PERSON_STATES = Set.of(RefundStatus.REJECTED, RefundStatus.NEEDS_REVIEW);

    /** The system's pass over a REJECTED refund. */
    public Result verifyAndReverse(Long refundId) {
        return run(refundId, null, Intent.SYSTEM);
    }

    /**
     * Read the provider's refunds for this refund and act on what they show.
     *
     * @param actorId the operator, or null when the system runs it
     */
    public Result run(Long refundId, Long actorId, Intent intent) {
        return run(refundId, actorId, intent, false);
    }

    /**
     * @param unknownPaymentApproved for a re-credit only: the caller has met every gate of a re-credit on a payment
     *                               the provider does not know (see {@link RefundOperations#recredit}); without it a
     *                               re-credit that finds the payment unknown puts nothing back
     */
    public Result run(Long refundId, Long actorId, Intent intent, boolean unknownPaymentApproved) {
        return run(refundId, actorId, intent, unknownPaymentApproved, null);
    }

    /**
     * @param reversedAudit the operator's own audit record of a put-back ({@code REFUND_OPS_RECREDIT}), written in the
     *                      same transaction as the reversal when, and only when, the part is put back, so that the record
     *                      and the money cannot part. Already fitted to the audit column by the caller; null for none
     */
    public Result run(Long refundId, Long actorId, Intent intent, boolean unknownPaymentApproved, String reversedAudit) {
        return execute(refundId, actorId, intent, unknownPaymentApproved, reversedAudit, new String[1]);
    }

    /** What a read found, and the payment that proved the provider's keys when it was a person's read of an unknown payment. */
    public record Read(Result result, String keysProvedBy) {
    }

    /** {@link #run(Long, Long, Intent, boolean, String)}, also saying which payment proved the keys (null if none was needed). */
    public Read runWithProof(Long refundId, Long actorId, Intent intent, boolean unknownPaymentApproved, String reversedAudit) {
        var proof = new String[1];
        var result = execute(refundId, actorId, intent, unknownPaymentApproved, reversedAudit, proof);
        return new Read(result, proof[0]);
    }

    private Result execute(Long refundId, Long actorId, Intent intent, boolean unknownPaymentApproved, String reversedAudit,
                           String[] provedOut) {
        var refund = refunds.findById(refundId).orElse(null);
        Set<RefundStatus> allowed = intent == Intent.SYSTEM ? SYSTEM_STATES : PERSON_STATES;
        if (refund == null || !allowed.contains(refund.getStatus())) {
            return Result.NOT_APPLICABLE;
        }
        if (intent == Intent.RECREDIT && refund.getReason() != RefundReason.WALLET_WITHDRAWAL) {
            // Only a withdrawal part has a wallet debit to put back. A cancellation refund's money
            // never came from a wallet, so it is never re-credited to one this way (see to-wallet).
            return Result.NOT_APPLICABLE;
        }
        if (intent == Intent.SYSTEM && refund.getSentAt() != null
                && Instant.now().isBefore(refund.getSentAt().plus(minAge))) {
            return Result.WAITING;
        }

        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        List<PaymentProvider.ProviderRefundEntry> listed;
        boolean providerKnowsPayment = true;
        // A person's read that finds the provider does not know the payment: by its list, or by an empty list and a
        // payment read that says unknown (Razorpay's list of an unknown payment answers 200 with nothing).
        boolean unknownPayment = false;
        try {
            listed = provider.listRefunds(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            if (ex.isNotFound() && intent == Intent.SYSTEM) {
                // "The provider does not know this payment" proves nothing about an earlier send: it is what
                // the keys of another account say. Nothing is decided here; the caller holds it back until it
                // knows whether the keys work (a configuration fault) or this payment is really gone.
                return Result.UNKNOWN_AT_PROVIDER;
            }
            if (!ex.isNotFound()) {
                // Not read, so nothing is decided. The refund stays as it is and the next run tries again.
                if (alerts.due("reversal-unreadable-" + refundId, Instant.now(), Duration.ofMinutes(15))) {
                    log.warn("Could not read the provider's refunds for refund {} of payment {}: {} {}",
                            refundId, payment.getId(), ex.providerCode(), ex.getMessage());
                }
                if (intent != Intent.SYSTEM) {
                    throw ex;
                }
                return Result.WAITING;
            }
            // A person acting on a payment the provider does not know says so themselves (a note, and the
            // confirmation that nothing was sent): nothing of ours can be listed there.
            listed = List.of();
            providerKnowsPayment = false;
            unknownPayment = true;
        }

        // What the payment itself says has gone back: read after the list, so a refund made between the two shows in
        // one of them. For the system's decision on a withdrawal part, and for every person's verification and
        // re-credit, which must not say "none of ours" of a payment the payer was refunded on another way (D-110).
        PaymentProvider.ProviderPaymentFacts facts = null;
        // (A person's read that already finds a refund of ours adopts it, which needs nothing more: a payment read
        // that then fails must not hold up the adoption.)
        Instant legacyOf = refund.getSentAt() == null && refund.getAttempts() > 0 ? refund.getCreatedAt() : null;
        boolean needsFacts = intent == Intent.SYSTEM
                ? refund.getReason() == RefundReason.WALLET_WITHDRAWAL
                : !unknownPayment && RefundMatching.ours(listed, refund, legacyOf).isEmpty();
        if (needsFacts) {
            try {
                facts = provider.inspect(payment.getProviderPaymentId());
            } catch (PaymentProviderException ex) {
                if (intent != Intent.SYSTEM) {
                    if (ex.isNotFound() && listed.isEmpty()) {
                        // Nothing listed and the payment unknown: the payment is not there for this account's keys.
                        unknownPayment = true;
                        providerKnowsPayment = false;
                    } else {
                        throw ex;
                    }
                } else if (ex.isNotFound()) {
                    return Result.UNKNOWN_AT_PROVIDER;
                } else {
                    log.warn("Could not read payment {} at the provider to tell whether it was refunded: {}",
                            payment.getId(), ex.getMessage());
                    return Result.WAITING;
                }
            }
        }

        String keysProvedBy = null;
        if (unknownPayment) {
            if (actorId == null) {
                // The system's own hourly read of a refund in review decides nothing about a payment the provider
                // does not know, and records nothing: a person does, after proving the keys.
                return Result.UNKNOWN_AT_PROVIDER;
            }
            if (intent == Intent.RECREDIT && !unknownPaymentApproved) {
                // The verification this rests on said the provider knew the payment, and it no longer does: none of
                // the gates of this kind of re-credit was met. Nothing is put back; verify again.
                return Result.UNKNOWN_PAYMENT;
            }
            // Proof that the provider's keys work right now, before anything is recorded or put back (see the method).
            keysProvedBy = proveKeysWork(payment);
            provedOut[0] = keysProvedBy;
        }

        final var readList = listed;
        final var readFacts = facts;
        final boolean knownToProvider = providerKnowsPayment;
        final boolean unknownToProvider = unknownPayment;
        final String provedBy = keysProvedBy;
        final var outletId = payment.getOutletId();
        final Result[] result = {Result.NOT_APPLICABLE};
        txTemplate.executeWithoutResult(status -> {
            // Wallet first, and only for a refund that has a wallet behind it.
            if (refund.getReason() == RefundReason.WALLET_WITHDRAWAL) {
                wallet.lock(outletId);
            }
            var locked = refunds.lockById(refundId).orElseThrow();
            if (!allowed.contains(locked.getStatus())) {
                return;
            }
            Instant legacySince = locked.getSentAt() == null && locked.getAttempts() > 0 ? locked.getCreatedAt() : null;
            var ours = RefundMatching.ours(readList, locked, legacySince);
            if (ours.isPresent()) {
                result[0] = refundService.applyAdoption(locked, ours.get()) ? Result.ADOPTED : Result.NEEDS_REVIEW;
                return;
            }

            Instant now = Instant.now();
            if (intent != Intent.SYSTEM) {
                // The same proof the system uses, whoever asks: a person verifying or putting money back is told
                // "none of ours" only when the provider shows no other refund and no more refunded than ours
                // explain. Otherwise the payer may already have been paid, and the money is not put back.
                var foreign = refundedAnotherWay(locked, readList, readFacts, false);
                if (foreign != null || (intent == Intent.RECREDIT
                        && REFUNDED_ANOTHER_WAY.equals(locked.getReviewCause()))) {
                    refunds.markVerified(refundId, now, "FOREIGN_REFUND");
                    result[0] = foreign != null && foreign.covered() ? Result.ALREADY_COVERED : Result.FOREIGN_REFUND;
                    return;
                }
            }
            if (unknownToProvider) {
                // Only for a person (the system returned above). Recorded with its own result, never "none of ours".
                auditService.record(actorId, null, "REFUND_UNKNOWN_PAYMENT_PROOF", "REFUND", refundId,
                        locked.getStatus().name(), UNKNOWN_PAYMENT_VERIFIED,
                        "the provider does not know payment " + locked.getPaymentId() + " while it answers about "
                                + provedBy, "ADMIN");
                if (intent == Intent.VERIFY) {
                    refunds.markVerified(refundId, now, UNKNOWN_PAYMENT_VERIFIED);
                    result[0] = Result.UNKNOWN_PAYMENT;
                    return;
                }
                if (!UNKNOWN_PAYMENT_VERIFIED.equals(locked.getVerifiedResult())) {
                    // Checked again under the lock: what this re-credit stands on is not what is recorded now.
                    result[0] = Result.UNKNOWN_PAYMENT;
                    return;
                }
                if (locked.getProviderRefundId() != null) {
                    // A refund id was issued for this part: it was sent through keys that knew the payment. Not closed by
                    // "unknown payment" (see RefundOperations): checked again under the lock.
                    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "This part carries provider refund " + locked.getProviderRefundId()
                                    + " and is not put back on an unknown payment. Check that refund on the account it was made on: "
                                    + "if it was processed there it is closed with mark-completed (two people, evidence); "
                                    + "if not, it needs engineering.");
                }
                reverse(locked, actorId, now, false, UNKNOWN_PAYMENT_VERIFIED);
                if (reversedAudit != null) {
                    auditService.record(actorId, null, "REFUND_OPS_RECREDIT", "REFUND", refundId, "NEEDS_REVIEW",
                            "REVERSED", reversedAudit, "ADMIN");
                }
                result[0] = Result.REVERSED;
                return;
            }
            if (intent == Intent.VERIFY) {
                // Only noted, by a statement that leaves the row's own clock and version alone: a
                // check must neither race a decision nor push back the end of the week it is watched for.
                refunds.markVerified(refundId, now, "NONE_OF_OURS");
                result[0] = Result.NONE_OF_OURS;
                return;
            }
            // The system's own read that ends in review is not an operator's verification: a person
            // who acts on the refund reads the provider themselves (D-110), so nothing is recorded here.

            if (intent == Intent.SYSTEM) {
                if (locked.getReason() != RefundReason.WALLET_WITHDRAWAL) {
                    // A cancellation refund's money never came from a wallet, so nothing here credits one.
                    toReview(locked, NOT_A_WITHDRAWAL, null,
                            "a cancellation refund the provider refused is never credited to a wallet by the system");
                    result[0] = Result.NEEDS_REVIEW;
                    return;
                }
                var refundedAnotherWay = refundedAnotherWay(locked, readList, readFacts, false);
                if (refundedAnotherWay != null) {
                    // Whatever the provider's words were: the payer has been, or may have been, paid by other
                    // means, and putting the part back would pay the restaurant twice. Only a person can say, and
                    // the reason is kept on the refund: a person who verifies it later reads the same records, and
                    // must not be able to put it back on a "none of ours" that ignores what the system saw.
                    toReview(locked, REFUNDED_ANOTHER_WAY, refundedAnotherWay.providerRefundId(),
                            refundedAnotherWay.why());
                    boolean refundedInFull = readFacts.amountRefunded() != null && readFacts.amount() != null
                            && readFacts.amountRefunded().compareTo(readFacts.amount()) >= 0;
                    if (refundedInFull && payments.block(locked.getPaymentId(), now, "REFUNDED_ELSEWHERE") == 1) {
                        blockedAudit(locked.getPaymentId(), "REFUNDED_ELSEWHERE", null);
                    }
                    result[0] = Result.NEEDS_REVIEW;
                    return;
                }
                if (locked.getFailureKind() == ProviderFailureKind.PAYMENT_UNKNOWN) {
                    // "The provider does not know this payment" and, in the same minutes, its list and its payment
                    // read both answered about it: the refusal came from somewhere else (the keys of another
                    // account, a mode mix-up) and is wrong. Putting the money back would undo a withdrawal that
                    // is fine, and blocking the source would take healthy money out of reach. So it is sent
                    // again, with the look at the list that comes first, and only so many times.
                    if (locked.getAttempts() < RefundService.MAX_ATTEMPTS) {
                        refundService.moveTo(locked, RefundStatus.REQUESTED);
                        locked.setSettleHeldAt(null);
                        refunds.save(locked);
                        auditService.record(null, null, "REFUND_REQUEUED", "REFUND", locked.getId(),
                                RefundStatus.REJECTED.name(), RefundStatus.REQUESTED.name(),
                                "the provider refused it as an unknown payment, then answered about that payment",
                                "SYSTEM");
                        log.warn("Refund {} was refused as an unknown payment, but the provider answers about payment {}: "
                                + "sent again, not put back", locked.getId(), locked.getPaymentId());
                        result[0] = Result.REQUEUED;
                    } else {
                        toReview(locked, CONTRADICTED_REFUSAL, null, "the provider keeps refusing it as an unknown "
                                + "payment, and keeps answering about that payment");
                        result[0] = Result.NEEDS_REVIEW;
                    }
                    return;
                }
                if (locked.getFailureKind() == ProviderFailureKind.ALREADY_REFUNDED
                        && (readFacts.amountRefunded() == null || readFacts.amount() == null
                        || readFacts.amountRefunded().compareTo(readFacts.amount()) < 0)) {
                    // The provider's answer to the send said the payment was refunded in full; its own reads say
                    // it is not. One of them is stale, and it is not known which: a refund made a moment ago may
                    // not show yet. The write said money went back, the reads only say it did not: a person
                    // decides. A wrong review costs minutes; a wrong reversal pays the restaurant twice.
                    toReview(locked, CONTRADICTED_REFUSAL, null, "the provider said this payment was already refunded "
                            + "in full, and its own reads show no such refund");
                    result[0] = Result.NEEDS_REVIEW;
                    return;
                }
            }

            reverse(locked, actorId, now, knownToProvider, "NONE_OF_OURS");
            if (reversedAudit != null) {
                auditService.record(actorId, null, "REFUND_OPS_RECREDIT", "REFUND", refundId, "NEEDS_REVIEW",
                        "REVERSED", reversedAudit, "ADMIN");
            }
            result[0] = Result.REVERSED;
        });
        return result[0];
    }

    /**
     * The proof that the payer was not refunded another way, or why there is none (D-110). Null when the
     * provider's own records show nothing but refunds of ours: no other refund on the payment's list, and
     * an {@code amount_refunded} no larger than our refunds explain (a refund the list does not show yet
     * would make it larger). Independent of what the provider's refusal said.
     *
     * <p>Two things besides our own refunds are accounted for, and only what a person recorded: the provider
     * refunds named on this refund as not this part's (two people said so, see
     * {@link RefundOperations#foreignRefundNotThisPart}) <b>while each is worth less than this part and together they
     * are worth less than it</b> ({@link #exclusionsBelowPart}; a record that does not hold any more is no record),
     * with their amounts, and a foreign refund that closed parts of ours up to its whole amount. Any other refund, or
     * any amount beyond these, is still a reason.
     *
     * @param auditing the late success watch, which also counts our own refunds that carry a provider refund id
     *                 but are not on the list yet (see {@link #auditReversed}); a decision to put money back never does
     */
    private Payer refundedAnotherWay(Refund refund, List<PaymentProvider.ProviderRefundEntry> listed,
                                     PaymentProvider.ProviderPaymentFacts facts, boolean auditing) {
        var books = booksOf(refund, listed);
        var foreign = RefundMatching.foreign(listed, books.ourIds(), books.accountedFor());
        if (!foreign.isEmpty()) {
            return new Payer("the provider lists refund " + foreign.get(0).providerRefundId()
                    + " on this payment, which is not ours: the payer may already have been paid",
                    foreign.get(0).providerRefundId());
        }
        // No payment read only where a person says the provider does not know the payment at all.
        if (facts == null) {
            return null;
        }
        var explained = RefundMatching.explainedByOurs(listed, books.ourIds())
                .add(RefundMatching.amountOf(listed, books.accountedFor()));
        if (auditing) {
            explained = explained.add(books.ownNotListedYet());
        }
        if (facts.amountRefunded() != null && facts.amountRefunded().compareTo(explained) > 0) {
            return new Payer("the provider says " + facts.amountRefunded().toPlainString() + " of this payment has gone back, "
                    + "and our refunds explain " + explained.toPlainString(), null);
        }
        if (!auditing && payerAlreadyCovered(refund.getAmount(), listed, facts, books.ourIds(), books.excludedAmount())) {
            return new Payer("the payment has already been refunded to the payer for at least this amount ("
                    + providerRefundedTotal(listed, facts).subtract(books.excludedAmount()).toPlainString() + " of "
                    + facts.amount().toPlainString() + " has gone back, not counting refunds recorded as not this part's; "
                    + refund.getAmount().toPlainString() + " more would exceed the payment)", null, true);
        }
        return null;
    }

    /** What the provider shows as refunded on the payment, whoever made it: the larger of its own figure and its list. */
    static java.math.BigDecimal providerRefundedTotal(List<PaymentProvider.ProviderRefundEntry> listed,
                                                      PaymentProvider.ProviderPaymentFacts facts) {
        var figure = facts.amountRefunded() == null ? java.math.BigDecimal.ZERO : facts.amountRefunded();
        return figure.max(RefundMatching.listedTotal(listed));
    }

    /**
     * <b>Whether refunds recorded as "not this part's" can be left out of the proof (D-110).</b> Each of them must be worth
     * strictly less than the part, and all of them together strictly less than it: a refund of at least the part's amount
     * looks like compensation for exactly this part (a payer refunded 400 by hand for a part of 400 that was refused),
     * and putting the part back as well would pay it twice. Such a refund is closed the other way (mark-completed with
     * {@code confirmPayerRefundedInFull}, or returned-outside); it is never "not this part's". An id the provider does not
     * list (not failed) cannot be judged and fails the rule.
     */
    static boolean exclusionsBelowPart(java.math.BigDecimal part, List<PaymentProvider.ProviderRefundEntry> listed,
                                       java.util.Collection<String> providerRefundIds) {
        var sum = java.math.BigDecimal.ZERO;
        for (String id : providerRefundIds) {
            var entry = listed.stream().filter(e -> id.equals(e.providerRefundId())).findFirst();
            if (entry.isEmpty() || entry.get().amount() == null) {
                return false;
            }
            if (entry.get().status() == PaymentProvider.ProviderRefundStatus.FAILED) {
                // A refund that failed at the provider moved no money: it counts as nothing (and is refused as a NEW
                // exclusion by describeListedNotOurs), so that one failure does not void the record of the others.
                continue;
            }
            // Redundant with the sum below for amounts that are not negative; kept as the stated rule, which also
            // decides which message is shown (RefundOperations.exclusionsNotBelowPart).
            if (entry.get().amount().compareTo(part) >= 0) {
                return false;
            }
            sum = sum.add(entry.get().amount());
        }
        return sum.compareTo(part) < 0;
    }

    /**
     * The first refund the provider lists that moved or may move money, is not ours and is not accounted for (recorded as
     * not this part's and still valid, or claimed in full by parts of ours): what a refusal can name. Null if none.
     */
    String firstUnaccountedForeignRefund(Refund refund, List<PaymentProvider.ProviderRefundEntry> listed) {
        var books = booksOf(refund, listed);
        var foreign = RefundMatching.foreign(listed, books.ourIds(), books.accountedFor());
        return foreign.isEmpty() ? null : foreign.get(0).providerRefundId();
    }

    /**
     * <b>The room invariant (D-110).</b> Where any of what the provider shows refunded is neither ours nor recorded as
     * "not this part's" under {@link #exclusionsBelowPart} (a refund that closed parts of ours), a part is put back only
     * while everything refunded at the provider, counted whole, less the valid exclusions ({@code excluded}), plus this part
     * does not exceed the payment: otherwise the payer has already been paid at least this part's money and crediting the
     * wallet as well pays it twice. Refunds validly recorded as not this part's are left out: each is worth less than the
     * part and together they are, so they cannot be compensation for it, and the provider's own room is no measure of
     * what the wallet is owed (payment 1000 fully withdrawn as one part, then a goodwill refund of 10 by hand: the
     * provider cannot take the part any more, and the wallet still gets it back). A payment refunded only by refunds of
     * ours is unchanged (an over-refund of ours is refused by the provider and put back as before).
     */
    static boolean payerAlreadyCovered(java.math.BigDecimal part, List<PaymentProvider.ProviderRefundEntry> listed,
                                       PaymentProvider.ProviderPaymentFacts facts, Set<String> ourIds,
                                       java.math.BigDecimal excluded) {
        if (facts == null || facts.amount() == null) {
            return false;
        }
        var total = providerRefundedTotal(listed, facts).subtract(excluded);
        var notOurs = total.subtract(RefundMatching.explainedByOurs(listed, ourIds));
        return notOurs.signum() > 0 && total.add(part).compareTo(facts.amount()) > 0;
    }

    /**
     * What our own books say about the provider's refunds of one payment.
     *
     * @param ourIds         the provider refund ids our refunds carry
     * @param accountedFor   provider refunds that are not ours and are not a reason: recorded by a person as not this
     *                       refund's (and still valid, see {@link #exclusionsBelowPart}), or claimed by parts of ours
     *                       that they cover in full
     * @param excludedAmount what the valid recorded exclusions add up to: left out of the room invariant
     * @param ownNotListedYet what our refunds that carry a provider refund id and are PROCESSING or COMPLETED add up
     *                       to, where the provider's list does not show them (yet)
     */
    private record Books(Set<String> ourIds, Set<String> accountedFor, java.math.BigDecimal ownNotListedYet,
                         java.math.BigDecimal excludedAmount) {
    }

    private Books booksOf(Refund refund, List<PaymentProvider.ProviderRefundEntry> listed) {
        var ourIds = new java.util.HashSet<String>();
        var claimed = new java.util.HashMap<String, java.math.BigDecimal>();
        var listedIds = new java.util.HashSet<String>();
        listed.forEach(entry -> listedIds.add(entry.providerRefundId()));
        var notListed = java.math.BigDecimal.ZERO;
        for (var ours : refunds.findByPaymentIdOrderByCreatedAtDesc(refund.getPaymentId())) {
            if (ours.getProviderRefundId() != null) {
                ourIds.add(ours.getProviderRefundId());
                if ((ours.getStatus() == RefundStatus.PROCESSING || ours.getStatus() == RefundStatus.COMPLETED)
                        && !listedIds.contains(ours.getProviderRefundId())) {
                    notListed = notListed.add(ours.getAmount());
                }
            }
            if (COMPLETED_BY_OTHER_REFUND.equals(ours.getReviewCause()) && ours.getReviewRef() != null
                    && ours.getStatus() == RefundStatus.COMPLETED) {
                claimed.merge(ours.getReviewRef(), ours.getAmount(), java.math.BigDecimal::add);
            }
        }
        var excluded = java.math.BigDecimal.ZERO;
        var accounted = new java.util.HashSet<String>();
        // A refund that is ours (another part was closed against it, or it carries our receipt) is counted as ours and
        // must not be counted again as recorded-as-not-this-part's: removed before the record is judged and summed.
        var recorded = new java.util.LinkedHashSet<>(recordedNotThisPart(refund));
        recorded.removeAll(ourIds);
        recorded.removeIf(id -> listed.stream().anyMatch(e -> id.equals(e.providerRefundId()) && RefundMatching.isOurs(e, ourIds)));
        if (!recorded.isEmpty() && exclusionsBelowPart(refund.getAmount(), listed, recorded)) {
            accounted.addAll(recorded);
            excluded = RefundMatching.amountOf(listed, recorded);
        }
        for (var entry : listed) {
            var taken = claimed.get(entry.providerRefundId());
            if (taken != null && entry.amount() != null && taken.compareTo(entry.amount()) >= 0) {
                accounted.add(entry.providerRefundId());
            }
        }
        return new Books(ourIds, accounted, notListed, excluded);
    }

    /**
     * Proof that the provider's keys work right now, read from a payment the provider must know if they do (D-110).
     * What a person's "the provider does not know this payment" rests on: an unknown payment says nothing about the
     * payment when the keys are those of another account, or another mode, and then it would say the same of
     * every payment.
     *
     * <p>The proof is a successful read of a payment made <b>after</b> this one, captured, with the order it belongs
     * to as our books have it: the most recent first, and at most {@value #KEY_PROOF_READS}. Later, so that keys of an
     * older account (which would know the older payments and not the newer) cannot prove themselves by the very
     * payments they should not know; the newest payment of all therefore has no proof and is never decided this way.
     * A payment answered as unknown (or of another order) does not count and the next is tried. A read that fails
     * for another reason (the provider down, limiting us, refusing the keys) is not a proof either and stops it.
     *
     * @return the provider payment id that proved it, for the record
     * @throws BusinessException 503 when it could not be proven; nothing is recorded or put back
     */
    private String proveKeysWork(Payment payment) {
        var candidates = payments.findKeyProofCandidates(provider.name(), payment.getId(),
                org.springframework.data.domain.PageRequest.of(0, KEY_PROOF_READS));
        for (var candidate : candidates) {
            try {
                var facts = provider.inspect(candidate.getProviderPaymentId());
                boolean sameOrder = facts.providerOrderId() == null || candidate.getProviderOrderId() == null
                        || facts.providerOrderId().equals(candidate.getProviderOrderId());
                if (sameOrder && candidate.getProviderPaymentId().equals(facts.providerPaymentId())) {
                    return candidate.getProviderPaymentId();
                }
                log.warn("Payment {} is answered by the provider as another order's; not a proof of its keys",
                        candidate.getId());
            } catch (PaymentProviderException ex) {
                if (!ex.isNotFound()) {
                    throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                            "The provider's keys could not be proven to work just now (reading payment "
                                    + candidate.getId() + " failed: " + ex.getMessage() + "), so a payment it does "
                                    + "not know is not decided about. Try again in a few minutes.");
                }
            }
        }
        throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                "The provider does not know this payment, and its keys could not be proven to work: it answered about "
                        + "none of the " + candidates.size() + " later payment(s) read. If the keys, mode or base URL "
                        + "are wrong, every payment looks unknown. Check the configuration; nothing was recorded or put back.");
    }

    /**
     * A refund whose payment the provider does not know, and which the caller has decided is a payment that
     * is really gone and not the sign of another account's keys: sent to a person, never put back (D-110).
     */
    public void reviewUnknown(Long refundId) {
        txTemplate.executeWithoutResult(status -> {
            var locked = refunds.lockById(refundId).orElse(null);
            if (locked != null && locked.getStatus() == RefundStatus.REJECTED) {
                toReview(locked, PAYMENT_GONE, null, "the provider does not know this payment, so its refunds cannot be read");
            }
        });
    }

    /** Put the money back. In the caller's transaction, wallet already held, refund row already locked. */
    private void reverse(Refund refund, Long actorId, Instant now, boolean providerKnowsPayment, String verifiedResult) {
        var from = refund.getStatus();
        var kind = refund.getFailureKind();
        refundService.moveTo(refund, RefundStatus.REVERSED);
        refund.setReversedAt(now);
        refund.setReversedBy(actorId);
        refund.setVerifiedAt(now);
        refund.setVerifiedResult(verifiedResult);
        refund.setOpsAction(null);
        refund.setOpsActionBy(null);
        refund.setOpsActionAt(null);
        // Every write to the refund is flushed before the credit and the block: both are bulk updates
        // that clear the persistence context, and nothing loaded before them may be written after.
        refunds.saveAndFlush(refund);

        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        String blockReason = null;
        if (UNKNOWN_PAYMENT_VERIFIED.equals(verifiedResult)) {
            // Put back on a payment the provider does not know, whatever the part's own failure kind was (an ambiguous
            // send, no kind at all): the source cannot take a refund its keys can look up, so it is not drawn from again.
            blockReason = ProviderFailureKind.PAYMENT_UNKNOWN.name();
        } else if (kind == ProviderFailureKind.PAYMENT_UNKNOWN && providerKnowsPayment) {
            // A person put it back after reading the provider's list of this payment: the provider does know it,
            // so "unknown payment" was the wrong answer and nothing is wrong with the source.
            log.warn("Refund {} was refused as an unknown payment, but the provider lists refunds of payment {}: "
                    + "the source is not blocked", refund.getId(), refund.getPaymentId());
        } else if (kind != null && kind.isPermanent()) {
            blockReason = kind.name();
        } else if (kind == ProviderFailureKind.REJECTED_OTHER
                && refunds.countByPaymentIdAndStatusAndFailureKind(refund.getPaymentId(), RefundStatus.REVERSED,
                        ProviderFailureKind.REJECTED_OTHER) > 1) {
            // The same unexplained refusal has now put money back twice for this payment: stop asking it.
            blockReason = "OPS";
            log.error("Payment {} has refused two withdrawal parts for a reason we do not recognise ({}); "
                    + "blocked as a refund source until someone looks", payment.getId(), refund.getFailureReason());
        }
        if (blockReason != null && payments.block(refund.getPaymentId(), now, blockReason) == 1) {
            blockedAudit(refund.getPaymentId(), blockReason, actorId);
        }
        if (kind == ProviderFailureKind.NOT_CAPTURED) {
            log.error("Payment {} is CAPTURED in our books but the provider says it was not captured; "
                    + "our records and the provider's disagree", payment.getId());
        }

        // The credit last: the wallet is held, the reference is unique, and it clears the context.
        wallet.creditWithdrawalReversal(payment.getOutletId(), refund.getId(), refund.getAmount());

        auditService.record(actorId, null, "REFUND_REVERSED", "REFUND", refund.getId(),
                from.name(), RefundStatus.REVERSED.name(),
                (kind == null ? "" : kind.name() + " ") + Rupees.of(refund.getAmount())
                        + (refund.getFailureReason() == null ? "" : ": " + shorten(refund.getFailureReason(), 200)),
                actorId == null ? "SYSTEM" : "ADMIN");
        outbox.publish("WithdrawalReversed", "REFUND", refund.getId(),
                Map.of("refundId", refund.getId(), "paymentId", refund.getPaymentId(),
                        "outletId", payment.getOutletId(), "amount", Rupees.of(refund.getAmount()),
                        "failureKind", kind == null ? "" : kind.name()),
                actorId);
        // Error, for an alert to match: money promised out did not go out (D-110).
        log.error("Refund {} REVERSED: {} back in the wallet of outlet {} ({}); source payment {} {}",
                refund.getId(), Rupees.of(refund.getAmount()), payment.getOutletId(), kind,
                payment.getId(), blockReason == null ? "not blocked" : "blocked as " + blockReason);
    }

    private void toReview(Refund refund, String cause, String ref, String why) {
        var from = refund.getStatus();
        if (from != RefundStatus.NEEDS_REVIEW) {
            refundService.moveTo(refund, RefundStatus.NEEDS_REVIEW);
        }
        refund.setReviewCause(cause);
        refund.setReviewRef(ref);
        refund.setSettleHeldAt(null);
        refunds.save(refund);
        log.error("Refund {} → NEEDS_REVIEW after verification ({}): {}", refund.getId(), refund.getFailureKind(), why);
    }

    private void blockedAudit(Long paymentId, String reason, Long actorId) {
        auditService.record(actorId, null, "PAYMENT_REFUND_BLOCKED", "PAYMENT", paymentId,
                null, reason, "No more withdrawals are drawn from this payment", actorId == null ? "SYSTEM" : "ADMIN");
        log.warn("Payment {} can no longer be refunded at the provider: {}", paymentId, reason);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    // ── The late success watch ───────────────────────────────────────────

    /**
     * When a reversed refund is looked for at the provider, counted from the reversal: front-loaded, because
     * the money put back can be spent within minutes, then daily for fourteen days (D-110).
     */
    static final List<Duration> AUDIT_SCHEDULE;

    /** {@code verified_result} of a reversed refund whose audit found a possible foreign refund once, not yet confirmed. */
    static final String SUSPECT = "SUSPECT";

    /**
     * How long after a first suspicious audit the payment is read again before an alarm is raised: longer than the
     * provider's list is expected to lag a refund it has just made (unverified, V-8), short enough that a real double
     * credit is found within minutes of the second look.
     */
    static final Duration SUSPECT_RECHECK_AFTER = Duration.ofMinutes(5);

    static {
        var points = new java.util.ArrayList<Duration>();
        points.add(Duration.ofMinutes(10));
        points.add(Duration.ofHours(1));
        points.add(Duration.ofHours(6));
        for (int day = 1; day <= 14; day++) {
            points.add(Duration.ofDays(day));
        }
        AUDIT_SCHEDULE = List.copyOf(points);
    }

    /**
     * Whether a reversed refund is due to be looked for: the latest point of the schedule that has passed
     * has not been read since. Worked out from two persisted times, so it survives a restart and no read is
     * skipped or repeated because a process forgot where it was.
     */
    static boolean auditDue(Instant reversedAt, Instant verifiedAt, Instant now) {
        return auditDue(reversedAt, verifiedAt, null, now);
    }

    /**
     * {@link #auditDue(Instant, Instant, Instant)}, and a refund whose last read left a suspicion (the payer may
     * have been refunded another way, {@link #SUSPECT}) is due again once {@link #SUSPECT_RECHECK_AFTER} has passed,
     * whatever the schedule says: the second look must not wait for the next point of it, which may be a day away
     * or, after the last, never come.
     */
    static boolean auditDue(Instant reversedAt, Instant verifiedAt, String verifiedResult, Instant now) {
        if (SUSPECT.equals(verifiedResult) && verifiedAt != null) {
            return !verifiedAt.plus(SUSPECT_RECHECK_AFTER).isAfter(now);
        }
        Instant latest = null;
        for (var point : AUDIT_SCHEDULE) {
            Instant at = reversedAt.plus(point);
            if (!at.isAfter(now)) {
                latest = at;
            }
        }
        return latest != null && (verifiedAt == null || verifiedAt.isBefore(latest));
    }

    /**
     * A refund we put back has turned up at the provider after all, or the payer was refunded another way (D-110).
     * By design close to impossible: a definite refusal, and nothing but refunds of ours in the list. This is the check
     * that says so if it ever happens, at ten minutes, an hour and six hours after the reversal and then daily for
     * fourteen days (the watch ends at 14.5 days: nothing is detected after that, nor on a payment the keys in use cannot read). It looks for two things: a refund of ours (the send went through after all), and what the
     * reversal itself required, no refund that is not ours and no more refunded than ours explain (the payer was
     * refunded another way in a moment the reversal's own reads did not show yet). Either means the restaurant was
     * credited twice. It does <b>not</b> claw anything back: the balance may already be spent and cannot go
     * negative. On a hit the outlet's withdrawals are paused (spending stays allowed) until a person resolves
     * it, so that the double credit cannot also be withdrawn to a card while they look.
     *
     * @return true if a double credit was found
     * @throws PaymentProviderException only when the provider is limiting our calls or refusing our keys, so the
     *                                  caller can stop the run instead of asking again for every refund
     */
    public boolean auditReversed(Long refundId) {
        var refund = refunds.findById(refundId).orElse(null);
        if (refund == null || refund.getStatus() != RefundStatus.REVERSED) {
            return false;
        }
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        var listed = listForAudit(refund, payment);
        if (listed == null) {
            return false;
        }
        var ours = RefundMatching.ours(listed, refund, null);
        if (ours.isPresent()) {
            refunds.markVerified(refundId, Instant.now(), "OURS");
            String providerRefundId = ours.get().providerRefundId();
            log.error("CRITICAL refund {} was REVERSED but provider refund {} exists: restaurant credited twice "
                            + "({} in the wallet of outlet {}; the balance may already be spent). Withdrawals of "
                            + "outlet {} are paused until this is resolved",
                    refundId, providerRefundId, Rupees.of(refund.getAmount()), payment.getOutletId(),
                    payment.getOutletId());
            flagDoubleCredit(refund, payment, providerRefundId,
                    "provider refund " + providerRefundId + " " + ours.get().amount().toPlainString(),
                    "Refund " + refundId + " was reversed but exists at the provider");
            return true;
        }

        // Not ours. Was the payer refunded another way? One read is not enough to pause a restaurant's withdrawals: a
        // refund of ours made a moment ago for another part of this payment may show in the payment's own figure
        // before it shows in the list, and reads a few milliseconds apart would both see the same lag. So a mismatch is
        // first only noted (with when it was first seen), and the alarm is raised if it is still there when the payment
        // is read again, at least SUSPECT_RECHECK_AFTER later. Our own refunds that carry a provider refund id and are
        // not on the list yet are counted as explained, which is the lag itself.
        Payer payer;
        try {
            payer = payerRefundedAnotherWay(refund, payment, listed);
        } catch (Unreadable ex) {
            if (UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult())) {
                // A part put back on a payment the provider does not know: its payment cannot be read, and the list
                // shows none of ours. Marked as read so the schedule moves on and is not asked again every run; a
                // refund of ours turning up on the list is still found above, whatever the payment read says.
                refunds.markVerified(refundId, Instant.now(), UNKNOWN_PAYMENT_VERIFIED);
            }
            // Otherwise not read, so not marked as read either: the next run asks again.
            return false;
        }
        Instant now = Instant.now();
        if (payer == null) {
            refunds.markVerified(refundId, now, "NONE_OF_OURS");
            return false;
        }
        boolean seenBefore = SUSPECT.equals(refund.getVerifiedResult()) && refund.getVerifiedAt() != null;
        if (!seenBefore) {
            refunds.markVerified(refundId, now, SUSPECT);
            log.warn("Reversed refund {}: the payer may have been refunded another way ({}); read again in at least {}",
                    refundId, payer.why(), SUSPECT_RECHECK_AFTER);
            return false;
        }
        if (refund.getVerifiedAt().isAfter(now.minus(SUSPECT_RECHECK_AFTER))) {
            // Read again too soon after the first look to tell a lag from a refund: left as it is, first-seen time kept.
            return false;
        }
        refunds.markVerified(refundId, now, "FOREIGN_REFUND");
        log.error("CRITICAL refund {} was REVERSED but the payer was refunded another way ({}): restaurant credited "
                        + "twice ({} in the wallet of outlet {}; the balance may already be spent). Withdrawals of "
                        + "outlet {} are paused until this is resolved",
                refundId, payer.why(), Rupees.of(refund.getAmount()), payment.getOutletId(), payment.getOutletId());
        flagDoubleCredit(refund, payment, payer.providerRefundId(), payer.why(),
                "Refund " + refundId + " was reversed but the payer was refunded another way");
        return true;
    }

    /** A refund the payer got that is not ours, or more refunded than ours explain: what, and the provider's refund id if the list shows one. */
    private record Payer(String why, String providerRefundId, boolean covered) {
        Payer(String why, String providerRefundId) {
            this(why, providerRefundId, false);
        }
    }

    /** The provider could not be read just now for a reason that is not a back-off; the audit tries again next run. */
    private static final class Unreadable extends RuntimeException {
        Unreadable() {
            super("provider not readable", null, false, false);
        }
    }

    private List<PaymentProvider.ProviderRefundEntry> listForAudit(Refund refund, Payment payment) {
        Long refundId = refund.getId();
        try {
            return provider.listRefunds(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            if (ex.isRateLimited() || ex.isCredentialsRefused()) {
                throw ex;
            }
            if (ex.isNotFound() && UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult())) {
                // A part put back on a payment the provider does not know, whose list answers "not found" itself (the
                // person's read accepted that as "unknown" too): nothing to look at. Marked as read so the schedule moves
                // on, and it is not asked again every run for the whole watch.
                refunds.markVerified(refundId, Instant.now(), UNKNOWN_PAYMENT_VERIFIED);
                return null;
            }
            log.warn("Could not check reversed refund {} against the provider: {}", refundId, ex.getMessage());
            return null;
        }
    }

    private Payer payerRefundedAnotherWay(Refund refund, Payment payment,
                                          List<PaymentProvider.ProviderRefundEntry> listed) {
        PaymentProvider.ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            if (ex.isRateLimited() || ex.isCredentialsRefused()) {
                throw ex;
            }
            log.warn("Could not read payment {} at the provider to check reversed refund {}: {}",
                    payment.getId(), refund.getId(), ex.getMessage());
            throw new Unreadable();
        }
        return refundedAnotherWay(refund, listed, facts, true);
    }

    /** Mark a double credit: the refund, the payment for a person, an audit line and an event. */
    private void flagDoubleCredit(Refund refund, Payment payment, String providerRefundId, String detail, String reviewReason) {
        Long refundId = refund.getId();
        txTemplate.executeWithoutResult(status -> {
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getLateSuccessAt() == null) {
                locked.setLateSuccessAt(Instant.now());
                locked.setLateSuccessResolvedAt(null);
                refunds.save(locked);
            }
            payments.findById(payment.getId()).ifPresent(p -> {
                if (p.getReviewRequiredAt() == null) {
                    // A CAPTURED payment is not read by the cancellation job, so this only marks it for a person.
                    p.setReviewRequiredAt(Instant.now());
                    p.setReviewReason(reviewReason);
                    payments.save(p);
                }
            });
            auditService.record(null, null, "REFUND_REVERSED_BUT_SENT", "REFUND", refundId,
                    RefundStatus.REVERSED.name(), RefundStatus.REVERSED.name(), detail, "SYSTEM");
            outbox.publish("WithdrawalDoubleCredit", "REFUND", refundId,
                    Map.of("refundId", refundId, "paymentId", payment.getId(),
                            "outletId", payment.getOutletId(),
                            "providerRefundId", String.valueOf(providerRefundId),
                            "amount", Rupees.of(refund.getAmount())), null);
        });
    }
}
