package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundEntry;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which of a payment's refunds at the provider is ours (D-110).
 *
 * <p>Ours means it carries the receipt we send ({@code mandi-refund-{id}}) or our id in its notes,
 * or is the refund we already recorded. A refund the provider reports as {@code failed} is not
 * money that left, and is not returned as a match: the caller reads "no refund of ours that moved
 * money". Conservative on purpose: where there is doubt the refund counts as ours, or goes to a
 * person, and is never taken as absent.
 */
final class RefundMatching {

    private RefundMatching() {
    }

    /** How long before our first send a refund without a receipt may have been created and still be ours (legacy refunds only). */
    private static final Duration LEGACY_SLACK = Duration.ofMinutes(1);

    /**
     * @param legacySince when this refund was made before receipts were sent, the earliest time it
     *                    can have been sent; a refund of the same amount with no receipt, created
     *                    after it, is then taken as ours. Null for any refund that carried a receipt
     * @return the refund of ours that is there and has not failed, if any
     */
    static Optional<ProviderRefundEntry> ours(List<ProviderRefundEntry> listed, Refund refund, Instant legacySince) {
        String receipt = receiptOf(refund);
        String id = String.valueOf(refund.getId());
        return listed.stream()
                .filter(entry -> entry.status() != ProviderRefundStatus.FAILED)
                .filter(entry -> receipt.equals(entry.receipt())
                        || id.equals(entry.mandiRefundId())
                        || (refund.getProviderRefundId() != null
                                && refund.getProviderRefundId().equals(entry.providerRefundId()))
                        || (legacySince != null && entry.receipt() == null && entry.mandiRefundId() == null
                                && entry.amount() != null && entry.amount().compareTo(refund.getAmount()) == 0
                                && entry.createdAt() != null
                                && !entry.createdAt().isBefore(legacySince.minus(LEGACY_SLACK))))
                .findFirst();
    }

    /** Whether the provider shows a refund of ours at all, failed or not: ours exists, so it is not "nothing was ever made". */
    static boolean anyOfOurs(List<ProviderRefundEntry> listed, Refund refund) {
        String receipt = receiptOf(refund);
        String id = String.valueOf(refund.getId());
        return listed.stream().anyMatch(entry -> receipt.equals(entry.receipt()) || id.equals(entry.mandiRefundId())
                || (refund.getProviderRefundId() != null && refund.getProviderRefundId().equals(entry.providerRefundId())));
    }

    /** The receipt every refund of ours carries, followed by our refund id. */
    static final String RECEIPT_PREFIX = "mandi-refund-";

    /**
     * Whether a refund the provider lists is one of ours, of any of our refunds: it carries our receipt or
     * our id in its notes, or is a refund we recorded (a legacy one sent before receipts existed).
     */
    static boolean isOurs(ProviderRefundEntry entry, Set<String> ourProviderRefundIds) {
        return (entry.receipt() != null && entry.receipt().startsWith(RECEIPT_PREFIX))
                || entry.mandiRefundId() != null
                || (entry.providerRefundId() != null && ourProviderRefundIds.contains(entry.providerRefundId()));
    }

    /**
     * Refunds on the payment that moved, or may move, money to the payer and are not any refund of ours: made
     * in the provider's dashboard, by its support, or by anything else. Whatever the provider's wording of a
     * refusal was, one of these means the payer may already have been paid (D-110). A {@code failed} refund
     * moved nothing and is not counted.
     */
    static List<ProviderRefundEntry> foreign(List<ProviderRefundEntry> listed, Set<String> ourProviderRefundIds) {
        return listed.stream()
                .filter(entry -> entry.status() != ProviderRefundStatus.FAILED)
                .filter(entry -> !isOurs(entry, ourProviderRefundIds))
                .toList();
    }

    /**
     * {@link #foreign}, less the refunds a person has recorded as not this part's or that closed parts of ours
     * (D-110): exactly those ids, never a refund that merely looks like them.
     */
    static List<ProviderRefundEntry> foreign(List<ProviderRefundEntry> listed, Set<String> ourProviderRefundIds,
                                             Set<String> accountedFor) {
        return foreign(listed, ourProviderRefundIds).stream()
                .filter(entry -> entry.providerRefundId() == null || !accountedFor.contains(entry.providerRefundId()))
                .toList();
    }

    /** What the listed refunds with these provider ids that moved or may move money add up to. */
    static BigDecimal amountOf(List<ProviderRefundEntry> listed, Set<String> providerRefundIds) {
        return listed.stream()
                .filter(entry -> entry.status() != ProviderRefundStatus.FAILED)
                .filter(entry -> entry.providerRefundId() != null && providerRefundIds.contains(entry.providerRefundId()))
                .map(entry -> entry.amount() == null ? BigDecimal.ZERO : entry.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What our own refunds that moved or may move money add up to, as the provider lists them. */
    static BigDecimal explainedByOurs(List<ProviderRefundEntry> listed, Set<String> ourProviderRefundIds) {
        return listed.stream()
                .filter(entry -> entry.status() != ProviderRefundStatus.FAILED)
                .filter(entry -> isOurs(entry, ourProviderRefundIds))
                .map(entry -> entry.amount() == null ? BigDecimal.ZERO : entry.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Everything the provider lists on the payment that moved or may move money (not failed), whoever made it. */
    static BigDecimal listedTotal(List<ProviderRefundEntry> listed) {
        return listed.stream()
                .filter(entry -> entry.status() != ProviderRefundStatus.FAILED)
                .map(entry -> entry.amount() == null ? BigDecimal.ZERO : entry.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String receiptOf(Refund refund) {
        return RECEIPT_PREFIX + refund.getId();
    }
}
