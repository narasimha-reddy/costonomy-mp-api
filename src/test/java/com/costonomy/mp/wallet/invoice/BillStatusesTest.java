package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.invoice.domain.BillStatus;
import com.costonomy.mp.wallet.invoice.domain.InvoiceStatus;
import com.costonomy.mp.wallet.invoice.service.BillStatuses;
import com.costonomy.mp.wallet.invoice.service.BillStatuses.Facts;
import com.costonomy.mp.wallet.invoice.service.BillTracking;
import com.costonomy.mp.wallet.service.WalletHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bill status rule (D-116), without a database. */
class BillStatusesTest {

    private static final Instant START = Instant.parse("2026-08-31T18:30:00Z"); // 1 Sep 2026 in India
    private static final Optional<Instant> ON = Optional.of(START);

    private static Facts f(WalletEntryKind kind, Instant at, InvoiceStatus invoice, boolean reviewed, boolean waived,
                           boolean returned) {
        return new Facts(kind, at, invoice, reviewed, waived, returned);
    }

    @Test
    @DisplayName("a bill that exists shows its status, whatever the kind, date, waiver or tracking")
    void billWins() {
        var before = START.minusSeconds(86400 * 30);
        for (var start : java.util.List.of(ON, Optional.<Instant>empty())) {
            assertThat(BillStatuses.resolve(f(WalletEntryKind.QUICKSCAN_PAYMENT, before, InvoiceStatus.READING, false, false, false), start))
                    .isEqualTo(BillStatus.READING);
            assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, before, InvoiceStatus.UNREADABLE, false, false, false), start))
                    .isEqualTo(BillStatus.UNREADABLE);
            // M3: an unreadable bill filled in by hand (a review saved) is REVIEWED.
            assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, before, InvoiceStatus.UNREADABLE, true, false, false), start))
                    .isEqualTo(BillStatus.REVIEWED);
            // A cancelled order's payment that has a bill still shows the bill.
            assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, START, InvoiceStatus.READING, false, false, true), start))
                    .isEqualTo(BillStatus.READING);
            assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, before, InvoiceStatus.READ, false, true, false), start))
                    .isEqualTo(BillStatus.ADDED);
            assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, before, InvoiceStatus.READ, true, false, true), start))
                    .isEqualTo(BillStatus.REVIEWED);
        }
    }

    @Test
    @DisplayName("without a bill: PENDING from the start (inclusive) for eligible payments only; waived is NOT_REQUIRED, no chip in the list")
    void withoutBill() {
        var kinds = EnumSet.allOf(WalletEntryKind.class);
        for (var kind : kinds) {
            boolean eligible = kind == WalletEntryKind.ORDER_PAYMENT || kind == WalletEntryKind.QUICKSCAN_PAYMENT;
            assertThat(BillStatuses.resolve(f(kind, START, null, false, false, false), ON)).describedAs(kind.name())
                    .isEqualTo(eligible ? BillStatus.PENDING : null);
        }
        assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, START.minusNanos(1000), null, false, false, false), ON)).isNull();
        assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, START.plusSeconds(1), null, false, false, false), Optional.empty())).isNull();
        assertThat(BillStatuses.resolve(f(WalletEntryKind.QUICKSCAN_PAYMENT, START, null, false, false, true), ON)).isNull();
        // The money came back in full (an order cancelled: ORDER_REFUND): no bill asked for, nothing to waive.
        assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, START, null, false, false, true), ON)).isNull();
        assertThat(BillStatuses.resolve(f(WalletEntryKind.QUICKSCAN_PAYMENT, START, null, false, true, false), ON))
                .isEqualTo(BillStatus.NOT_REQUIRED);
        assertThat(BillStatuses.forList(f(WalletEntryKind.QUICKSCAN_PAYMENT, START, null, false, true, false), ON)).isNull();
        // Waived before the start, or with tracking off: still 'No bill needed'.
        assertThat(BillStatuses.resolve(f(WalletEntryKind.ORDER_PAYMENT, START.minusSeconds(5), null, false, true, false), Optional.empty()))
                .isEqualTo(BillStatus.NOT_REQUIRED);
    }

    @Test
    @DisplayName("a credit repayment never needs a bill: no status in any state, not billable, not in the bill queries")
    void creditRepaymentNeedsNoBill() {
        var kind = WalletEntryKind.CREDIT_REPAYMENT;
        assertThat(BillStatuses.KINDS).doesNotContain(kind);
        for (var start : java.util.List.of(ON, Optional.<Instant>empty())) {
            assertThat(BillStatuses.resolve(f(kind, START, null, false, false, false), start)).isNull();
            assertThat(BillStatuses.resolve(f(kind, START.plusSeconds(86400 * 90), null, false, false, false), start)).isNull();
            assertThat(BillStatuses.forList(f(kind, START, null, false, false, false), start)).isNull();
        }
    }

    @Test
    @DisplayName("the tracking start: an ISO date at midnight in India; blank is off; anything else refuses to start")
    void trackingStart() {
        assertThat(new BillTracking("2026-09-01").start()).contains(START);
        assertThat(new BillTracking(" ").start()).isEmpty();
        assertThat(new BillTracking("").start()).isEmpty();
        assertThatThrownBy(() -> new BillTracking("01/09/2026")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2026-10-01");
    }

    @Test
    @DisplayName("bills= accepts the five statuses in any case and refuses anything else with a plain sentence")
    void filterParsing() {
        var filter = WalletHistoryService.parseFilter(null, null, null, "pending, Reviewed,PENDING");
        assertThat(filter.bills()).containsExactlyInAnyOrder(BillStatus.PENDING, BillStatus.REVIEWED);
        assertThat(WalletHistoryService.parseFilter(null, null, null, null).bills()).isEmpty();
        for (String bad : java.util.List.of("NOT_REQUIRED", "MISSING", "1")) {
            assertThatThrownBy(() -> WalletHistoryService.parseFilter(null, null, null, bad))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("'" + bad + "' is not a bill status. Use PENDING, READING, ADDED, REVIEWED or UNREADABLE.");
        }
    }

    @Test
    @DisplayName("the SQL filter: one term per status; PENDING never matches while tracking is off")
    void filterSql() {
        assertThat(BillStatuses.matching(EnumSet.of(BillStatus.PENDING), false)).isEqualTo("((t.id < 0))");
        assertThat(BillStatuses.matching(EnumSet.of(BillStatus.PENDING), true)).contains(":billStart");
        assertThat(BillStatuses.usesStart(EnumSet.of(BillStatus.PENDING), false)).isFalse();
        assertThat(BillStatuses.usesStart(EnumSet.of(BillStatus.READING), true)).isFalse();
        assertThat(BillStatuses.matching(EnumSet.of(BillStatus.ADDED, BillStatus.REVIEWED), true))
                .contains("bi.reviewedAt is null").contains("bi.reviewedAt is not null").doesNotContain(":billStart");
        assertThat(BillStatuses.matching(EnumSet.of(BillStatus.UNREADABLE), true)).contains("bi.reviewedAt is null");
        assertThatThrownBy(() -> BillStatuses.matching(EnumSet.of(BillStatus.NOT_REQUIRED), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
