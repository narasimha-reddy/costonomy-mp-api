package com.costonomy.mp.payment.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a person may do about a refund the provider did not send (D-110). Everything here needs
 * {@code REFUND_OPERATE} at platform scope (its read side, {@code PAYMENT_INSPECT}, lists), a note
 * saying what was checked, and is audited with the actor.
 *
 * <p><b>Nothing here decides on a hunch.</b> Every action that moves money or closes a refund is
 * preceded by a read of the provider's refunds for that payment, and refused if the read does not
 * support it: putting money back needs a read showing none of ours (and, above a threshold, a second
 * person); marking a refund done needs the provider's own refund, processed, for this payment and this
 * amount. Operations changes what is possible, not what a party decided (D-048): a wallet is credited
 * only with money the provider did not send, and a refund is completed only when the provider says it was.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundOperations {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final RefundService refundService;
    private final WithdrawalReversalService reversals;
    private final RefundWalletPort wallet;
    private final TransactionTemplate txTemplate;

    /** A verification older than this no longer supports putting money back (D-110). */
    static final Duration VERIFICATION_MAX_AGE = Duration.ofMinutes(10);

    /** A first approval waits this long for the second person. */
    static final Duration APPROVAL_VALID_FOR = Duration.ofHours(24);

    /**
     * How long after the last send of a refund whose outcome was never known (a timeout, a 5xx) it may be
     * put back by a person. The provider's list may lag a refund it has just made, so a read a few seconds
     * after the send that shows none of ours proves little; before this has passed the wallet is not credited.
     */
    @Value("${costonomy.mp.refunds.recredit-min-age:PT30M}")
    private Duration recreditMinAge = Duration.ofMinutes(30);

    /** Above this, putting money back needs two people (E-9). Rupees. */
    @Value("${costonomy.mp.refunds.second-approver-above:10000.00}")
    private BigDecimal secondApproverAbove = new BigDecimal("10000.00");

    private static final Set<RefundStatus> LISTED = Set.of(RefundStatus.NEEDS_REVIEW, RefundStatus.REJECTED);

    // ── Reading ──────────────────────────────────────────────────────────

    /** A refund and the payment behind it, for the queue. */
    public record Row(Refund refund, Payment payment) {
    }

    public List<Row> list(Long actorId, Set<RefundStatus> statuses, ProviderFailureKind kind, Integer limit) {
        accessControl.require(actorId, Permissions.PAYMENT_INSPECT, ScopeType.PLATFORM, null);
        var wanted = statuses == null || statuses.isEmpty() ? LISTED : statuses;
        int max = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        var rows = new ArrayList<Row>();
        for (var refund : refunds.findByStatusInOrderByUpdatedAtDesc(wanted, PageRequest.of(0, max))) {
            if (kind != null && refund.getFailureKind() != kind) {
                continue;
            }
            rows.add(new Row(refund, payments.findById(refund.getPaymentId()).orElse(null)));
        }
        return rows;
    }

    /** One refund with the provider's own list of the payment's refunds, read now (best effort), and the wallet's balance. */
    public record Detail(Row row, List<PaymentProvider.ProviderRefundEntry> providerRefunds,
                         String providerError, BigDecimal walletBalance, BigDecimal secondApproverAbove) {
    }

    public Detail detail(Long actorId, Long refundId) {
        accessControl.require(actorId, Permissions.PAYMENT_INSPECT, ScopeType.PLATFORM, null);
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        List<PaymentProvider.ProviderRefundEntry> listed = null;
        String error = null;
        try {
            listed = provider.listRefunds(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            error = ex.isNotFound() ? "The provider does not know this payment"
                    : "Could not read the provider: " + ex.getMessage();
        }
        return new Detail(new Row(refund, payment), listed, error,
                wallet.balanceOf(payment.getOutletId()), secondApproverAbove);
    }

    // ── Verify ───────────────────────────────────────────────────────────

    /** What a verification found. */
    public record Verified(String result, Long refundId, String status) {
    }

    /** Read the provider's refunds now. Ours found: adopted. Otherwise noted, which is what a later re-credit stands on. */
    public Verified verify(Long actorId, Long refundId, String note) {
        require(actorId);
        note = requireNote(note);
        refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        var read = reversalRunWithProof(refundId, actorId, WithdrawalReversalService.Intent.VERIFY);
        var result = read.result();
        var after = refunds.findById(refundId).orElseThrow();
        if (result == WithdrawalReversalService.Result.NOT_APPLICABLE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a refund in review or rejected can be verified.");
        }
        // A read of the provider is a new fact about the refund: an approval made before it is stale.
        invalidateApproval(refundId);
        // What is recorded, and answered, for a payer already covered is the ordinary "refunded another way".
        String answer = result == WithdrawalReversalService.Result.ALREADY_COVERED ? "FOREIGN_REFUND" : result.name();
        // The payment that proved the keys is named in this row too (it is also in REFUND_UNKNOWN_PAYMENT_PROOF).
        String proof = read.keysProvedBy() == null ? null
                : "keys proven by reading payment " + read.keysProvedBy() + "; the provider does not know this one. ";
        auditService.record(actorId, null, "REFUND_OPS_VERIFY", "REFUND", refundId,
                null, answer, OperatorAuditText.of(proof, false, note, null), "ADMIN");
        log.warn("Refund {} verified by operator {}: {} ({})", refundId, actorId, answer, note);
        return new Verified(answer, refundId, after.getStatus().name());
    }

    // ── Retry ────────────────────────────────────────────────────────────

    /**
     * Send a refund in review again, with the same key. A look at the provider comes first: if the
     * refund is already there it is adopted instead of sent. The refund job then sends it.
     */
    public Verified retry(Long actorId, Long refundId, String note) {
        require(actorId);
        note = requireNote(note);
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a refund in review can be retried.");
        }
        var found = reversalRun(refundId, actorId, WithdrawalReversalService.Intent.VERIFY);
        if (found == WithdrawalReversalService.Result.ADOPTED) {
            auditService.record(actorId, null, "REFUND_OPS_RETRY", "REFUND", refundId, "NEEDS_REVIEW",
                    refunds.findById(refundId).orElseThrow().getStatus().name(),
                    "already at the provider; adopted. " + note, "ADMIN");
            return new Verified(found.name(), refundId, refunds.findById(refundId).orElseThrow().getStatus().name());
        }
        final String said = note;
        txTemplate.executeWithoutResult(status -> {
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            refundService.moveTo(locked, RefundStatus.REQUESTED);
            // (Moving the status also ends any approval waiting for a second person.)
            // A fresh run of sends. sent_at stays, so the send is again preceded by a look at the provider.
            locked.setAttempts(0);
            // The refund is asked of the provider again, and whatever it does then is read afresh.
            locked.setReviewCause(null);
            locked.setReviewRef(null);
            refunds.save(locked);
            auditService.record(actorId, null, "REFUND_OPS_RETRY", "REFUND", refundId, "NEEDS_REVIEW",
                    "REQUESTED", said, "ADMIN");
        });
        log.warn("Refund {} sent back to the refund job by operator {}: {}", refundId, actorId, note);
        return new Verified("REQUESTED", refundId, RefundStatus.REQUESTED.name());
    }

    // ── Re-credit ────────────────────────────────────────────────────────

    /** Outcome of a money-moving action: done, or waiting for a second person. */
    public record Decision(boolean done, boolean awaitingSecondApprover, String status, Long refundId) {
    }

    /**
     * Put a withdrawal part back in the wallet (D-110). Needs a verification from the last ten
     * minutes that showed none of ours, the operator's confirmation that nothing was sent, and
     * for an ambiguous refund the evidence. Above the threshold a second person has to make the
     * same call. Then the same reversal the system uses runs, with its own fresh read.
     */
    public Decision recredit(Long actorId, Long refundId, String note, boolean confirmNoProviderRefund,
                             String evidence) {
        return recredit(actorId, refundId, note, confirmNoProviderRefund, evidence, false);
    }

    /**
     * {@link #recredit(Long, Long, String, boolean, String)} with the confirmation a part on a payment the provider does
     * not know needs (D-110). Such a part, whose last verification is {@code UNKNOWN_PAYMENT}, is <b>always</b> put
     * back by two different people whatever it is worth, and each of them says in {@code evidence} which Razorpay
     * account or keys the payment belongs to and confirms with {@code confirmPaymentOnOtherAccount} that it is another,
     * retired account and that nothing was sent to the payer from there. On top of every other gate: the fresh
     * verification, the minimum time since the last send (for any kind of failure), and the provider read inside the
     * call, which must again find the payment unknown while its keys work.
     */
    public Decision recredit(Long actorId, Long refundId, String note, boolean confirmNoProviderRefund,
                             String evidence, boolean confirmPaymentOnOtherAccount) {
        require(actorId);
        note = requireNote(note);
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getReason() != RefundReason.WALLET_WITHDRAWAL) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a withdrawal part can be put back in a wallet this way. A cancellation refund "
                            + "can be sent to the wallet instead.");
        }
        if (refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a withdrawal in review can be put back. The system handles a rejected one itself.");
        }
        if (!confirmNoProviderRefund) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Confirm that nothing was sent to the customer's card or bank.");
        }
        if (refund.getFailureKind() == ProviderFailureKind.AMBIGUOUS && (evidence == null || evidence.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This refund's outcome was never known. Say what proves the money did not go back "
                            + "(the provider's answer, a support ticket).");
        }
        refuseWhileRefundedAnotherWay(refund);
        boolean unknownPayment = WithdrawalReversalService.UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult());
        if (unknownPayment) {
            refuseWhileItCarriesAProviderRefund(refund);
            requireUnknownPaymentGates(evidence, confirmPaymentOnOtherAccount);
        }
        // A part whose payment showed a refund that two people then recorded as "not this part's" is put back only by two
        // different people again, whatever it is worth, each saying why it is safe: the exclusion is a judgement, and the
        // put-back that stands on it is the second half of the same one.
        boolean afterExclusion = WithdrawalReversalService.FOREIGN_NOT_THIS_PART.equals(refund.getReviewCause());
        requireFreshVerification(refund);
        requireAgedSinceLastSend(refund, unknownPayment);
        if (afterExclusion && !unknownPayment && (evidence == null || evidence.trim().length() < MIN_ACCOUNT_EVIDENCE)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A refund of this payment was recorded as not this part's. Say in the evidence why putting this part "
                            + "back is safe: what shows the payer was not refunded this part's money (at least "
                            + MIN_ACCOUNT_EVIDENCE + " characters).");
        }
        if (holdsForSecondApprover(actorId, refund, unknownPayment ? "RECREDIT_UNKNOWN" : "RECREDIT", note,
                unknownPayment || afterExclusion, evidence, unknownPayment)) {
            return new Decision(false, true, refund.getStatus().name(), refundId);
        }

        // The audit of the put-back is written by the reversal itself, in its own transaction: the record and the
        // money move together, and a text that could not be stored can never fail a part that was put back.
        String reversedAudit = OperatorAuditText.of(null, unknownPayment, note, evidence);
        var result = reversalRun(refundId, actorId, WithdrawalReversalService.Intent.RECREDIT, unknownPayment, reversedAudit);
        if (result == WithdrawalReversalService.Result.UNKNOWN_PAYMENT) {
            // The read made for this very call finds the provider does not know a payment the verification said it
            // knew: none of the gates of a re-credit of that kind was met.
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "The provider's answer about this payment changed: it no longer knows it. Verify this refund again.");
        }
        if (result == WithdrawalReversalService.Result.ADOPTED) {
            auditService.record(actorId, null, "REFUND_OPS_RECREDIT", "REFUND", refundId, "NEEDS_REVIEW",
                    "ADOPTED", OperatorAuditText.of("the provider already holds this refund; nothing put back. ", false, note, null),
                    "ADMIN");
            return new Decision(true, false, refunds.findById(refundId).orElseThrow().getStatus().name(), refundId);
        }
        if (result == WithdrawalReversalService.Result.ALREADY_COVERED) {
            throw alreadyCovered();
        }
        if (result == WithdrawalReversalService.Result.FOREIGN_REFUND) {
            // The fresh read made for this very call shows what the verification before it did not (a refund
            // made in between): nothing is put back.
            throw foreignRefund(namedForeignRefund(refunds.findById(refundId).orElseThrow()));
        }
        if (result != WithdrawalReversalService.Result.REVERSED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This refund is no longer waiting for a person.");
        }
        log.warn("Refund {} put back in the wallet by operator {}: {}", refundId, actorId, note);
        return new Decision(true, false, RefundStatus.REVERSED.name(), refundId);
    }

    // ── To the wallet (a cancellation refund) ────────────────────────────

    /**
     * Send a cancellation refund the provider would not make to the restaurant's wallet instead (D-110).
     * Same gates as a re-credit: it is money going to the restaurant that the provider did not send.
     * One transaction: the refund is REVERSED and a wallet refund of the same payment and amount is
     * made, key {@code cancel-wallet-{refundId}}, so the payment's refunded amount moves once.
     */
    public Decision toWallet(Long actorId, Long refundId, String note, boolean confirmNoProviderRefund) {
        return toWallet(actorId, refundId, note, confirmNoProviderRefund, null, false);
    }

    /** With the evidence and confirmation of a payment the provider does not know: see {@link #recredit(Long, Long, String, boolean, String, boolean)}. */
    public Decision toWallet(Long actorId, Long refundId, String note, boolean confirmNoProviderRefund,
                             String evidence, boolean confirmPaymentOnOtherAccount) {
        require(actorId);
        note = requireNote(note);
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getReason() != RefundReason.CANCELLATION || refund.getDestination() != RefundDestination.ORIGINAL) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a cancellation refund to the original payment can be sent to the wallet.");
        }
        if (refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a refund in review can be sent to the wallet.");
        }
        if (!confirmNoProviderRefund) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Confirm that nothing was sent to the customer's card or bank.");
        }
        refuseWhileRefundedAnotherWay(refund);
        boolean unknownPayment = WithdrawalReversalService.UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult());
        if (unknownPayment) {
            refuseWhileItCarriesAProviderRefund(refund);
            requireUnknownPaymentGates(evidence, confirmPaymentOnOtherAccount);
        }
        requireFreshVerification(refund);
        requireAgedSinceLastSend(refund, unknownPayment);
        if (holdsForSecondApprover(actorId, refund, unknownPayment ? "TOWALLET_UNKNOWN" : "TO_WALLET", note, unknownPayment,
                evidence, unknownPayment)) {
            return new Decision(false, true, refund.getStatus().name(), refundId);
        }
        // A fresh read of our own, so the decision rests on the provider now and not on a check minutes old.
        var read = reversalRun(refundId, actorId, WithdrawalReversalService.Intent.VERIFY);
        if (read == WithdrawalReversalService.Result.ADOPTED) {
            auditService.record(actorId, null, "REFUND_OPS_TO_WALLET", "REFUND", refundId, "NEEDS_REVIEW", "ADOPTED",
                    OperatorAuditText.of("the provider already holds this refund; nothing sent to the wallet. ", false, note, null),
                    "ADMIN");
            return new Decision(true, false, refunds.findById(refundId).orElseThrow().getStatus().name(), refundId);
        }
        if (read == WithdrawalReversalService.Result.ALREADY_COVERED) {
            throw alreadyCovered();
        }
        if (read == WithdrawalReversalService.Result.FOREIGN_REFUND) {
            throw foreignRefund(namedForeignRefund(refunds.findById(refundId).orElseThrow()));
        }
        if (read == WithdrawalReversalService.Result.UNKNOWN_PAYMENT && !unknownPayment) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "The provider's answer about this payment changed: it no longer knows it. Verify this refund again.");
        }
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        final String said = OperatorAuditText.of(null, unknownPayment, note, evidence);
        txTemplate.executeWithoutResult(status -> {
            // Wallet, then refund, then payment (refundToWallet takes the payment).
            wallet.lock(payment.getOutletId());
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            refundService.moveTo(locked, RefundStatus.REVERSED);
            locked.setReversedAt(Instant.now());
            locked.setReversedBy(actorId);
            locked.setOpsAction(null);
            locked.setOpsActionBy(null);
            locked.setOpsActionAt(null);
            refunds.saveAndFlush(locked);
            refundService.refundToWallet(actorId, locked.getPaymentId(), locked.getAmount(),
                    RefundReason.CANCELLATION, "Order cancelled; the refund to the original payment was not "
                            + "possible and was sent to the wallet", "cancel-wallet-" + refundId);
            auditService.record(actorId, null, "REFUND_OPS_TO_WALLET", "REFUND", refundId, "NEEDS_REVIEW",
                    "REVERSED", said, "ADMIN");
        });
        log.error("Cancellation refund {} REVERSED to the wallet of outlet {} by operator {}: {}",
                refundId, payment.getOutletId(), actorId, note);
        return new Decision(true, false, RefundStatus.REVERSED.name(), refundId);
    }

    // ── Mark completed ───────────────────────────────────────────────────

    /**
     * The money did reach the payer: a refund in review that someone made by hand in the provider's
     * dashboard (D-110). Checked against the provider, not taken on trust: the refund must be listed
     * under this refund's payment, be processed, match the amount, and not belong to another refund of ours.
     *
     * <p><b>A refund that covers more than this part</b> (one dashboard refund of the whole payment for a smaller part,
     * or one refund for two parts) cannot match one part's amount and is one provider refund for several of ours,
     * which the unique provider refund id forbids. With {@code confirmPayerRefundedInFull} a withdrawal part can be
     * closed against it anyway, if the provider shows the payment refunded in full, the refund is not ours, and the
     * parts closed against it do not add up to more than it: see {@link #closeAgainstForeignRefund}.
     */
    public Verified markCompleted(Long actorId, Long refundId, String providerRefundId, String note) {
        return markCompleted(actorId, refundId, providerRefundId, note, false);
    }

    public Verified markCompleted(Long actorId, Long refundId, String providerRefundId, String note,
                                  boolean confirmPayerRefundedInFull) {
        return markCompleted(actorId, refundId, providerRefundId, note, confirmPayerRefundedInFull, null, false);
    }

    /**
     * {@link #markCompleted(Long, Long, String, String, boolean)} with the way out of a withdrawal part that carries a
     * provider refund id on a payment the provider does not know ({@code confirmProcessedOnOtherAccount}, D-110): see
     * {@link #markCompletedOnOtherAccount}.
     */
    public Verified markCompleted(Long actorId, Long refundId, String providerRefundId, String note,
                                  boolean confirmPayerRefundedInFull, String evidence, boolean confirmProcessedOnOtherAccount) {
        return markCompleted(actorId, refundId, providerRefundId, note, confirmPayerRefundedInFull, evidence,
                confirmProcessedOnOtherAccount, false);
    }

    /**
     * With {@code confirmRefundCoversThisPart} (D-110): a withdrawal part is closed against a refund made outside Mandi
     * that covers it although the amounts differ and the payment is not refunded in full (a refund of 500 by hand for a part
     * of 400 of a payment of 1000: the 400 and 100 goodwill). See {@link #closeAgainstForeignRefund}.
     */
    public Verified markCompleted(Long actorId, Long refundId, String providerRefundId, String note,
                                  boolean confirmPayerRefundedInFull, String evidence, boolean confirmProcessedOnOtherAccount,
                                  boolean confirmRefundCoversThisPart) {
        require(actorId);
        note = requireNote(note);
        if (providerRefundId == null || providerRefundId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give the provider's refund id.");
        }
        if (confirmRefundCoversThisPart && (confirmPayerRefundedInFull || confirmProcessedOnOtherAccount)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Send one confirmation: confirmRefundCoversThisPart cannot be combined with confirmPayerRefundedInFull "
                            + "or confirmProcessedOnOtherAccount.");
        }
        if (confirmProcessedOnOtherAccount) {
            return markCompletedOnOtherAccount(actorId, refundId, providerRefundId.trim(), note, evidence);
        }
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getStatus() != RefundStatus.NEEDS_REVIEW && refund.getStatus() != RefundStatus.REJECTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a refund in review can be marked completed.");
        }
        if (confirmRefundCoversThisPart && refund.getReason() != RefundReason.WALLET_WITHDRAWAL) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "confirmRefundCoversThisPart is only for a wallet withdrawal part.");
        }
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        List<PaymentProvider.ProviderRefundEntry> listed = readList(payment);
        var entry = listed.stream().filter(e -> providerRefundId.equals(e.providerRefundId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        notListed(providerRefundId)));
        if (entry.status() != PaymentProvider.ProviderRefundStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    entry.status() == PaymentProvider.ProviderRefundStatus.PENDING
                            ? "That refund exists at the provider but is not processed yet (PENDING): wait until it is "
                            + "processed, then try again."
                            : "That refund is not processed at the provider (" + entry.status() + ").");
        }
        if (entry.amount() == null || entry.amount().compareTo(refund.getAmount()) != 0) {
            if (confirmRefundCoversThisPart) {
                return closeAgainstForeignRefund(actorId, refund, payment, entry, listed, note, evidence, true);
            }
            if (confirmPayerRefundedInFull && refund.getReason() == RefundReason.WALLET_WITHDRAWAL) {
                return closeAgainstForeignRefund(actorId, refund, payment, entry, listed, note, null, false);
            }
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "That refund is for ₹%s at the provider, not ₹%s. If it covers this part along with the rest of the "
                            .formatted(com.costonomy.mp.common.text.Rupees.of(entry.amount()),
                                    com.costonomy.mp.common.text.Rupees.of(refund.getAmount()))
                            + "payment (refunded in full by hand), confirm that with confirmPayerRefundedInFull; if it covers this "
                            + "part and the payment is not refunded in full, with confirmRefundCoversThisPart (always two people, "
                            + "evidence).");
        }
        if (refunds.existsByProviderRefundIdAndIdNot(providerRefundId, refundId)) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "That provider refund already completes another refund of ours.");
        }
        requireNotClaimedByOtherParts(payment, providerRefundId);
        // A refund of this very amount may be one made by hand with our own lost send still to land: the payer would be
        // paid twice. Not within the minimum age of the last send of an ambiguous one (as every other close of a part).
        requireAgedSinceLastSend(refund);
        final String said = note;
        final var before = refund.getStatus();
        txTemplate.executeWithoutResult(status -> {
            if (refund.getReason() == RefundReason.WALLET_WITHDRAWAL) {
                wallet.lock(payment.getOutletId());
            }
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW && locked.getStatus() != RefundStatus.REJECTED) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            // Payment after refund (D-104), the lock a closing against a foreign refund holds while it records its claim.
            payments.lockById(payment.getId()).orElseThrow();
            // Again under the locks: a part closed against this provider refund at the same moment holds its claim now.
            requireNotClaimedByOtherParts(payment, providerRefundId);
            // Again under the locks, on the locked refund: its last send may have moved since the check before them.
            requireAgedSinceLastSend(locked);
            // Checked again here, under the locks: the check above ran before them, and two calls for two refunds
            // of ours with one provider refund would both have passed it.
            if (!refundService.applyAdoption(locked, entry)) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "That provider refund already completes another refund of ours.");
            }
            locked.setOpsAction(null);
            locked.setOpsActionBy(null);
            locked.setOpsActionAt(null);
            refunds.save(locked);
            auditService.record(actorId, null, "REFUND_OPS_MARK_COMPLETED", "REFUND", refundId, before.name(),
                    RefundStatus.COMPLETED.name(), "provider refund " + providerRefundId + ". " + said, "ADMIN");
        });
        log.warn("Refund {} marked completed by operator {} against provider refund {}: {}",
                refundId, actorId, providerRefundId, note);
        return new Verified("COMPLETED", refundId, refunds.findById(refundId).orElseThrow().getStatus().name());
    }

    /**
     * <b>The exit of a part that carries a provider refund id on a payment the provider does not know (D-110).</b> Such a part
     * was sent, through keys that knew the payment, and the refund made then (id recorded on the part) cannot be looked up
     * with the keys now in use; it is never put back in a wallet (the payer may hold the money). When the refund was
     * processed on the account it was made on, two different people, always, close the part as COMPLETED here: the payer has
     * the money, the wallet is not credited, and the source is blocked {@code PAYMENT_UNKNOWN}.
     *
     * <p>Every gate, at every call: a withdrawal part in review; the refund id named is the one recorded on the part;
     * {@code evidence} of at least {@value #MIN_ACCOUNT_EVIDENCE} characters (the other account, and what shows the refund
     * was processed there); {@code confirmProcessedOnOtherAccount}; a verification from the last ten minutes that said the
     * provider does not know the payment ({@code UNKNOWN_PAYMENT}); and, inside the completing call and again under the
     * lock, a read of the provider that finds the payment unknown again while its keys are proven to work. The part is
     * COMPLETED with {@code review_cause = COMPLETED_OTHER_ACCOUNT}, {@code review_ref} = the refund id.
     */
    private Verified markCompletedOnOtherAccount(Long actorId, Long refundId, String providerRefundId, String note,
                                                 String evidence) {
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getReason() != RefundReason.WALLET_WITHDRAWAL || refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a withdrawal part in review can be closed as processed on another account.");
        }
        if (refund.getProviderRefundId() == null || !refund.getProviderRefundId().equals(providerRefundId)) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "Only a part that carries a provider refund id of its own is closed this way, and the id named must be "
                            + "that one" + (refund.getProviderRefundId() == null ? " (this part carries none)."
                            : " (" + refund.getProviderRefundId() + ")."));
        }
        if (evidence == null || evidence.trim().length() < MIN_ACCOUNT_EVIDENCE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Say in the evidence which other Razorpay account made refund " + providerRefundId + " and what shows it "
                            + "was processed there (at least " + MIN_ACCOUNT_EVIDENCE + " characters).");
        }
        if (!WithdrawalReversalService.UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult())
                || refund.getVerifiedAt() == null
                || refund.getVerifiedAt().isBefore(Instant.now().minus(VERIFICATION_MAX_AGE))) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "Verify this refund against the payment provider first. The check must be less than "
                            + VERIFICATION_MAX_AGE.toMinutes() + " minutes old and show that the provider does not know the payment "
                            + "(UNKNOWN_PAYMENT).");
        }
        if (holdsForSecondApprover(actorId, refund, boundAction("MCU", List.of(providerRefundId)), note, true, evidence, false)) {
            return new Verified("AWAITING_SECOND_APPROVER", refundId, refund.getStatus().name());
        }
        // The provider now, and its keys proven to work again, at the moment of the action.
        var read = reversalRunWithProof(refundId, actorId, WithdrawalReversalService.Intent.VERIFY);
        if (read.result() == WithdrawalReversalService.Result.ADOPTED) {
            // The keys are fixed and the provider lists this part's own refund: adopted, the part is COMPLETED (the
            // payer has the money, the wallet is not credited), and nothing is left to close.
            auditService.record(actorId, null, "REFUND_OPS_MARK_COMPLETED", "REFUND", refundId, "NEEDS_REVIEW", "ADOPTED",
                    OperatorAuditText.of("the provider now knows the payment and lists this part's own refund; adopted, "
                            + "nothing else closed. ", false, note, null), "ADMIN");
            return new Verified("ADOPTED", refundId, refunds.findById(refundId).orElseThrow().getStatus().name());
        }
        if (read.result() != WithdrawalReversalService.Result.UNKNOWN_PAYMENT) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "The provider's answer about this payment changed: it no longer answers as unknown (" + read.result()
                            + "). Verify this refund again; nothing was closed.");
        }
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        final String said = OperatorAuditText.of("processed on another account; refund " + providerRefundId + ". ", false, note,
                evidence);
        txTemplate.executeWithoutResult(status -> {
            // Wallet, then refund, then payment (D-104).
            wallet.lock(payment.getOutletId());
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            if (!WithdrawalReversalService.UNKNOWN_PAYMENT_VERIFIED.equals(locked.getVerifiedResult())
                    || !providerRefundId.equals(locked.getProviderRefundId())) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                        "What this closing stood on changed. Verify this refund again; nothing was closed.");
            }
            payments.lockById(payment.getId()).orElseThrow();
            locked.setReviewCause(WithdrawalReversalService.COMPLETED_OTHER_ACCOUNT);
            locked.setReviewRef(providerRefundId);
            locked.setOpsAction(null);
            locked.setOpsActionBy(null);
            locked.setOpsActionAt(null);
            refundService.complete(locked);
            if (payments.block(payment.getId(), Instant.now(), "PAYMENT_UNKNOWN") == 1) {
                auditService.record(actorId, null, "PAYMENT_REFUND_BLOCKED", "PAYMENT", payment.getId(), null,
                        "PAYMENT_UNKNOWN", "No more withdrawals are drawn from this payment", "ADMIN");
            }
            auditService.record(actorId, null, "REFUND_OPS_MARK_COMPLETED", "REFUND", refundId, "NEEDS_REVIEW",
                    RefundStatus.COMPLETED.name(), said, "ADMIN");
        });
        log.warn("Refund {} closed by operator {} as processed on another account (provider refund {}): {}",
                refundId, actorId, providerRefundId, note);
        return new Verified("COMPLETED", refundId, refunds.findById(refundId).orElseThrow().getStatus().name());
    }

    /**
     * Close a withdrawal part against a refund made outside Mandi that is not for exactly this part (D-110): the payer
     * was refunded in full and this part's money is inside what they received. Everything is checked at the provider
     * now and again under the locks, and above the threshold a second person has to make the same call.
     *
     * <p>The part is COMPLETED with {@code provider_refund_id} left empty (that column is unique, a claim of one refund
     * for one of ours) and the claim recorded as {@code review_cause = COMPLETED_BY_OTHER_REFUND},
     * {@code review_ref = the provider refund}. The parts closed against one refund never add up to more than it, so
     * two parts of one dashboard refund can be closed and a third cannot, and the refund counts as accounted for when
     * the payment is audited (nothing else of ours on the payment is then read as a refund by someone else).
     */
    private Verified closeAgainstForeignRefund(Long actorId, Refund refund, Payment payment,
                                                PaymentProvider.ProviderRefundEntry entry,
                                                List<PaymentProvider.ProviderRefundEntry> listed, String note,
                                                String evidence, boolean coversThisPart) {
        if (coversThisPart) {
            requireCoversThisPartGates(refund, evidence);
        } else {
            // The payer may hold our own lost send as well: not within the minimum age of an ambiguous one.
            requireAgedSinceLastSend(refund);
        }
        PaymentProvider.ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            throw unreadable(ex);
        }
        if (coversThisPart) {
            if (!facts.captured()) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "The provider does not show this payment as captured, so a refund cannot stand for this part.");
            }
        } else if (!facts.captured() || facts.amount() == null || facts.amountRefunded() == null
                || facts.amountRefunded().compareTo(facts.amount()) < 0) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "The provider does not show this payment refunded in full, so a refund of another amount cannot "
                            + "stand for this part. If the refund is not this part's at all, record that instead "
                            + "(foreign-refund-not-this-part).");
        }
        var ourIds = ourProviderRefundIds(payment.getId());
        if (RefundMatching.isOurs(entry, ourIds)) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "That refund is one of ours, not one made outside Mandi. Verify this refund instead: if it is ours "
                            + "it is adopted.");
        }
        if (RefundMatching.ours(listed, refund, refund.getSentAt() == null && refund.getAttempts() > 0
                ? refund.getCreatedAt() : null).isPresent()) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "The provider holds a refund of this part's own. Verify this refund instead: it is adopted.");
        }
        requireCoversPart(refund, payment, entry);
        String action = boundAction(coversThisPart ? "CV" : "CF", List.of(entry.providerRefundId()));
        if (coversThisPart
                ? holdsForSecondApprover(actorId, refund, action, note, true, evidence, false)
                : holdsForSecondApprover(actorId, refund, action, note)) {
            return new Verified("AWAITING_SECOND_APPROVER", refund.getId(), refund.getStatus().name());
        }
        final String said = coversThisPart ? OperatorAuditText.of(null, false, note, evidence) : note;
        final var before = refund.getStatus();
        txTemplate.executeWithoutResult(status -> {
            // Wallet, then refund, then payment (D-104).
            wallet.lock(payment.getOutletId());
            var locked = refunds.lockById(refund.getId()).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW && locked.getStatus() != RefundStatus.REJECTED) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            payments.lockById(payment.getId()).orElseThrow();
            // Again under the locks: a plain mark-completed of another part that took this refund as its own at the same
            // moment (it is ours then, and a claim against it would be money counted twice).
            if (refunds.existsByProviderRefundIdAndIdNot(entry.providerRefundId(), locked.getId())) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "That refund is one of ours, not one made outside Mandi. Verify this refund instead: if it is ours "
                                + "it is adopted.");
            }
            // Again under the locks: two parts closed against one refund at the same moment would each have found room.
            requireCoversPart(locked, payment, entry);
            locked.setReviewCause(WithdrawalReversalService.COMPLETED_BY_OTHER_REFUND);
            locked.setReviewRef(entry.providerRefundId());
            if (coversThisPart) {
                requireCoversThisPartGates(locked, evidence);
            } else {
                requireAgedSinceLastSend(locked);
            }
            locked.setVerifiedAt(Instant.now());
            locked.setVerifiedResult("FOREIGN_REFUND");
            locked.setOpsAction(null);
            locked.setOpsActionBy(null);
            locked.setOpsActionAt(null);
            refundService.complete(locked);
            if (payments.block(payment.getId(), Instant.now(), "REFUNDED_ELSEWHERE") == 1) {
                auditService.record(actorId, null, "PAYMENT_REFUND_BLOCKED", "PAYMENT", payment.getId(), null,
                        "REFUNDED_ELSEWHERE", "No more withdrawals are drawn from this payment", "ADMIN");
            }
            auditService.record(actorId, null, "REFUND_OPS_MARK_COMPLETED", "REFUND", refund.getId(), before.name(),
                    RefundStatus.COMPLETED.name(), shorten("covered by refund " + entry.providerRefundId() + " of ₹"
                            + com.costonomy.mp.common.text.Rupees.of(entry.amount()) + " made outside Mandi; "
                            + (coversThisPart ? "confirmed to cover this part (payment not necessarily refunded in full). "
                            : "the payment is refunded in full. ") + said, 480), "ADMIN");
        });
        log.warn("Refund {} closed by operator {} against provider refund {} made outside Mandi ({}): {}",
                refund.getId(), actorId, entry.providerRefundId(),
                coversThisPart ? "refund confirmed to cover this part, payment " + payment.getId()
                        : "payment " + payment.getId() + " refunded in full", note);
        return new Verified("COMPLETED", refund.getId(), refunds.findById(refund.getId()).orElseThrow().getStatus().name());
    }

    /**
     * What closing a part against a refund that covers it (not refunded in full) needs besides the shared checks (D-110):
     * evidence of at least {@value #MIN_ACCOUNT_EVIDENCE} characters, a verification from the last ten minutes that showed
     * a refund that is not ours and, for a part whose send was ambiguous, the minimum age since its last send. Checked at every call and again under the lock (the verification is voided by any read).
     */
    private void requireCoversThisPartGates(Refund refund, String evidence) {
        // An ambiguous send of ours may still land (the provider's list lags): the payer would then hold it and this
        // refund. The same minimum age as every other close of a part whose send was never answered.
        requireAgedSinceLastSend(refund);
        if (evidence == null || evidence.trim().length() < MIN_ACCOUNT_EVIDENCE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Say in the evidence what shows that refund covers this part and was meant for it (who made it, "
                            + "why, a ticket; at least " + MIN_ACCOUNT_EVIDENCE + " characters).");
        }
        if (!"FOREIGN_REFUND".equals(refund.getVerifiedResult()) || refund.getVerifiedAt() == null
                || refund.getVerifiedAt().isBefore(Instant.now().minus(VERIFICATION_MAX_AGE))) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "Verify this refund against the payment provider first. The check must be less than "
                            + VERIFICATION_MAX_AGE.toMinutes() + " minutes old and show a refund that is not ours.");
        }
    }

    /**
     * A provider refund that parts of ours are closed against ({@code COMPLETED_BY_OTHER_REFUND}) is not adopted as the
     * money of one refund of ours: that would make the same money stand for those parts and for this one, more than
     * the refund is worth (parts of 300 and 400 closed against and adopted by a refund of 400).
     */
    private void requireNotClaimedByOtherParts(Payment payment, String providerRefundId) {
        BigDecimal claimed = refunds.claimedAgainst(payment.getId(), WithdrawalReversalService.COMPLETED_BY_OTHER_REFUND,
                providerRefundId);
        if (claimed.signum() > 0) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "Parts of ours worth ₹%s are already closed against that provider refund, so it cannot also complete "
                            .formatted(com.costonomy.mp.common.text.Rupees.of(claimed))
                            + "another refund of ours.");
        }
    }

    /** The parts closed against a foreign refund never add up to more than it: this part's amount must fit in what is left of it. */
    private void requireCoversPart(Refund refund, Payment payment, PaymentProvider.ProviderRefundEntry entry) {
        BigDecimal claimed = refunds.claimedAgainst(payment.getId(), WithdrawalReversalService.COMPLETED_BY_OTHER_REFUND,
                entry.providerRefundId());
        if (entry.amount() == null || claimed.add(refund.getAmount()).compareTo(entry.amount()) > 0) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "That refund is for ₹%s and parts of ours worth ₹%s are already closed against it, so it cannot also cover ₹%s."
                            .formatted(com.costonomy.mp.common.text.Rupees.of(entry.amount()),
                                    com.costonomy.mp.common.text.Rupees.of(claimed),
                                    com.costonomy.mp.common.text.Rupees.of(refund.getAmount())));
        }
    }

    // ── A foreign refund that is not this part's ─────────────────────────

    /**
     * Two people record that a refund made outside Mandi is <b>not this part's</b> (D-110): an unrelated refund (a
     * goodwill refund in the provider's dashboard) on a payment the payer was also to receive this part from. The
     * system never puts a part back while the provider shows the payer was refunded another way, and that is right
     * when it cannot tell; this is the way out when a person can. Without it a part whose foreign refund is for
     * another purpose was debited from the wallet, never sent and never put back, with nothing that could close it.
     *
     * <p>What it records is a judgement and no more: the named provider refunds are left out of the proof, by id and
     * with their own amounts ({@link WithdrawalReversalService#FOREIGN_NOT_THIS_PART}). It does not put anything back.
     * The re-credit that follows needs everything it always needs, on what remains: a fresh verification that shows
     * no other refund and no more refunded than ours and the recorded ones explain, the minimum time since the last
     * send, the evidence, **two different people whatever the part is worth** (the judgement and the put-back that stands on it),
     * and its own read of the provider at that moment.
     * A refund not recorded here, a different id, or an amount the recorded ones do not explain still refuses it.
     *
     * <p><b>Only a refund that is not the part's own money can be recorded:</b> every refund named (with those already
     * recorded on this part) must be worth strictly less than the part, and all together strictly less than it
     * ({@link WithdrawalReversalService#exclusionsBelowPart}); a refund worth the part or more looks like compensation for
     * exactly this part and is closed with mark-completed ({@code confirmPayerRefundedInFull}) or returned-outside instead.
     * The rule is checked at both approvals, under the lock, and again whenever the proof is read. Refunds validly recorded
     * are also left out of the room invariant, so a part is put back although they and the part exceed the payment.
     *
     * <p>Always two different people, whatever the part is worth, and each names the same refunds: the first
     * approval is bound to the ids and to nothing else, and is void as soon as the refund is read, retried or changes.
     */
    public Decision foreignRefundNotThisPart(Long actorId, Long refundId, List<String> providerRefundIds,
                                             String note, String evidence) {
        require(actorId);
        note = requireNote(note);
        if (evidence == null || evidence.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Say what shows that refund is not this part's (who made it, why, a ticket).");
        }
        var ids = new java.util.TreeSet<String>();
        if (providerRefundIds != null) {
            providerRefundIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).forEach(ids::add);
        }
        if (ids.isEmpty() || ids.size() > WithdrawalReversalService.MAX_RECORDED_NOT_THIS_PART) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Name the provider's refund, up to "
                    + WithdrawalReversalService.MAX_RECORDED_NOT_THIS_PART + ".");
        }
        var refund = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
        if (refund.getReason() != RefundReason.WALLET_WITHDRAWAL || refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a withdrawal part in review can have a refund recorded as not its own.");
        }
        // The operator has looked: a read of the provider from the last ten minutes that found another refund.
        if (!"FOREIGN_REFUND".equals(refund.getVerifiedResult()) || refund.getVerifiedAt() == null
                || refund.getVerifiedAt().isBefore(Instant.now().minus(VERIFICATION_MAX_AGE))) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "Verify this refund against the payment provider first. The check must be less than "
                            + VERIFICATION_MAX_AGE.toMinutes() + " minutes old and show a refund that is not ours.");
        }
        requireAgedSinceLastSend(refund);
        // Named refunds are checked against the provider at the first approval too, and not only after the second
        // person: a request naming a refund that is not there is not recorded (and would only wait a day to fail).
        requireExclusionsBelowPart(refund, payments.findById(refund.getPaymentId()).orElseThrow(), ids);
        if (holdsForSecondApprover(actorId, refund, boundAction("NTP", ids), note, true)) {
            return new Decision(false, true, refund.getStatus().name(), refundId);
        }

        // The provider now, at the moment of the action, and not the check made before the second person came.
        var read = reversalRun(refundId, actorId, WithdrawalReversalService.Intent.VERIFY);
        if (read == WithdrawalReversalService.Result.ADOPTED) {
            auditService.record(actorId, null, "REFUND_OPS_FOREIGN_NOT_THIS_PART", "REFUND", refundId, "NEEDS_REVIEW",
                    "ADOPTED", "the provider already holds this refund; nothing recorded. " + note, "ADMIN");
            return new Decision(true, false, refunds.findById(refundId).orElseThrow().getStatus().name(), refundId);
        }
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        final var listedNow = requireExclusionsBelowPart(refund, payment, ids);
        final String described = describeListedNotOurs(payment, ids);
        final String said = note;
        final String why = evidence.trim();
        txTemplate.executeWithoutResult(status -> {
            wallet.lock(payment.getOutletId());
            var locked = refunds.lockById(refundId).orElseThrow();
            if (locked.getStatus() != RefundStatus.NEEDS_REVIEW) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund is no longer waiting for a person.");
            }
            // What was recorded before stays, and these are added: a second foreign refund is a second judgement.
            var recorded = recordedStillNotOurs(locked, listedNow, ourProviderRefundIds(payment.getId()));
            recorded.addAll(ids);
            // Again on the row as it is under the lock: two requests at the same moment, each naming a refund that is
            // worth less than the part, must not together record refunds that are worth as much as it.
            if (!WithdrawalReversalService.exclusionsBelowPart(locked.getAmount(), listedNow, recorded)) {
                throw exclusionsNotBelowPart(locked, listedNow, recorded);
            }
            String ref = String.join(",", recorded);
            if (recorded.size() > WithdrawalReversalService.MAX_RECORDED_NOT_THIS_PART || ref.length() > 200) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Too many refunds are recorded as not this part's. Look at this payment with someone who can "
                                + "correct the records.");
            }
            locked.setReviewCause(WithdrawalReversalService.FOREIGN_NOT_THIS_PART);
            locked.setReviewRef(ref);
            locked.setOpsAction(null);
            locked.setOpsActionBy(null);
            locked.setOpsActionAt(null);
            refunds.save(locked);
            auditService.record(actorId, null, "REFUND_OPS_FOREIGN_NOT_THIS_PART", "REFUND", refundId, "NEEDS_REVIEW",
                    "NEEDS_REVIEW", shorten("not this part's: " + described + ". " + said + " Evidence: " + why, 480),
                    "ADMIN");
        });
        log.warn("Refund {}: provider refund(s) {} recorded as not this part's by operator {}: {} (evidence: {})",
                refundId, described, actorId, note, evidence);
        return new Decision(true, false, RefundStatus.NEEDS_REVIEW.name(), refundId);
    }

    /**
     * <b>The rule of an exclusion (D-110).</b> Every refund named, together with those already recorded on this part, must
     * be worth strictly less than the part, and all of them together strictly less than it. A refund of at least the part's
     * amount looks like compensation for exactly this part (a 400 refund by hand for a refused part of 400): it cannot be
     * "not this part's", and its exit is mark-completed with {@code confirmPayerRefundedInFull} (or returned-outside).
     * 422, nothing recorded, at the first approval and again at the second (and once more under the lock).
     *
     * @return the provider's list as read now
     */
    private List<PaymentProvider.ProviderRefundEntry> requireExclusionsBelowPart(Refund refund, Payment payment,
                                                                                java.util.Collection<String> ids) {
        var listed = readList(payment);
        describeListedNotOurs(payment, ids);
        var all = recordedStillNotOurs(refund, listed, ourProviderRefundIds(payment.getId()));
        all.addAll(ids);
        if (!WithdrawalReversalService.exclusionsBelowPart(refund.getAmount(), listed, all)) {
            throw exclusionsNotBelowPart(refund, listed, all);
        }
        return listed;
    }

    /**
     * The refunds recorded on this part as not its own, less those that are ours now (another part was closed against
     * one): those count as ours in the proof and must not be counted again as excluded (see the books of the reversal).
     */
    private static java.util.LinkedHashSet<String> recordedStillNotOurs(Refund refund,
                                                                       List<PaymentProvider.ProviderRefundEntry> listed,
                                                                       Set<String> ourIds) {
        var recorded = new java.util.LinkedHashSet<>(WithdrawalReversalService.recordedNotThisPart(refund));
        recorded.removeIf(id -> ourIds.contains(id)
                || listed.stream().anyMatch(e -> id.equals(e.providerRefundId()) && RefundMatching.isOurs(e, ourIds)));
        return recorded;
    }

    private static BusinessException exclusionsNotBelowPart(Refund refund, List<PaymentProvider.ProviderRefundEntry> listed,
                                                            java.util.Collection<String> ids) {
        var sum = BigDecimal.ZERO;
        String big = null;
        for (String id : ids) {
            var entry = listed.stream().filter(e -> id.equals(e.providerRefundId())).findFirst().orElse(null);
            if (entry == null || entry.amount() == null) {
                return new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "Refund " + id + " is recorded or named for this part but the provider does not list it on this "
                                + "payment any more, so the refunds cannot be judged against the part. Nothing is recorded; "
                                + "look at this payment with someone who can correct the records.");
            }
            if (entry.status() != PaymentProvider.ProviderRefundStatus.FAILED) {
                sum = sum.add(entry.amount());
                if (big == null && entry.amount().compareTo(refund.getAmount()) >= 0) {
                    big = id + " (₹" + com.costonomy.mp.common.text.Rupees.of(entry.amount()) + ")";
                }
            }
        }
        String part = com.costonomy.mp.common.text.Rupees.of(refund.getAmount());
        return new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                (big != null
                        ? "Refund " + big + " is worth at least this part (₹" + part + ")"
                        : "The refunds named, with those already recorded, add up to ₹" + com.costonomy.mp.common.text.Rupees.of(sum)
                                + ", at least this part (₹" + part + ")")
                        + ": it looks like the payer's money for exactly this part, so it cannot be recorded as not this part's and "
                        + "the part is not put back. Each refund recorded as not this part's must be worth less than the part, and "
                        + "together less than it. If the refund covers this part, mark the part completed against it "
                        + "(confirmPayerRefundedInFull, or confirmRefundCoversThisPart when the payment is not refunded in full) or, for a "
                        + "cancelled order, record it as returned outside.");
    }

    /**
     * The named provider refunds, each read on the payment's list now: listed, not failed and not ours. Otherwise 422.
     *
     * @return them described, for the record
     */
    private String describeListedNotOurs(Payment payment, java.util.Collection<String> ids) {
        var listed = readList(payment);
        var ourIds = ourProviderRefundIds(payment.getId());
        var described = new StringBuilder();
        for (String id : ids) {
            var entry = listed.stream().filter(e -> id.equals(e.providerRefundId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED, notListed(id)));
            if (RefundMatching.isOurs(entry, ourIds)) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "Refund " + id + " is one of ours. Verify this refund instead: if it is this part's it is adopted.");
            }
            if (entry.status() == PaymentProvider.ProviderRefundStatus.FAILED) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "Refund " + id + " failed at the provider and moved no money: there is nothing to record.");
            }
            described.append(described.length() == 0 ? "" : ", ").append(id).append(" (₹")
                    .append(com.costonomy.mp.common.text.Rupees.of(entry.amount())).append(")");
        }
        return described.toString();
    }

    /** The provider's list does not show the refund: wrong id, or a refund still pending that the list shows once processed. */
    private static String notListed(String providerRefundId) {
        return "The provider lists no refund " + providerRefundId + " on this refund's payment. Check the id; a refund "
                + "made a moment ago may not be listed until the provider has processed it (it can take a few minutes): "
                + "try again then.";
    }

    private Set<String> ourProviderRefundIds(Long paymentId) {
        var ourIds = new java.util.HashSet<String>();
        for (var ours : refunds.findByPaymentIdOrderByCreatedAtDesc(paymentId)) {
            if (ours.getProviderRefundId() != null) {
                ourIds.add(ours.getProviderRefundId());
            }
        }
        return ourIds;
    }

    /**
     * The action a first approver recorded, bound to what it was about: {@code ops_action} is sixteen characters, so
     * a prefix and the start of a hash of the ids. A second person who names other refunds is not the second of this
     * request, and becomes the first of a new one.
     */
    private static String boundAction(String prefix, java.util.Collection<String> ids) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", new java.util.TreeSet<>(ids)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefix + java.util.HexFormat.of().formatHex(digest).substring(0, 16 - prefix.length());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    // ── A payment refunded outside Mandi ─────────────────────────────────

    /**
     * A cancelled order's payment stopped for a person because it was refunded by hand in the provider's
     * dashboard ("returned outside Mandi", D-110). Checked against the provider: the payment must show
     * refunded in full and the named refund must be listed under it, processed, for the payment's whole
     * amount. Then, in one transaction, the payment is recorded as captured and refunded and the
     * cancellation refund as done (key {@code cancel-order-{orderId}}, so the job cannot raise a second),
     * and the review flag is cleared. Replaces the hand-run SQL in the runbook.
     */
    public Verified returnedOutside(Long actorId, Long paymentId, String providerRefundId, String note) {
        require(actorId);
        note = requireNote(note);
        if (providerRefundId == null || providerRefundId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give the provider's refund id.");
        }
        var payment = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.CANCEL_PENDING || payment.getReviewRequiredAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a cancelled order's payment waiting for a person can be recorded as returned outside Mandi.");
        }
        PaymentProvider.ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            throw unreadable(ex);
        }
        if (!facts.captured() || facts.amount() == null || facts.amount().compareTo(payment.getAuthorizedAmount()) != 0
                || facts.amountRefunded() == null || facts.amountRefunded().compareTo(facts.amount()) < 0
                || facts.providerOrderId() == null || !facts.providerOrderId().equals(payment.getProviderOrderId())) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "The provider does not show this payment captured and refunded in full.");
        }
        var entry = readList(payment).stream().filter(e -> providerRefundId.equals(e.providerRefundId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "The provider lists no refund " + providerRefundId + " on this payment."));
        if (entry.status() != PaymentProvider.ProviderRefundStatus.COMPLETED
                || entry.amount() == null || entry.amount().compareTo(payment.getAuthorizedAmount()) != 0) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                    "That refund is not a processed refund of the whole ₹%s.".formatted(
                            com.costonomy.mp.common.text.Rupees.of(payment.getAuthorizedAmount())));
        }
        final String said = note;
        final String key = CancellationLedger.cancelRefundKey(payment.getSupplierOrderId());
        txTemplate.executeWithoutResult(status -> {
            var locked = payments.lockById(paymentId).orElseThrow();
            if (locked.getStatus() != PaymentStatus.CANCEL_PENDING || locked.getReviewRequiredAt() == null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This payment is no longer waiting for a person.");
            }
            if (refunds.findByIdempotencyKey(key).isPresent()) {
                throw new BusinessException(ErrorCode.REFUND_ALREADY_REQUESTED);
            }
            if (refunds.existsByProviderRefundIdAndIdNot(providerRefundId, -1L)) {
                throw new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                        "That provider refund already completes a refund of ours.");
            }
            requireNotClaimedByOtherParts(locked, providerRefundId);
            locked.setStatus(PaymentStatus.CAPTURED);
            locked.setCapturedAmount(locked.getAuthorizedAmount());
            locked.setCapturedAt(locked.getCapturedAt() == null ? Instant.now() : locked.getCapturedAt());
            if (facts.fee() != null && locked.getProviderFee() == null) {
                locked.setProviderFee(facts.fee());
            }
            locked.setReviewRequiredAt(null);
            locked.setReviewReason(null);
            payments.saveAndFlush(locked);

            var refund = new Refund();
            refund.setPaymentId(paymentId);
            refund.setSupplierOrderId(locked.getSupplierOrderId());
            refund.setAmount(locked.getAuthorizedAmount());
            refund.setReason(RefundReason.CANCELLATION);
            refund.setDestination(RefundDestination.ORIGINAL);
            refund.setNote("Order cancelled before dispatch; returned outside Mandi (provider refund " + providerRefundId + ")");
            refund.setStatus(RefundStatus.PROCESSING);
            refund.setProviderRefundId(providerRefundId);
            refund.setIdempotencyKey(key);
            refund.setRequestedBy(actorId);
            refund.setSentAt(Instant.now());
            refund.setLastSentAt(Instant.now());
            refund.setVerifiedAt(Instant.now());
            refund.setVerifiedResult("OURS");
            refunds.saveAndFlush(refund);
            // Completes the refund and moves the payment to FULLY_REFUNDED, as any completed refund does.
            refundService.complete(refund);
            auditService.record(actorId, null, "PAYMENT_RETURNED_OUTSIDE", "PAYMENT", paymentId, "CANCEL_PENDING",
                    PaymentStatus.FULLY_REFUNDED.name(), "provider refund " + providerRefundId + ". " + said, "ADMIN");
        });
        log.warn("Payment {} recorded as returned outside Mandi by operator {} (provider refund {}): {}",
                paymentId, actorId, providerRefundId, note);
        return new Verified("COMPLETED", null, payments.findById(paymentId).orElseThrow().getStatus().name());
    }

    // ── A double credit ──────────────────────────────────────────────────

    /**
     * A person has dealt with a refund that was put back in a wallet and then turned up at the provider (D-110):
     * the restaurant's credit was adjusted, or it was decided to stand. Ends the pause on the outlet's
     * withdrawals. Says nothing about the money, which this does not move.
     */
    public Verified resolveLateSuccess(Long actorId, Long refundId, String note) {
        require(actorId);
        String said = requireNote(note);
        boolean resolved = Boolean.TRUE.equals(txTemplate.execute(status -> {
            var locked = refunds.lockById(refundId).orElseThrow(() -> new NotFoundException("Refund", refundId));
            if (locked.getLateSuccessAt() == null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This refund was not found at the provider after being put back.");
            }
            if (locked.getLateSuccessResolvedAt() != null) {
                return false;
            }
            locked.setLateSuccessResolvedAt(Instant.now());
            refunds.save(locked);
            auditService.record(actorId, null, "REFUND_LATE_SUCCESS_RESOLVED", "REFUND", refundId,
                    locked.getStatus().name(), locked.getStatus().name(), said, "ADMIN");
            return true;
        }));
        log.warn("Late success of refund {} resolved by operator {}: {} (changed={})", refundId, actorId, said, resolved);
        return new Verified(resolved ? "RESOLVED" : "ALREADY_RESOLVED", refundId,
                refunds.findById(refundId).orElseThrow().getStatus().name());
    }

    // ── Block and unblock a source ───────────────────────────────────────

    public boolean blockSource(Long actorId, Long paymentId, String reason) {
        require(actorId);
        String said = requireNote(reason);
        payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
        boolean changed = Boolean.TRUE.equals(txTemplate.execute(status -> {
            boolean blocked = payments.block(paymentId, Instant.now(), "OPS") == 1;
            if (blocked) {
                auditService.record(actorId, null, "PAYMENT_REFUND_BLOCKED", "PAYMENT", paymentId, null, "OPS", said, "ADMIN");
            }
            return blocked;
        }));
        log.warn("Payment {} blocked as a refund source by operator {}: {} (changed={})", paymentId, actorId, said, changed);
        return changed;
    }

    public boolean unblockSource(Long actorId, Long paymentId, String reason) {
        require(actorId);
        String said = requireNote(reason);
        payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
        boolean changed = Boolean.TRUE.equals(txTemplate.execute(status -> {
            boolean unblocked = payments.unblock(paymentId, Instant.now()) == 1;
            if (unblocked) {
                auditService.record(actorId, null, "PAYMENT_REFUND_UNBLOCKED", "PAYMENT", paymentId, "BLOCKED", null, said, "ADMIN");
            }
            return unblocked;
        }));
        log.warn("Payment {} unblocked as a refund source by operator {}: {} (changed={})", paymentId, actorId, said, changed);
        return changed;
    }

    // ── shared ───────────────────────────────────────────────────────────

    private void require(Long actorId) {
        accessControl.require(actorId, Permissions.REFUND_OPERATE, ScopeType.PLATFORM, null);
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say what you checked.");
        }
        String trimmed = note.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400);
    }

    /** The provider's list, or a clear failure: an unreadable provider decides nothing. */
    private List<PaymentProvider.ProviderRefundEntry> readList(Payment payment) {
        try {
            return provider.listRefunds(payment.getProviderPaymentId());
        } catch (PaymentProviderException ex) {
            if (ex.isNotFound()) {
                return List.of();
            }
            throw unreadable(ex);
        }
    }

    private static BusinessException unreadable(PaymentProviderException ex) {
        return new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                "The payment provider could not be read just now: " + ex.getMessage());
    }

    private WithdrawalReversalService.Result reversalRun(Long refundId, Long actorId,
                                                          WithdrawalReversalService.Intent intent) {
        return reversalRun(refundId, actorId, intent, false);
    }

    private WithdrawalReversalService.Read reversalRunWithProof(Long refundId, Long actorId,
                                                                WithdrawalReversalService.Intent intent) {
        try {
            return reversals.runWithProof(refundId, actorId, intent, false, null);
        } catch (PaymentProviderException ex) {
            throw unreadable(ex);
        }
    }

    private WithdrawalReversalService.Result reversalRun(Long refundId, Long actorId,
                                                          WithdrawalReversalService.Intent intent,
                                                          boolean unknownPaymentApproved) {
        return reversalRun(refundId, actorId, intent, unknownPaymentApproved, null);
    }

    private WithdrawalReversalService.Result reversalRun(Long refundId, Long actorId,
                                                          WithdrawalReversalService.Intent intent,
                                                          boolean unknownPaymentApproved, String reversedAudit) {
        try {
            return reversals.run(refundId, actorId, intent, unknownPaymentApproved, reversedAudit);
        } catch (PaymentProviderException ex) {
            throw unreadable(ex);
        }
    }

    /** An ambiguous refund is not put back within {@link #recreditMinAge} of its last send: the list may lag. */
    private void requireAgedSinceLastSend(Refund refund) {
        requireAgedSinceLastSend(refund, false);
    }

    /**
     * When the refund was last sent, from timestamps that no action of a person ever moves: {@code last_sent_at}; for a
     * refund from before that column (every legacy row) {@code sent_at}, the first send, and failing that
     * {@code created_at}. Never {@code updated_at}: recording or voiding an approval, and a verification, move it, and the
     * minimum age then re-armed itself for ever against a verification that has to be under ten minutes old.
     */
    static Instant lastSentOf(Refund refund) {
        if (refund.getLastSentAt() != null) {
            return refund.getLastSentAt();
        }
        return refund.getSentAt() != null ? refund.getSentAt() : refund.getCreatedAt();
    }

    /** @param whatever true for a payment the provider does not know: the minimum applies to every kind of failure */
    private void requireAgedSinceLastSend(Refund refund, boolean whatever) {
        // From the last send, not from updated_at: recording a first approver's request, or voiding it, moves
        // updated_at, and the second person would then never be old enough. (A refund with no last_sent_at was
        // last changed before the column existed: its updated_at is the best there is, and errs on the safe side.)
        Instant lastSent = lastSentOf(refund);
        if ((whatever || refund.getFailureKind() == ProviderFailureKind.AMBIGUOUS) && lastSent != null
                && lastSent.isAfter(Instant.now().minus(recreditMinAge))) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "This refund was last sent less than " + recreditMinAge.toMinutes() + " minutes ago and its "
                            + "outcome was never known: the provider's list may not show it yet. Verify it again later.");
        }
    }

    /**
     * A part the system sent to review because the payer was refunded another way is never put back, by anyone:
     * the wallet would be credited for money the payer already has. The exits are marking it completed against
     * that refund, or retrying it (the provider then refuses what it cannot do).
     */
    private static void refuseWhileRefundedAnotherWay(Refund refund) {
        if (WithdrawalReversalService.REFUNDED_ANOTHER_WAY.equals(refund.getReviewCause())) {
            throw foreignRefund(refund.getReviewRef());
        }
    }

    /** The payer has already been refunded at the provider for at least this part's amount: never put back, by anyone. */
    private static BusinessException alreadyCovered() {
        return new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                "The payment has already been refunded to the payer for at least this amount: what the provider shows as "
                        + "refunded, counted whole (less refunds validly recorded as not this part's), plus this part would "
                        + "exceed the payment. It is not put back in a wallet. If the payer's refund covers this part, "
                        + "mark it completed against that refund (confirmPayerRefundedInFull) or, for a cancelled order, "
                        + "record it as returned outside.");
    }

    /**
     * The foreign refund a message names: the one the system found ({@code REFUNDED_ANOTHER_WAY}). After an exclusion
     * {@code review_ref} holds the refunds recorded as not this part's, which are not the reason of a refusal.
     */
    private String namedForeignRefund(Refund refund) {
        if (WithdrawalReversalService.REFUNDED_ANOTHER_WAY.equals(refund.getReviewCause())) {
            return refund.getReviewRef();
        }
        if (!WithdrawalReversalService.FOREIGN_NOT_THIS_PART.equals(refund.getReviewCause())) {
            return null;
        }
        // After an exclusion: the refund that is not accounted for (made after it, or not listed then), if the list shows one.
        try {
            var payment = payments.findById(refund.getPaymentId()).orElseThrow();
            return reversals.firstUnaccountedForeignRefund(refund, provider.listRefunds(payment.getProviderPaymentId()));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BusinessException foreignRefund(String providerRefundId) {
        return new BusinessException(ErrorCode.REFUND_VERIFICATION_FAILED,
                "The payment provider shows the payer was refunded another way"
                        + (providerRefundId == null ? " (more has gone back than our refunds explain)"
                        : " (refund " + providerRefundId + ", which is not ours)")
                        + ", so this is not put back in a wallet. If that refund is this one, mark it completed against it. "
                        + "If it covers this part and the payer was refunded in full, mark it completed against it and confirm "
                        + "that. If it has nothing to do with this part and is worth less than it, two people can record that "
                        + "(foreign-refund-not-this-part; the refunds recorded must each be worth less than the part and together "
                        + "less than it). If it covers this part although the payment is not refunded in full, mark it completed "
                        + "against it with confirmRefundCoversThisPart (two people, evidence). Retry the part only if that refund "
                        + "has nothing to do with it: a retry sends the part again.");
    }

    /** Whatever a first approver asked for is over: the refund it was about has since been read, retried or changed. */
    private void invalidateApproval(Long refundId) {
        txTemplate.executeWithoutResult(status -> refunds.lockById(refundId).ifPresent(locked -> {
            if (locked.getOpsAction() != null) {
                locked.setOpsAction(null);
                locked.setOpsActionBy(null);
                locked.setOpsActionAt(null);
                refunds.save(locked);
            }
        }));
    }

    /** The last read of the provider must be recent and have shown none of ours. */
    private void requireFreshVerification(Refund refund) {
        if (!("NONE_OF_OURS".equals(refund.getVerifiedResult())
                || WithdrawalReversalService.UNKNOWN_PAYMENT_VERIFIED.equals(refund.getVerifiedResult()))
                || refund.getVerifiedAt() == null
                || refund.getVerifiedAt().isBefore(Instant.now().minus(VERIFICATION_MAX_AGE))) {
            throw new BusinessException(ErrorCode.REFUND_VERIFICATION_REQUIRED,
                    "Verify this refund against the payment provider first. The check must be less than "
                            + VERIFICATION_MAX_AGE.toMinutes() + " minutes old and show no refund of ours"
                            + ("FOREIGN_REFUND".equals(refund.getVerifiedResult())
                            ? ". The last check showed the payer was refunded another way, or that the payment has already "
                            + "been refunded for at least this amount: it is not put back while that holds."
                            : "."));
        }
    }

    /**
     * A part that carries a provider refund id was sent to the provider, which made a refund for it (and then reported
     * it failed, or was never asked again): it went out through the keys that were then in use. That proves the payment
     * is known to an account that is, or was, ours, and a person's "nothing was sent from the other account" is then
     * about that refund, which the keys now in use cannot look up. So the unknown-payment close is refused while the id
     * is there. The one exit of a withdrawal part is to check that refund on the account it was made on and, if it was
     * processed there, close the part with mark-completed (evidence, {@code confirmProcessedOnOtherAccount}, two
     * people: {@link #markCompletedOnOtherAccount}). If it was not processed there, nothing here can close it: it needs
     * engineering or a manual step. A cancellation refund has no exit here at all (nothing reads the other account).
     */
    private static void refuseWhileItCarriesAProviderRefund(Refund refund) {
        if (refund.getProviderRefundId() != null && !refund.getProviderRefundId().isBlank()) {
            String head = "This part was sent to the provider as refund " + refund.getProviderRefundId() + ", which the keys now "
                    + "in use cannot look up (they do not know the payment). It is not put back on an unknown payment while "
                    + "that refund id is recorded. Check that refund on the account it was made on. ";
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, head
                    + (refund.getReason() == RefundReason.WALLET_WITHDRAWAL
                    ? "If it was processed there, two different people close the part with mark-completed, naming that refund, "
                    + "with evidence (the other account and what shows it was processed) and confirmProcessedOnOtherAccount: "
                    + "the payer has the money and the wallet is not credited. If it was NOT processed there, it cannot be "
                    + "closed from here and needs engineering (a manual step)."
                    : "A cancellation refund cannot be closed from here: it needs engineering (a manual step)."));
        }
    }

    /** The fewest characters of evidence about which account a payment the provider does not know belongs to. */
    static final int MIN_ACCOUNT_EVIDENCE = 15;

    /**
     * What a person must say before a part on a payment the provider does not know is put back, at every call:
     * which Razorpay account or keys the payment belongs to, and that it is another (retired) account and that nothing
     * was sent to the payer from it.
     */
    private static void requireUnknownPaymentGates(String evidence, boolean confirmPaymentOnOtherAccount) {
        if (evidence == null || evidence.trim().length() < MIN_ACCOUNT_EVIDENCE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The provider does not know this payment. Say in the evidence which Razorpay account or keys it "
                            + "belongs to and what shows that nothing was sent to the payer from there.");
        }
        if (!confirmPaymentOnOtherAccount) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Confirm that this payment belongs to another (retired) Razorpay account, that the provider's "
                            + "current keys are not that account's, and that nothing was sent to the payer from it "
                            + "(confirmPaymentOnOtherAccount).");
        }
    }

    /**
     * Above the threshold, a first person records the request and a different person carries it out.
     *
     * @return true if this call only recorded the request (or found it still waiting)
     */
    private boolean holdsForSecondApprover(Long actorId, Refund refund, String action, String note) {
        return holdsForSecondApprover(actorId, refund, action, note, false, null, false);
    }

    private boolean holdsForSecondApprover(Long actorId, Refund refund, String action, String note, boolean always) {
        return holdsForSecondApprover(actorId, refund, action, note, always, null, false);
    }

    /**
     * @param always true for a judgement that is not about the amount at all (a foreign refund is not this part's):
     *               two people whatever the part is worth
     */
    private boolean holdsForSecondApprover(Long actorId, Refund refund, String action, String note, boolean always,
                                           String evidence, boolean confirmed) {
        if (!always && refund.getAmount().compareTo(secondApproverAbove) <= 0) {
            return false;
        }
        Instant now = Instant.now();
        boolean pending = action.equals(refund.getOpsAction()) && refund.getOpsActionAt() != null
                && refund.getOpsActionAt().isAfter(now.minus(APPROVAL_VALID_FOR));
        if (pending && !actorId.equals(refund.getOpsActionBy())) {
            return false;
        }
        if (pending) {
            throw new BusinessException(ErrorCode.SECOND_APPROVER_REQUIRED,
                    "You asked for this already. A second person has to make the same request.");
        }
        txTemplate.executeWithoutResult(status -> {
            var locked = refunds.lockById(refund.getId()).orElseThrow();
            locked.setOpsAction(action);
            locked.setOpsActionBy(actorId);
            locked.setOpsActionAt(now);
            refunds.save(locked);
            auditService.record(actorId, null, "REFUND_OPS_APPROVAL_REQUESTED", "REFUND", refund.getId(),
                    refund.getStatus().name(), action,
                    OperatorAuditText.of((always ? "Always two people: " : "Above ₹" + secondApproverAbove.toPlainString() + ": ")
                            + "waiting for a second person. ", confirmed, note, evidence), "ADMIN");
        });
        log.warn("Refund {} {} requested by operator {}; waiting for a second person (₹{})",
                refund.getId(), action, actorId, refund.getAmount().toPlainString());
        return true;
    }
}
