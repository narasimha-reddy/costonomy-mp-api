package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentFacts;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundEntry;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule of an exclusion (each below the part, together below it) and the room invariant it leaves out (D-110). */
class ExclusionAndRoomRulesTest {

    private static ProviderRefundEntry refund(String id, String amount, ProviderRefundStatus status) {
        return new ProviderRefundEntry(id, amount == null ? null : new BigDecimal(amount), status, null, null, Instant.now());
    }

    private static ProviderRefundEntry done(String id, String amount) {
        return refund(id, amount, ProviderRefundStatus.COMPLETED);
    }

    private static ProviderPaymentFacts facts(String amount, String refunded) {
        return new ProviderPaymentFacts("pay_1", "order_1", ProviderPaymentStatus.CAPTURED, true, new BigDecimal(amount),
                refunded == null ? null : new BigDecimal(refunded), "upi", null, null, Instant.now());
    }

    private static final BigDecimal PART_50 = new BigDecimal("50.00");

    @Test
    @DisplayName("an exclusion is valid only while each refund is worth strictly less than the part and all together strictly less")
    void exclusionsStayBelowThePart() {
        var listed = List.of(done("a", "30.00"), done("b", "30.00"), done("c", "20.00"), done("d", "20.00"), done("e", "50.00"),
                done("f", "49.99"), done("g", "25.00"), done("h", "24.99"), done("i", "25.00"), done("z", "0.01"));

        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("a"))).isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("a", "b"))).describedAs("30 + 30 >= 50").isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("c", "d"))).describedAs("20 + 20 < 50").isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("e"))).describedAs("exactly the part").isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("f"))).describedAs("the part less a paisa").isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("g", "i"))).describedAs("25 + 25 = 50: not below").isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("g", "h"))).describedAs("25 + 24.99 < 50").isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("f", "z"))).describedAs("49.99 + 0.01 = 50").isFalse();
    }

    @Test
    @DisplayName("an id that is not listed and a refund without an amount cannot be judged: the rule fails; a refund that FAILED moved no money and counts as nothing (F3)")
    void whatCannotBeJudgedFails() {
        var listed = List.of(done("a", "10.00"), refund("failed", "10.00", ProviderRefundStatus.FAILED), refund("none", null, ProviderRefundStatus.COMPLETED),
                refund("pending", "20.00", ProviderRefundStatus.PENDING), done("big", "45.00"));

        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("ghost"))).isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("a", "ghost"))).isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("none"))).isFalse();
        // F3: the failed refund is worth 0, whatever its amount says: it neither fails the rule nor adds to the sum.
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("failed"))).isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("failed", "big"))).describedAs("0 + 45 < 50").isTrue();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("a", "failed", "big"))).describedAs("10 + 0 + 45 >= 50").isFalse();
        // A refund still pending may yet be processed: it counts at its amount.
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("pending", "big"))).describedAs("20 + 45 >= 50").isFalse();
    }

    @Test
    @DisplayName("the clause 'each refund below the part' is the stated rule although the sum implies it for amounts that are not negative: a negative amount cannot hide a refund worth the part")
    void eachRefundBelowThePartIsAClauseOfItsOwn() {
        var listed = List.of(done("neg", "-30.00"), done("e", "50.00"), done("a", "10.00"));

        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("neg", "e"))).describedAs("sum 20 < 50, but e is worth the part").isFalse();
        assertThat(WithdrawalReversalService.exclusionsBelowPart(PART_50, listed, List.of("neg", "a"))).isTrue();
    }

    @Test
    @DisplayName("the room total is the larger of the provider's figure and its list, less what is excluded; the rule applies only where something refunded is not ours")
    void theRoomRule() {
        var part = new BigDecimal("400.00");
        var none = Set.<String>of();
        var foreign = List.of(done("f", "700.00"));

        // Agreeing figures: 700 + 400 > 1000.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, foreign, facts("1000.00", "700.00"), none, BigDecimal.ZERO)).isTrue();
        // The figure behind the list (R1): the list's 700 counts.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, foreign, facts("1000.00", "500.00"), none, BigDecimal.ZERO)).isTrue();
        // The figure ahead of the list (R2): the figure's 700 counts, although the list shows 500.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, List.of(done("f", "500.00")), facts("1000.00", "700.00"), none, BigDecimal.ZERO)).isTrue();
        // Both low enough: room.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, List.of(done("f", "500.00")), facts("1000.00", "500.00"), none, BigDecimal.ZERO)).isFalse();
        // Exactly the payment with the part, and one paisa over.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, List.of(done("f", "600.00")), facts("1000.00", "600.00"), none, BigDecimal.ZERO)).isFalse();
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, List.of(done("f", "600.01")), facts("1000.00", "600.01"), none, BigDecimal.ZERO)).isTrue();
        // A failed refund on the list is no money (R3).
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part,
                List.of(done("f", "100.00"), refund("x", "950.00", ProviderRefundStatus.FAILED)), facts("1000.00", "100.00"), none, BigDecimal.ZERO)).isFalse();
        // What is validly excluded is left out: payment 1000 fully withdrawn, a goodwill refund of 10 (F2).
        assertThat(WithdrawalReversalService.payerAlreadyCovered(new BigDecimal("1000.00"), List.of(done("g", "10.00")), facts("1000.00", "10.00"),
                none, new BigDecimal("10.00"))).isFalse();
        // The same refund not excluded: covered.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(new BigDecimal("1000.00"), List.of(done("g", "10.00")), facts("1000.00", "10.00"),
                none, BigDecimal.ZERO)).isTrue();
        // Refunds of ours only: the rule does not apply (an over-refund of ours is put back as before).
        var ours = new ProviderRefundEntry("o", new BigDecimal("400.00"), ProviderRefundStatus.COMPLETED, "mandi-refund-9", null, Instant.now());
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, List.of(ours), facts("400.00", "400.00"), none, BigDecimal.ZERO)).isFalse();
        // No figure for the payment: nothing to measure against.
        assertThat(WithdrawalReversalService.payerAlreadyCovered(part, foreign, null, none, BigDecimal.ZERO)).isFalse();
    }
}
