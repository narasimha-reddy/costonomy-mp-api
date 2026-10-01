package com.costonomy.mp.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a reversed refund is looked for at the provider (D-110): 10 minutes, 1 hour and 6 hours after the reversal,
 * then daily for 14 days. Worked out from two persisted times only.
 */
class ReversalAuditScheduleTest {

    private static final Instant REVERSED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("nothing is due in the first ten minutes, and then a look is due until it has been made")
    void firstLookAtTenMinutes() {
        assertThat(WithdrawalReversalService.auditDue(REVERSED, REVERSED, REVERSED.plus(Duration.ofMinutes(9)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, REVERSED, REVERSED.plus(Duration.ofMinutes(10)))).isTrue();
        // Made at 11 minutes: the next is at an hour.
        Instant looked = REVERSED.plus(Duration.ofMinutes(11));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofHours(1)))).isTrue();
    }

    @Test
    @DisplayName("then six hours, then daily; a look that was late still counts for the point it passed, and no point is skipped")
    void frontLoadedThenDaily() {
        Instant looked = REVERSED.plus(Duration.ofHours(1)).plusSeconds(30);
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofHours(5)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofHours(6)))).isTrue();
        looked = REVERSED.plus(Duration.ofHours(6));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofHours(23)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofDays(1)))).isTrue();
        // The process was down for three days: one look is due, not three.
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(1)))).isTrue();
        looked = REVERSED.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(2));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofDays(3)).plus(Duration.ofHours(5)))).isFalse();
    }

    @Test
    @DisplayName("the last look is at fourteen days and nothing is due after it")
    void endsAtFourteenDays() {
        Instant looked = REVERSED.plus(Duration.ofDays(13)).plus(Duration.ofMinutes(1));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofDays(14)))).isTrue();
        looked = REVERSED.plus(Duration.ofDays(14)).plus(Duration.ofMinutes(1));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, looked, REVERSED.plus(Duration.ofDays(30)))).isFalse();
        // Never looked at (a row from before the schedule): due as soon as any point has passed.
        assertThat(WithdrawalReversalService.auditDue(REVERSED, null, REVERSED.plus(Duration.ofMinutes(10)))).isTrue();
    }

    @Test
    @DisplayName("R3-3: a suspicion is read again five minutes after it was noted, on whatever day, and not before")
    void aSuspicionIsDueAfterFiveMinutes() {
        Instant noted = REVERSED.plus(Duration.ofDays(14)).plus(Duration.ofMinutes(2));
        // On the schedule nothing more is due after fourteen days; a suspicion is.
        assertThat(WithdrawalReversalService.auditDue(REVERSED, noted, "NONE_OF_OURS", noted.plus(Duration.ofHours(3)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, noted, "SUSPECT", noted.plus(Duration.ofMinutes(4)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, noted, "SUSPECT", noted.plus(Duration.ofMinutes(5)))).isTrue();
        // Between two points of the schedule, where nothing else would be due for hours.
        Instant early = REVERSED.plus(Duration.ofMinutes(11));
        assertThat(WithdrawalReversalService.auditDue(REVERSED, early, "NONE_OF_OURS", early.plus(Duration.ofMinutes(6)))).isFalse();
        assertThat(WithdrawalReversalService.auditDue(REVERSED, early, "SUSPECT", early.plus(Duration.ofMinutes(6)))).isTrue();
    }
}
