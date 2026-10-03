package com.costonomy.mp.admin.web.dto;

import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.service.RefundOperations;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Operations' view of refunds the provider did not send, and the requests that act on them (D-110). */
public final class AdminRefundDtos {

    private AdminRefundDtos() {
    }

    /** What was checked. Required and audited on every action. */
    public record NoteRequest(@NotBlank(message = "Say what you checked") @Size(max = 400) String note) {
    }

    public record RecreditRequest(
            @NotBlank(message = "Say what you checked") @Size(max = 400) String note,
            /** The operator confirms that nothing was sent to the customer's card or bank. */
            boolean confirmNoProviderRefund,
            /** What proves it, required when the refund's outcome was never known. */
            @Size(max = 400) String evidence,
            /**
             * Only for a part whose last `verify` said UNKNOWN_PAYMENT (the provider does not know the payment): the
             * operator confirms it belongs to another, retired Razorpay account and nothing was sent from it. Optional.
             */
            boolean confirmPaymentOnOtherAccount) {
    }

    public record ToWalletRequest(
            @NotBlank(message = "Say what you checked") @Size(max = 400) String note,
            boolean confirmNoProviderRefund,
            /** Only for a payment the provider does not know: which account it belongs to. Optional. */
            @Size(max = 400) String evidence,
            /** Only for a payment the provider does not know: see {@link RecreditRequest}. Optional. */
            boolean confirmPaymentOnOtherAccount) {
    }

    public record MarkCompletedRequest(
            @NotBlank(message = "Give the provider's refund id") @Size(max = 200) String providerRefundId,
            @NotBlank(message = "Say what you checked") @Size(max = 400) String note,
            /**
             * For a withdrawal part only: the refund is for more than this part (a dashboard refund of the whole
             * payment, or of two parts) and the operator confirms the payer was refunded in full. Optional.
             */
            boolean confirmPayerRefundedInFull,
            /**
             * Only for a withdrawal part that carries a provider refund id on a payment the provider does not know: which
             * other Razorpay account made that refund and what shows it was processed there (at least 15 characters).
             */
            @Size(max = 400) String evidence,
            /** The operator confirms that refund was processed on that other account (see `evidence`). Optional. */
            boolean confirmProcessedOnOtherAccount,
            /**
             * For a withdrawal part only: the refund made outside Mandi (listed, processed, not ours) covers this part
             * although its amount differs and the payment is not refunded in full (a 500 refund by hand for a part of 400:
             * the 400 and 100 goodwill). Always two people, a fresh `verify` that showed the refund and `evidence` of at
             * least 15 characters; the parts closed against one refund never add up to more than it. Optional.
             */
            boolean confirmRefundCoversThisPart) {
    }

    /** Two people record that the provider's refund(s) are not this part's (D-110). */
    public record ForeignRefundNotThisPartRequest(
            @NotBlank(message = "Say what you checked") @Size(max = 400) String note,
            /** What shows the refund is unrelated: who made it and why, a ticket. Required. */
            @NotBlank(message = "Say what shows the refund is not this part's") @Size(max = 400) String evidence,
            @jakarta.validation.constraints.NotEmpty(message = "Name the provider's refund")
            @Size(max = 6, message = "At most 6 refunds")
            List<@NotBlank @Size(max = 60) String> providerRefundIds) {
    }

    public record ReasonRequest(@NotBlank(message = "Say why") @Size(max = 400) String reason) {
    }

    public record ReturnedOutsideRequest(
            @NotBlank(message = "Give the provider's refund id") @Size(max = 200) String providerRefundId,
            @NotBlank(message = "Say what you checked") @Size(max = 400) String note) {
    }

    /** One refund in the queue. */
    public record RefundSummary(
            Long id, Long paymentId, Long outletId, Long supplierOrderId,
            BigDecimal amount, String reason, String destination, String status, int attempts,
            String failureKind, String failureCode, String failureReason, String reviewCause, String reviewRef,
            Instant createdAt, Instant sentAt, Instant verifiedAt, String verifiedResult,
            Instant reversedAt, Long reversedBy,
            String pendingAction, Long pendingActionBy, Instant pendingActionAt,
            Instant sourceBlockedAt, String sourceBlockedReason, Instant updatedAt) {

        public static RefundSummary of(RefundOperations.Row row) {
            Refund r = row.refund();
            Payment p = row.payment();
            return new RefundSummary(r.getId(), r.getPaymentId(), p == null ? null : p.getOutletId(),
                    r.getSupplierOrderId(), r.getAmount(), r.getReason().name(), r.getDestination().name(),
                    r.getStatus().name(), r.getAttempts(),
                    r.getFailureKind() == null ? null : r.getFailureKind().name(),
                    r.getFailureCode(), r.getFailureReason(), r.getReviewCause(), r.getReviewRef(), r.getCreatedAt(), r.getSentAt(),
                    r.getVerifiedAt(), r.getVerifiedResult(), r.getReversedAt(), r.getReversedBy(),
                    r.getOpsAction(), r.getOpsActionBy(), r.getOpsActionAt(),
                    p == null ? null : p.getProviderRefundBlockedAt(),
                    p == null ? null : p.getProviderRefundBlockedReason(), r.getUpdatedAt());
        }
    }

    public record ProviderRefundView(String id, BigDecimal amount, String status, String receipt, boolean ours) {
    }

    public record RefundDetail(RefundSummary refund, List<ProviderRefundView> providerRefunds,
                               String providerError, BigDecimal walletBalance, BigDecimal secondApproverAbove) {

        public static RefundDetail of(RefundOperations.Detail detail) {
            String receipt = "mandi-refund-" + detail.row().refund().getId();
            List<ProviderRefundView> views = detail.providerRefunds() == null ? null
                    : detail.providerRefunds().stream().map((PaymentProvider.ProviderRefundEntry e) ->
                    new ProviderRefundView(e.providerRefundId(), e.amount(), e.status().name(), e.receipt(),
                            receipt.equals(e.receipt()) || String.valueOf(detail.row().refund().getId())
                                    .equals(e.mandiRefundId()))).toList();
            return new RefundDetail(RefundSummary.of(detail.row()), views, detail.providerError(),
                    detail.walletBalance(), detail.secondApproverAbove());
        }
    }

    public record ActionResult(String result, Long refundId, String status, boolean done,
                               boolean awaitingSecondApprover) {
    }
}
