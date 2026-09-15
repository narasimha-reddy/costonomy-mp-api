package com.costonomy.mp.settlement;

import com.costonomy.mp.settlement.domain.SettlementAdjustment;
import com.costonomy.mp.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settlement state machine and the commission arithmetic. Doc 01 §16–17,
 * doc 03 §13.
 */
class SettlementLifecycleTest {

    @Nested
    @DisplayName("the settlement lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("the documented path is walkable")
        void documentedPath() {
            // Doc 03 §13.
            var path = List.of(SettlementStatus.PENDING, SettlementStatus.CALCULATED,
                    SettlementStatus.APPROVED, SettlementStatus.PROCESSING,
                    SettlementStatus.PAID);

            for (int i = 0; i < path.size() - 1; i++) {
                assertThat(path.get(i).canTransitionTo(path.get(i + 1)))
                        .describedAs("%s → %s", path.get(i), path.get(i + 1))
                        .isTrue();
            }
        }

        @Test
        @DisplayName("approval cannot be skipped")
        void approvalIsMandatory() {
            // A settlement is money leaving the platform. The gap between
            // CALCULATED and APPROVED is where somebody reads the number before it
            // becomes a payment; skipping it lets a calculation bug pay itself out.
            assertThat(SettlementStatus.CALCULATED
                    .canTransitionTo(SettlementStatus.PROCESSING)).isFalse();
            assertThat(SettlementStatus.CALCULATED
                    .canTransitionTo(SettlementStatus.PAID)).isFalse();
            assertThat(SettlementStatus.PENDING
                    .canTransitionTo(SettlementStatus.APPROVED)).isFalse();
        }

        @Test
        @DisplayName("a failed payout retries from approved, not from the start")
        void failureKeepsItsApproval() {
            // It has already been calculated and already been approved. Sending it
            // back to PENDING would recalculate against whatever has changed since,
            // and ask for an approval that was already given.
            assertThat(SettlementStatus.PROCESSING
                    .canTransitionTo(SettlementStatus.FAILED)).isTrue();
            assertThat(SettlementStatus.FAILED
                    .canTransitionTo(SettlementStatus.PROCESSING)).isTrue();
            assertThat(SettlementStatus.FAILED
                    .canTransitionTo(SettlementStatus.PENDING)).isFalse();
        }

        @Test
        @DisplayName("paid is final")
        void paidIsTerminal() {
            assertThat(SettlementStatus.PAID.isTerminal()).isTrue();
            for (SettlementStatus target : SettlementStatus.values()) {
                assertThat(SettlementStatus.PAID.canTransitionTo(target))
                        .describedAs("PAID → %s", target).isFalse();
            }
        }

        @Test
        @DisplayName("figures freeze at approval")
        void figuresFreeze() {
            // An approved payout is a commitment. A correction after that belongs
            // to the next settlement, carrying its own reason — silently changing
            // a number somebody signed off is how two parties end up holding
            // different statements.
            assertThat(SettlementStatus.PENDING.isMutable()).isTrue();
            assertThat(SettlementStatus.CALCULATED.isMutable()).isTrue();
            assertThat(SettlementStatus.APPROVED.isMutable()).isFalse();
            assertThat(SettlementStatus.PROCESSING.isMutable()).isFalse();
            assertThat(SettlementStatus.PAID.isMutable()).isFalse();
        }
    }

    @Nested
    @DisplayName("commission arithmetic")
    class Commission {

        /** Doc 01 §16's own worked example. */
        @Test
        @DisplayName("1% of a hundred thousand is a thousand, leaving ninety-nine")
        void docWorkedExample() {
            BigDecimal gross = new BigDecimal("100000.00");
            BigDecimal commission = gross.multiply(new BigDecimal("1.00"))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            assertThat(commission).isEqualByComparingTo("1000.00");
            assertThat(gross.subtract(commission)).isEqualByComparingTo("99000.00");
        }

        @Test
        @DisplayName("commission rounds to the paisa, half up")
        void roundingIsExplicit() {
            // 1% of 1234.55 is 12.3455. A settlement that rounds differently from
            // the supplier's own spreadsheet is a support call every month.
            BigDecimal commission = new BigDecimal("1234.55")
                    .multiply(new BigDecimal("1.00"))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            assertThat(commission).isEqualByComparingTo("12.35");
        }
    }

    @Nested
    @DisplayName("adjustments")
    class Adjustments {

        private SettlementAdjustment adjustment(String direction, String amount) {
            var adjustment = new SettlementAdjustment();
            adjustment.setDirection(direction);
            adjustment.setAmount(new BigDecimal(amount));
            return adjustment;
        }

        @Test
        @DisplayName("a credit adds and a debit subtracts")
        void directionDecidesTheSign() {
            // Direction is explicit rather than implied by the sign of the amount,
            // so nobody has to infer it — and the CHECK can require a positive
            // amount, which catches a debit entered twice negative.
            assertThat(adjustment("CREDIT", "500.00").signedAmount())
                    .isEqualByComparingTo("500.00");
            assertThat(adjustment("DEBIT", "500.00").signedAmount())
                    .isEqualByComparingTo("-500.00");
        }

        @Test
        @DisplayName("the net equation holds")
        void netEquation() {
            // Gross - Commission ± Adjustments = Net. Doc 01 §17.
            BigDecimal gross = new BigDecimal("10000.00");
            BigDecimal commission = new BigDecimal("100.00");
            BigDecimal adjustments = adjustment("CREDIT", "250.00").signedAmount()
                    .add(adjustment("DEBIT", "75.00").signedAmount());

            assertThat(gross.subtract(commission).add(adjustments))
                    .isEqualByComparingTo("10075.00");
        }
    }
}
