package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentFacts;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.payment.service.RefundService.Withdrawable;
import com.costonomy.mp.payment.service.WithdrawalSources.Checked;
import com.costonomy.mp.payment.service.WithdrawalSources.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a withdrawal may take from each source, and how it is refused (D-110). */
class WithdrawalSourcesTest {

    private RefundService refunds;
    private RefundRepository refundRepository;
    private PaymentRepository payments;
    private PaymentProvider provider;
    private AuditService audit;
    private WithdrawalSources sources;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        refunds = mock(RefundService.class);
        refundRepository = mock(RefundRepository.class);
        payments = mock(PaymentRepository.class);
        provider = mock(PaymentProvider.class);
        audit = mock(AuditService.class);
        var tx = mock(TransactionTemplate.class);
        doAnswer(call -> {
            ((Consumer<Object>) call.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        sources = new WithdrawalSources(refunds, refundRepository, payments, provider, audit, tx, new AlertThrottle());
        when(refundRepository.unconfirmedOn(anyLong(), any())).thenReturn(BigDecimal.ZERO);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private static Withdrawable source(long paymentId, String available) {
        return new Withdrawable(paymentId, money(available), false);
    }

    private void paymentExists(long id) {
        var payment = new Payment();
        payment.setId(id);
        payment.setProviderPaymentId("pay_" + id);
        payment.setProviderOrderId("order_" + id);
        when(payments.findById(id)).thenReturn(Optional.of(payment));
    }

    private ProviderPaymentFacts facts(long id, ProviderPaymentStatus status, boolean captured, String amount,
                                       String refunded, Instant createdAt) {
        return new ProviderPaymentFacts("pay_" + id, "order_" + id, status, captured, money(amount), money(refunded),
                "upi", null, null, createdAt);
    }

    private void providerSays(long id, ProviderPaymentFacts facts) {
        paymentExists(id);
        when(provider.inspect("pay_" + id)).thenReturn(facts);
    }

    @Test
    @DisplayName("a captured payment with some refunded elsewhere can take what is left")
    void capacityIsAmountLessRefunded() {
        providerSays(1, facts(1, ProviderPaymentStatus.CAPTURED, true, "1000", "300", Instant.now()));

        var checked = sources.inspect(List.of(source(1, "1000")), money("500"));

        assertThat(checked).singleElement().satisfies(c -> {
            assertThat(c.verdict()).isEqualTo(Verdict.OK);
            assertThat(c.capacity()).isEqualByComparingTo("700");
        });
    }

    @Test
    @DisplayName("a payment the provider does not know is blocked PAYMENT_UNKNOWN and audited once")
    void unknownPaymentIsBlocked() {
        paymentExists(1);
        when(provider.inspect("pay_1")).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        when(payments.block(eq(1L), any(), eq("PAYMENT_UNKNOWN"))).thenReturn(1);

        var checked = sources.inspect(List.of(source(1, "400")), money("400"));

        assertThat(checked).singleElement().satisfies(c -> {
            assertThat(c.verdict()).isEqualTo(Verdict.BLOCKED);
            assertThat(c.reason()).isEqualTo("PAYMENT_UNKNOWN");
        });
        verify(audit, times(1)).record(any(), any(), eq("PAYMENT_REFUND_BLOCKED"), eq("PAYMENT"), eq(1L), any(),
                eq("PAYMENT_UNKNOWN"), anyString(), anyString());
    }

    @Test
    @DisplayName("two payments in a row the provider does not know, and no other answered, is a configuration fault: nothing blocked, all unavailable")
    void twoUnknownInARowIsAConfigurationFault() {
        for (long id = 1; id <= 3; id++) {
            paymentExists(id);
            when(provider.inspect("pay_" + id)).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        }

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400")), money("1200"));

        assertThat(checked).extracting(Checked::verdict).containsOnly(Verdict.UNAVAILABLE);
        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L, 3L);
        verify(payments, never()).block(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("two dead sources at the head do not lock the outlet out: the sources after them are asked, and when one answers the dead ones are blocked")
    void twoUnknownFollowedByHealthySourcesAreBlockedAndTheRestUsed() {
        for (long id = 1; id <= 2; id++) {
            paymentExists(id);
            when(provider.inspect("pay_" + id)).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        }
        providerSays(3, facts(3, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        providerSays(4, facts(4, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        when(payments.block(anyLong(), any(), anyString())).thenReturn(1);

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400"), source(4, "400")),
                money("800"));

        assertThat(checked).extracting(Checked::paymentId, Checked::verdict).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(1L, Verdict.BLOCKED), org.assertj.core.groups.Tuple.tuple(2L, Verdict.BLOCKED),
                org.assertj.core.groups.Tuple.tuple(3L, Verdict.OK), org.assertj.core.groups.Tuple.tuple(4L, Verdict.OK));
        verify(payments).block(eq(1L), any(), eq("PAYMENT_UNKNOWN"));
        verify(payments).block(eq(2L), any(), eq("PAYMENT_UNKNOWN"));
    }

    @Test
    @DisplayName("a source the provider could not be reached for does not prove the keys work: two unknown and then an outage is still a configuration fault")
    void anUnreachableSourceIsNotAnAnswer() {
        for (long id = 1; id <= 2; id++) {
            paymentExists(id);
            when(provider.inspect("pay_" + id)).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        }
        paymentExists(3);
        when(provider.inspect("pay_3")).thenThrow(PaymentProviderException.unreachable("down", new RuntimeException()));
        paymentExists(4);
        when(provider.inspect("pay_4")).thenThrow(new PaymentProviderException("slow down", false, "429"));

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400"), source(4, "400")),
                money("1600"));

        assertThat(checked).extracting(Checked::verdict).containsOnly(Verdict.UNAVAILABLE);
        verify(payments, never()).block(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("a payment past the refund window, or of another order, is an answer: the provider was reached, so the unknown ones before it are gone")
    void aWindowPassedSourceIsAnAnswer() {
        for (long id = 1; id <= 2; id++) {
            paymentExists(id);
            when(provider.inspect("pay_" + id)).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        }
        providerSays(3, facts(3, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now().minus(Duration.ofDays(400))));
        when(payments.block(anyLong(), any(), anyString())).thenReturn(1);

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400")), money("1200"));

        assertThat(checked).extracting(Checked::verdict).containsExactlyInAnyOrder(
                Verdict.BLOCKED, Verdict.BLOCKED, Verdict.UNAVAILABLE);
        verify(payments).block(eq(1L), any(), eq("PAYMENT_UNKNOWN"));
        verify(payments).block(eq(2L), any(), eq("PAYMENT_UNKNOWN"));
    }

    @Test
    @DisplayName("an unknown payment followed by one the provider does answer for is a payment that is gone: blocked; one that came after a fault is not")
    void unknownThenAnsweredIsBlocked() {
        paymentExists(1);
        when(provider.inspect("pay_1")).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        providerSays(2, facts(2, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        providerSays(3, facts(3, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        paymentExists(4);
        when(provider.inspect("pay_4")).thenThrow(new PaymentProviderException("gone", false, "NOT_FOUND"));
        when(payments.block(anyLong(), any(), anyString())).thenReturn(1);

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(4, "400"), source(3, "400")), money("1600"));

        assertThat(checked).extracting(Checked::verdict).containsExactlyInAnyOrder(
                Verdict.BLOCKED, Verdict.OK, Verdict.BLOCKED, Verdict.OK);
        verify(payments).block(eq(1L), any(), eq("PAYMENT_UNKNOWN"));
        verify(payments).block(eq(4L), any(), eq("PAYMENT_UNKNOWN"));
    }

    @Test
    @DisplayName("the provider is asked until what the plan will use covers the request: what a source gives is capped by refunds of ours not yet counted")
    void coveredUsesTheSameFigureAsThePlan() {
        providerSays(1, facts(1, ProviderPaymentStatus.CAPTURED, true, "1000", "300", Instant.now()));
        providerSays(2, facts(2, ProviderPaymentStatus.CAPTURED, true, "300", "0", Instant.now()));
        when(refundRepository.unconfirmedOn(eq(1L), any())).thenReturn(money("400"));

        // Source 1 holds 600 of the wallet's refund money, the provider can take 700, but 400 of ours is in flight: 300.
        var checked = sources.inspect(List.of(source(1, "600"), source(2, "300")), money("600"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L);
        assertThat(checked).extracting(Checked::verdict).containsOnly(Verdict.OK);
    }

    @Test
    @DisplayName("a source another caller already blocked is not audited a second time")
    void alreadyBlockedIsNotAuditedAgain() {
        paymentExists(1);
        when(provider.inspect("pay_1")).thenThrow(new PaymentProviderException("gone", false, "404"));
        when(payments.block(eq(1L), any(), anyString())).thenReturn(0);

        sources.inspect(List.of(source(1, "400")), money("400"));

        verify(audit, never()).record(any(), any(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("captured=false or authorized is NOT_CAPTURED; fully refunded is REFUNDED_ELSEWHERE; both blocked")
    void notCapturedAndRefundedElsewhereAreBlocked() {
        providerSays(1, facts(1, ProviderPaymentStatus.AUTHORIZED, false, "400", "0", Instant.now()));
        providerSays(2, facts(2, ProviderPaymentStatus.REFUNDED, true, "400", "400", Instant.now()));
        providerSays(3, facts(3, ProviderPaymentStatus.CAPTURED, true, "400", "400", Instant.now()));
        when(payments.block(anyLong(), any(), anyString())).thenReturn(1);

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400")), money("1200"));

        assertThat(checked).extracting(Checked::reason).containsExactly("NOT_CAPTURED", "REFUNDED_ELSEWHERE", "REFUNDED_ELSEWHERE");
        assertThat(checked).extracting(Checked::verdict).containsOnly(Verdict.BLOCKED);
        verify(payments).block(eq(1L), any(), eq("NOT_CAPTURED"));
        verify(payments).block(eq(2L), any(), eq("REFUNDED_ELSEWHERE"));
    }

    @Test
    @DisplayName("an unreachable provider, a mismatched order and a payment past the window are skipped, never blocked")
    void unavailableIsNeverBlocked() {
        paymentExists(1);
        when(provider.inspect("pay_1")).thenThrow(PaymentProviderException.unreachable("down", new RuntimeException()));
        paymentExists(2);
        when(provider.inspect("pay_2")).thenThrow(new PaymentProviderException("slow down", false, "429"));
        paymentExists(3);
        when(provider.inspect("pay_3")).thenThrow(new PaymentProviderException("refused", false, "401"));
        providerSays(4, new ProviderPaymentFacts("pay_4", "order_someone_else", ProviderPaymentStatus.CAPTURED, true,
                money("400"), money("0"), "upi", null, null, Instant.now()));
        providerSays(5, facts(5, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now().minus(Duration.ofDays(400))));

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400"),
                source(4, "400"), source(5, "400")), money("2000"));

        assertThat(checked).extracting(Checked::verdict).containsOnly(Verdict.UNAVAILABLE);
        assertThat(checked).extracting(Checked::reason).containsExactly(
                "PROVIDER_UNREACHABLE", "PROVIDER_UNREACHABLE", "PROVIDER_UNREACHABLE", "MISMATCH", "WINDOW_PASSED");
        verify(payments, never()).block(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("the provider is asked only until enough is covered")
    void stopsOnceCovered() {
        for (long id = 1; id <= 3; id++) {
            providerSays(id, facts(id, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        }

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "400"), source(3, "400")), money("400"));

        assertThat(checked).hasSize(1);
        verify(provider, times(1)).inspect(anyString());
    }

    @Test
    @DisplayName("at most ten sources are asked about, however many there are")
    void atMostTenSources() {
        var candidates = new ArrayList<Withdrawable>();
        for (long id = 1; id <= 15; id++) {
            providerSays(id, facts(id, ProviderPaymentStatus.CAPTURED, true, "10", "0", Instant.now()));
            candidates.add(source(id, "10"));
        }

        var checked = sources.inspect(candidates, money("1000"));

        assertThat(checked).hasSize(WithdrawalSources.MAX_SOURCES);
        verify(provider, times(WithdrawalSources.MAX_SOURCES)).inspect(anyString());
    }

    @Test
    @DisplayName("a spent time budget stops the asking")
    void budgetStopsTheAsking() {
        providerSays(1, facts(1, ProviderPaymentStatus.CAPTURED, true, "400", "0", Instant.now()));
        sources.budget = Duration.ofMillis(-1);

        assertThat(sources.inspect(List.of(source(1, "400")), money("400"))).isEmpty();
        verify(provider, never()).inspect(anyString());
    }

    @Test
    @DisplayName("a blocked source is never asked about again")
    void blockedSourcesAreSkipped() {
        var checked = sources.inspect(List.of(new Withdrawable(1L, money("400"), true)), money("400"));

        assertThat(checked).isEmpty();
        verify(provider, never()).inspect(anyString());
    }

    @Test
    @DisplayName("with the local switch off nothing is asked and each source is taken at its ledger figure")
    void precheckCanBeSwitchedOff() {
        sources.precheckEnabled = false;

        var checked = sources.inspect(List.of(source(1, "400"), source(2, "250")), money("650"));

        assertThat(checked).extracting(Checked::capacity).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(money("400"), money("250"));
        verify(provider, never()).inspect(anyString());
    }

    private Checked ok(long id, String capacity, Instant at) {
        return new Checked(id, money("400"), Verdict.OK, money(capacity), at, null);
    }

    @Test
    @DisplayName("what is allowed: the ledger figure capped by the provider's capacity less our unconfirmed refunds, blocked and unchecked money counted apart")
    void allowedCapsAndCountsTheRest() {
        Instant at = Instant.parse("2026-09-30T10:00:00Z");
        var fresh = List.of(source(1, "400"), source(2, "400"), source(3, "400"), source(4, "400"), source(5, "400"),
                new Withdrawable(6L, money("400"), true));
        var checked = List.of(
                ok(1, "1000", at),
                new Checked(2L, money("400"), Verdict.BLOCKED, null, at, "PAYMENT_UNKNOWN"),
                new Checked(3L, money("400"), Verdict.UNAVAILABLE, null, at, "PROVIDER_UNREACHABLE"),
                // 4 was credited after it was asked about: not checked, so not used.
                ok(5, "300", at));
        when(refundRepository.unconfirmedOn(eq(5L), any())).thenReturn(money("100"));

        var plan = sources.allowed(fresh, checked, money("500"));

        assertThat(plan.parts()).extracting(WithdrawalSources.Part::paymentId).containsExactly(1L, 5L);
        assertThat(plan.parts().get(0).allowed()).isEqualByComparingTo("400");
        assertThat(plan.parts().get(1).allowed()).isEqualByComparingTo("200");
        // 600 usable, but the wallet holds 500.
        assertThat(plan.withdrawableNow()).isEqualByComparingTo("500");
        // 2 blocked by this check and 6 blocked already.
        assertThat(plan.blocked()).isEqualByComparingTo("800");
        // 3 unreachable and 4 unchecked.
        assertThat(plan.unavailable()).isEqualByComparingTo("800");
        // Unconfirmed refunds are counted from just before the read.
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refundRepository).unconfirmedOn(eq(5L), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(at.minusSeconds(5));
    }

    @Test
    @DisplayName("refunds of ours the provider may not have counted can take a source to nothing, never below")
    void unconfirmedNeverGoesNegative() {
        Instant at = Instant.now();
        when(refundRepository.unconfirmedOn(eq(1L), any())).thenReturn(money("900"));

        var plan = sources.allowed(List.of(source(1, "400")), List.of(ok(1, "300", at)), money("400"));

        assertThat(plan.parts()).isEmpty();
        assertThat(plan.withdrawableNow()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the refusal says what can go back, what is blocked and what could not be checked, in words a person can act on")
    void exceededSaysWhatCanGo() {
        var blocked = new WithdrawalSources.Plan(List.of(), money("800"), money("400"), money("0"));
        var unreachable = new WithdrawalSources.Plan(List.of(), money("800"), money("0"), money("400"));
        var plain = new WithdrawalSources.Plan(List.of(), money("100"), money("0"), money("0"));
        var nothingBlocked = new WithdrawalSources.Plan(List.of(), money("0"), money("400"), money("0"));
        var nothingUnreachable = new WithdrawalSources.Plan(List.of(), money("0"), money("0"), money("400"));
        var nothingAtAll = new WithdrawalSources.Plan(List.of(), money("0"), money("0"), money("0"));

        var one = WithdrawalSources.exceeded(money("1200"), blocked);
        assertThat(one.code()).isEqualTo(ErrorCode.WITHDRAWAL_EXCEEDS_REFUNDABLE);
        assertThat(one.getMessage()).isEqualTo("₹800.00 can go back to your card or bank now. ₹400.00 can't: its original "
                + "payment can no longer be refunded. That money stays in your wallet for orders.");
        assertThat(one.details()).containsEntry("reason", "SOURCE_BLOCKED");
        assertThat((BigDecimal) one.details().get("withdrawableNow")).isEqualByComparingTo("800");
        assertThat((BigDecimal) one.details().get("requested")).isEqualByComparingTo("1200");

        assertThat(WithdrawalSources.exceeded(money("1200"), unreachable).getMessage()).isEqualTo(
                "₹800.00 can go back to your card or bank now. ₹400.00 couldn't be checked with the payment provider "
                        + "just now. Try again in a few minutes.");
        assertThat(WithdrawalSources.exceeded(money("1200"), unreachable).details()).containsEntry("reason", "PROVIDER_UNREACHABLE");
        assertThat(WithdrawalSources.exceeded(money("300"), plain).getMessage()).isEqualTo(
                "₹100.00 can go back to your card or bank. The rest of your balance can be spent on orders.");
        assertThat(WithdrawalSources.exceeded(money("300"), nothingBlocked).getMessage()).startsWith("Nothing can go back to your card or bank right now.")
                .contains("can no longer be refunded");
        assertThat(WithdrawalSources.exceeded(money("300"), nothingUnreachable).getMessage()).contains("Try again in a few minutes");
        assertThat(WithdrawalSources.exceeded(money("300"), nothingAtAll).details()).containsEntry("reason", "NO_REFUND_MONEY");
    }

    @Test
    @DisplayName("the breaker opens on refunds refused for our balance or credentials in the last half hour, and only those")
    void breaker() {
        when(refundRepository.countFailuresSince(any(), any())).thenReturn(0L);
        sources.requireOpen(1L);

        when(refundRepository.countFailuresSince(any(), any())).thenReturn(1L);
        assertThatThrownBy(() -> sources.requireOpen(1L)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.WITHDRAWALS_PAUSED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ProviderFailureKind>> kinds = ArgumentCaptor.forClass(java.util.Collection.class);
        var since = ArgumentCaptor.forClass(Instant.class);
        verify(refundRepository, times(2)).countFailuresSince(kinds.capture(), since.capture());
        assertThat(kinds.getValue()).containsExactlyInAnyOrder(ProviderFailureKind.INSUFFICIENT_BALANCE, ProviderFailureKind.CONFIG);
        assertThat(Duration.between(since.getValue(), Instant.now())).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
    }

    // ── F3: the check says how much it looked at, and looks at the sources that can cover the request first ──

    private List<Withdrawable> manySources(int count, String each) {
        var list = new ArrayList<Withdrawable>();
        for (int i = 1; i <= count; i++) {
            providerSays(i, facts(i, ProviderPaymentStatus.CAPTURED, true, "1000", "0", Instant.now()));
            list.add(source(i, each));
        }
        return list;
    }

    @Test
    @DisplayName("F3/D-110: the sources are asked oldest credit first: a small old credit is drawn before a large newer one (10, 20 and 500, withdraw 30: the 10 and the 20, never the 500)")
    void oldestSourcesAreAskedAndDrawnFirst() {
        var list = new ArrayList<Withdrawable>();
        for (int i = 1; i <= 3; i++) {
            providerSays(i, facts(i, ProviderPaymentStatus.CAPTURED, true, "1000", "0", Instant.now()));
        }
        list.add(source(1, "10"));
        list.add(source(2, "20"));
        list.add(source(3, "500"));

        var checked = sources.inspect(list, money("30"));
        var plan = sources.allowed(list, checked, money("530"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L);
        verify(provider, never()).inspect("pay_3");
        assertThat(plan.parts()).extracting(WithdrawalSources.Part::paymentId).containsExactly(1L, 2L);
        assertThat(plan.parts()).extracting(part -> part.allowed().intValueExact()).containsExactly(10, 20);
    }

    @Test
    @DisplayName("F3/D-110: a request larger than the small old credits is still asked oldest first, and the large newer one is asked last")
    void oldestFirstThenTheLargeOne() {
        var list = new ArrayList<Withdrawable>();
        for (int i = 1; i <= 3; i++) {
            providerSays(i, facts(i, ProviderPaymentStatus.CAPTURED, true, "1000", "0", Instant.now()));
        }
        list.add(source(1, "10"));
        list.add(source(2, "20"));
        list.add(source(3, "500"));

        var checked = sources.inspect(list, money("400"));
        var plan = sources.allowed(list, checked, money("530"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L, 3L);
        assertThat(plan.parts()).extracting(WithdrawalSources.Part::paymentId).containsExactly(1L, 2L, 3L);
    }

    /** {@code small} sources of {@code eachSmall}, oldest, then {@code large} sources of {@code eachLarge}, newest. */
    private List<Withdrawable> smallThenLarge(int small, String eachSmall, int large, String eachLarge) {
        var list = new ArrayList<Withdrawable>();
        for (int i = 1; i <= small + large; i++) {
            providerSays(i, facts(i, ProviderPaymentStatus.CAPTURED, true, "1000", "0", Instant.now()));
            list.add(source(i, i <= small ? eachSmall : eachLarge));
        }
        return list;
    }

    @Test
    @DisplayName("F3/D-110: the largest sources are asked first ONLY when the oldest ten cannot cover the request at all; then the request is satisfiable, and the plan still takes its parts in the ledger's order")
    void largestFirstOnlyWhenTheOldestTenCannotCoverIt() {
        var list = smallThenLarge(10, "10", 2, "500");

        var checked = sources.inspect(list, money("600"));
        var plan = sources.allowed(list, checked, money("1100"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(11L, 12L);
        assertThat(plan.withdrawableNow()).isEqualByComparingTo("1000");
        assertThat(plan.parts()).extracting(WithdrawalSources.Part::paymentId).containsExactly(11L, 12L);
        assertThat(plan.uncheckedSources()).isEqualTo(10);
    }

    @Test
    @DisplayName("F3/D-110: when the oldest ten can cover the request the order is the ledger's, however large a newer source is")
    void oldestTenThatCanCoverKeepTheLedgersOrder() {
        var list = smallThenLarge(10, "100", 2, "500");

        var checked = sources.inspect(list, money("300"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L, 3L);
        verify(provider, never()).inspect("pay_11");
    }

    @Test
    @DisplayName("F3/D-110: the order is the ledger's at exactly the edge: the oldest ten hold exactly the amount (oldest first), one rupee more than they hold (largest first)")
    void theEdgeOfTheFallback() {
        var exact = WithdrawalSources.inAskingOrder(smallThenLarge(10, "10", 2, "500"), money("100"));
        var over = WithdrawalSources.inAskingOrder(smallThenLarge(10, "10", 2, "500"), money("101"));

        assertThat(exact).extracting(Withdrawable::paymentId).startsWith(1L, 2L, 3L);
        assertThat(over).extracting(Withdrawable::paymentId).startsWith(11L, 12L, 1L);
    }

    @Test
    @DisplayName("F3/D-110: blocked sources do not count among the oldest ten, and up to ten open sources are always asked in ledger order")
    void blockedSourcesAreNotAmongTheOldestTen() {
        var list = new ArrayList<Withdrawable>();
        list.add(new Withdrawable(90L, money("5000"), true));
        list.addAll(smallThenLarge(10, "10", 1, "500"));
        // Ten open sources hold 100, the request is 300: they cannot cover it, so the large one is asked first.
        assertThat(WithdrawalSources.inAskingOrder(list, money("300"))).extracting(Withdrawable::paymentId).startsWith(90L, 11L);
        // Ten or fewer open sources: all are asked anyway, in ledger order.
        var few = new ArrayList<Withdrawable>(smallThenLarge(9, "10", 1, "500"));
        assertThat(WithdrawalSources.inAskingOrder(few, money("300"))).extracting(Withdrawable::paymentId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    @DisplayName("F3: sources that can give the same are asked in the ledger's order, oldest credit first")
    void equalSourcesKeepTheLedgersOrder() {
        var list = manySources(4, "100");

        var checked = sources.inspect(list, money("250"));

        assertThat(checked).extracting(Checked::paymentId).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("F3: the plan and the refusal say how many sources were checked and how many were not, and that more may be withdrawable")
    void refusalCountsCheckedAndUncheckedSources() {
        var list = manySources(12, "10");

        var checked = sources.inspect(list, money("200"));
        var plan = sources.allowed(list, checked, money("120"));

        assertThat(checked).hasSize(WithdrawalSources.MAX_SOURCES);
        assertThat(plan.withdrawableNow()).isEqualByComparingTo("100");
        assertThat(plan.checkedSources()).isEqualTo(10);
        assertThat(plan.uncheckedSources()).isEqualTo(2);
        var refusal = WithdrawalSources.exceeded(money("200"), plan);
        assertThat(refusal.details()).containsEntry("checkedSources", 10).containsEntry("uncheckedSources", 2)
                .containsEntry("requested", money("200"));
        assertThat((BigDecimal) refusal.details().get("withdrawableNow")).isEqualByComparingTo("100");
        assertThat(refusal.getMessage()).contains("We checked 10 of your 12 payments this time, so more may be withdrawable in a further step.");
    }

    @Test
    @DisplayName("F3: when everything was checked the refusal says nothing about more, and the counts are still there")
    void nothingUncheckedSaysNothingAboutMore() {
        var list = manySources(3, "100");

        var checked = sources.inspect(list, money("1000"));
        var plan = sources.allowed(list, checked, money("300"));
        var refusal = WithdrawalSources.exceeded(money("1000"), plan);

        assertThat(refusal.details()).containsEntry("checkedSources", 3).containsEntry("uncheckedSources", 0);
        assertThat(refusal.getMessage()).doesNotContain("further step");
    }

    @Test
    @DisplayName("F3: when the time budget is spent before any source is read, all of them are unchecked, and it says so; a source already known to be blocked counts as neither")
    void spentBudgetLeavesEverythingUnchecked() {
        var list = new ArrayList<>(manySources(3, "100"));
        list.add(new Withdrawable(99L, money("100"), true));
        sources.budget = Duration.ofSeconds(-1);

        var checked = sources.inspect(list, money("300"));
        var plan = sources.allowed(list, checked, money("400"));
        var refusal = WithdrawalSources.exceeded(money("300"), plan);

        assertThat(checked).isEmpty();
        assertThat(plan.checkedSources()).isZero();
        assertThat(plan.uncheckedSources()).isEqualTo(3);
        assertThat(refusal.details()).containsEntry("uncheckedSources", 3).containsEntry("checkedSources", 0);
        assertThat(refusal.getMessage()).contains("We checked 0 of your 3 payments this time");
    }
}
