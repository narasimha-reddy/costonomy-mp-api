package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundEntry;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which refund at the provider is ours (D-110): what "none of ours" rests on. */
class RefundMatchingTest {

    private static Refund refund(long id, String amount) {
        var refund = new Refund();
        refund.setId(id);
        refund.setAmount(new BigDecimal(amount));
        return refund;
    }

    private static ProviderRefundEntry entry(String id, String amount, ProviderRefundStatus status,
                                             String receipt, String notesId, Instant at) {
        return new ProviderRefundEntry(id, new BigDecimal(amount), status, receipt, notesId, at);
    }

    @Test
    @DisplayName("ours is found by receipt, by our id in the notes, or by the provider id we already recorded")
    void oursByReceiptNotesOrRecordedId() {
        var refund = refund(7, "200.00");
        assertThat(RefundMatching.ours(List.of(entry("r1", "200", ProviderRefundStatus.COMPLETED, "mandi-refund-7", null, null)),
                refund, null)).isPresent();
        assertThat(RefundMatching.ours(List.of(entry("r1", "200", ProviderRefundStatus.PENDING, null, "7", null)),
                refund, null)).isPresent();
        refund.setProviderRefundId("r9");
        assertThat(RefundMatching.ours(List.of(entry("r9", "200", ProviderRefundStatus.PENDING, null, null, null)),
                refund, null)).isPresent();
    }

    @Test
    @DisplayName("someone else's refund is not ours, whatever its amount, when ours carried a receipt")
    void foreignIsNotOurs() {
        var refund = refund(7, "200.00");
        assertThat(RefundMatching.ours(List.of(
                entry("r1", "200", ProviderRefundStatus.COMPLETED, null, null, Instant.now()),
                entry("r2", "200", ProviderRefundStatus.COMPLETED, "mandi-refund-70", "70", Instant.now())),
                refund, null)).isEmpty();
    }

    @Test
    @DisplayName("a refund the provider reports failed is not money that left, so it is not returned as a match")
    void failedIsNotAMatch() {
        var refund = refund(7, "200.00");
        var list = List.of(entry("r1", "200", ProviderRefundStatus.FAILED, "mandi-refund-7", "7", null));
        assertThat(RefundMatching.ours(list, refund, null)).isEmpty();
        // ...but it does exist, which the caller may want to know.
        assertThat(RefundMatching.anyOfOurs(list, refund)).isTrue();
    }

    @Test
    @DisplayName("a refund from before receipts existed is found by amount and time, and only then")
    void legacyIsFoundByAmountAndTime() {
        var refund = refund(7, "200.00");
        Instant since = Instant.parse("2026-09-01T10:00:00Z");
        var sameAmountLater = entry("r1", "200.00", ProviderRefundStatus.COMPLETED, null, null, since.plusSeconds(30));
        var sameAmountEarlier = entry("r2", "200.00", ProviderRefundStatus.COMPLETED, null, null, since.minusSeconds(3600));
        var otherAmount = entry("r3", "199.00", ProviderRefundStatus.COMPLETED, null, null, since.plusSeconds(30));

        assertThat(RefundMatching.ours(List.of(sameAmountLater), refund, since)).isPresent();
        assertThat(RefundMatching.ours(List.of(sameAmountEarlier), refund, since)).isEmpty();
        assertThat(RefundMatching.ours(List.of(otherAmount), refund, since)).isEmpty();
        // Not legacy: never guessed.
        assertThat(RefundMatching.ours(List.of(sameAmountLater), refund, null)).isEmpty();
    }

    @Test
    @DisplayName("F1: a refund on the payment is foreign unless it carries our receipt or notes or is one we recorded; a failed one moved nothing and is neither")
    void foreignRefundsAndWhatOursExplain() {
        var mine = entry("r1", "200", ProviderRefundStatus.COMPLETED, "mandi-refund-5", null, null);
        var mineByNotes = entry("r2", "50", ProviderRefundStatus.PENDING, null, "6", null);
        var recorded = entry("r3", "30", ProviderRefundStatus.COMPLETED, null, null, null);
        var dashboard = entry("r4", "10", ProviderRefundStatus.COMPLETED, null, null, null);
        var support = entry("r5", "400", ProviderRefundStatus.PENDING, "support-ticket-9", null, null);
        var failedDashboard = entry("r6", "999", ProviderRefundStatus.FAILED, null, null, null);
        var list = List.of(mine, mineByNotes, recorded, dashboard, support, failedDashboard);
        var recordedIds = java.util.Set.of("r3");

        assertThat(RefundMatching.foreign(list, recordedIds)).containsExactly(dashboard, support);
        assertThat(RefundMatching.explainedByOurs(list, recordedIds)).isEqualByComparingTo("280");
        assertThat(RefundMatching.foreign(List.of(mine, mineByNotes, recorded, failedDashboard), recordedIds)).isEmpty();
        assertThat(RefundMatching.foreign(List.of(), java.util.Set.of())).isEmpty();
        assertThat(RefundMatching.explainedByOurs(List.of(), java.util.Set.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("R3-1: refunds recorded as not a part's, or claimed by parts of ours, are left out by their ids and their own amounts, and nothing else is")
    void accountedForRefundsAreLeftOutByIdOnly() {
        var goodwill = entry("r1", "100", ProviderRefundStatus.COMPLETED, null, null, null);
        var lookalike = entry("r2", "100", ProviderRefundStatus.COMPLETED, null, null, null);
        var failed = entry("r3", "100", ProviderRefundStatus.FAILED, null, null, null);
        var list = List.of(goodwill, lookalike, failed);

        // Only r1: a refund of the same amount, made the same way, is not it.
        assertThat(RefundMatching.foreign(list, java.util.Set.of(), java.util.Set.of("r1"))).containsExactly(lookalike);
        assertThat(RefundMatching.foreign(list, java.util.Set.of(), java.util.Set.of("r1", "r2"))).isEmpty();
        assertThat(RefundMatching.foreign(list, java.util.Set.of(), java.util.Set.of())).containsExactly(goodwill, lookalike);
        assertThat(RefundMatching.amountOf(list, java.util.Set.of("r1"))).isEqualByComparingTo("100");
        assertThat(RefundMatching.amountOf(list, java.util.Set.of("r1", "r2", "r3"))).describedAs("a failed refund moved nothing")
                .isEqualByComparingTo("200");
        assertThat(RefundMatching.amountOf(list, java.util.Set.of("unknown"))).isEqualByComparingTo("0");
    }
}
