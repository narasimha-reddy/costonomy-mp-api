package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditExposure;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit arithmetic and state machines. Doc 01 §18–19, doc 03 §8–9, doc 10 §3.
 *
 * <p>These are the invariants doc 10 §3 names by hand — {@code available >= 0} and
 * {@code approved = reserved + utilized + available} — which are the two things
 * that decide whether a restaurant can spend money it does not have.
 */
class CreditExposureTest {

    private static CreditExposure exposure(String limit, String reserved, String utilized) {
        return new CreditExposure(new BigDecimal(limit), new BigDecimal(reserved),
                new BigDecimal(utilized), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("the formula")
    class Formula {

        @Test
        @DisplayName("available is the limit less what is held and what is drawn")
        void availableIsWhatIsLeft() {
            // Doc 01 §18's formula, verbatim.
            assertThat(exposure("200000", "30000", "50000").available())
                    .isEqualByComparingTo("120000");
        }

        @Test
        @DisplayName("the four numbers always add back up to the limit")
        void theIdentityHolds() {
            // Doc 10 §3: approved = reserved + utilized + available. If this can
            // ever fail, some screen is showing a restaurant credit that isn't there.
            var exposure = exposure("200000", "30000", "50000");
            assertThat(exposure.reserved()
                    .add(exposure.utilized())
                    .add(exposure.available()))
                    .isEqualByComparingTo(exposure.approvedLimit());
        }

        @Test
        @DisplayName("available never goes negative")
        void availableIsNeverNegative() {
            // Doc 01 §18. Reachable only if a limit was cut below live exposure,
            // which is allowed — a supplier tightening after a bad month. What must
            // not happen is the shortfall presenting as spendable.
            assertThat(exposure("50000", "30000", "40000").available())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a limit cut below exposure leaves nothing to spend")
        void cutLimitLeavesNothingAvailable() {
            assertThat(exposure("50000", "30000", "40000").canCover(new BigDecimal("1")))
                    .isFalse();
        }

        @Test
        @DisplayName("an order for exactly the available amount fits")
        void exactAmountFits() {
            // The boundary, because >= and > differ here by one order.
            assertThat(exposure("100000", "0", "40000").canCover(new BigDecimal("60000")))
                    .isTrue();
            assertThat(exposure("100000", "0", "40000").canCover(new BigDecimal("60000.01")))
                    .isFalse();
        }

        @Test
        @DisplayName("scale doesn't change the answer")
        void scaleIsIrrelevant() {
            // 60000 and 60000.0000 are the same money. Comparing with equals here
            // would reject an order for the exact available balance.
            assertThat(exposure("100000.0000", "0.0000", "40000.0000")
                    .canCover(new BigDecimal("60000")))
                    .isTrue();
        }

        @Test
        @DisplayName("agreements add up across suppliers")
        void portfolioSums() {
            // The Credit Overview total (§23A.24) is a sum of separate supplier
            // lines, not one pooled limit — a restaurant with ₹1L at two suppliers
            // cannot spend ₹2L at either.
            var total = exposure("100000", "10000", "20000")
                    .plus(exposure("50000", "0", "5000"));

            assertThat(total.approvedLimit()).isEqualByComparingTo("150000");
            assertThat(total.available()).isEqualByComparingTo("115000");
        }

        @Test
        @DisplayName("overdue is part of due, not added to it")
        void overdueIsASubsetOfDue() {
            // Doc 04 §13 lists both. A restaurant reading them as separate buckets
            // would believe they owe the sum of the two.
            var exposure = new CreditExposure(
                    new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("40000"),
                    new BigDecimal("40000"), new BigDecimal("15000"));

            assertThat(exposure.overdue()).isLessThan(exposure.due());
            assertThat(exposure.utilized()).isEqualByComparingTo(exposure.due());
        }
    }

    @Nested
    @DisplayName("agreement lifecycle")
    class AgreementLifecycle {

        @Test
        @DisplayName("only an ACTIVE agreement can fund an order")
        void onlyActiveFunds() {
            // The single predicate behind guardrail 16 for credit orders. APPROVED
            // in particular must not fund: the supplier changed the terms and the
            // restaurant has not said yes.
            for (CreditAgreementStatus status : CreditAgreementStatus.values()) {
                assertThat(status.canFund())
                        .describedAs("%s funds orders", status)
                        .isEqualTo(status == CreditAgreementStatus.ACTIVE);
            }
        }

        @Test
        @DisplayName("the documented path is allowed")
        void documentedPath() {
            // Doc 03 §8.
            assertThat(CreditAgreementStatus.REQUESTED
                    .canTransitionTo(CreditAgreementStatus.APPROVED)).isTrue();
            assertThat(CreditAgreementStatus.APPROVED
                    .canTransitionTo(CreditAgreementStatus.ACTIVE)).isTrue();
            assertThat(CreditAgreementStatus.ACTIVE
                    .canTransitionTo(CreditAgreementStatus.SUSPENDED)).isTrue();
            assertThat(CreditAgreementStatus.SUSPENDED
                    .canTransitionTo(CreditAgreementStatus.ACTIVE)).isTrue();
        }

        @Test
        @DisplayName("nothing comes back from a terminal state")
        void terminalIsTerminal() {
            for (CreditAgreementStatus terminal : java.util.List.of(
                    CreditAgreementStatus.REJECTED, CreditAgreementStatus.EXPIRED,
                    CreditAgreementStatus.CLOSED)) {

                assertThat(terminal.isTerminal()).isTrue();
                for (CreditAgreementStatus target : CreditAgreementStatus.values()) {
                    assertThat(terminal.canTransitionTo(target))
                            .describedAs("%s → %s", terminal, target)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("a suspended agreement can be closed but not re-approved")
        void suspendedCannotBeReapproved() {
            assertThat(CreditAgreementStatus.SUSPENDED
                    .canTransitionTo(CreditAgreementStatus.CLOSED)).isTrue();
            // Re-approving would restart a negotiation that already concluded; the
            // way back is reinstatement, which keeps the agreed terms.
            assertThat(CreditAgreementStatus.SUSPENDED
                    .canTransitionTo(CreditAgreementStatus.APPROVED)).isFalse();
        }
    }

    @Nested
    @DisplayName("reservation lifecycle")
    class ReservationLifecycle {

        @Test
        @DisplayName("only a RESERVED reservation is holding exposure")
        void onlyReservedHolds() {
            // What utilize() and release() test before touching balances. Getting
            // this wrong would double-release a rejected order and inflate the
            // available limit.
            for (CreditReservationStatus status : CreditReservationStatus.values()) {
                assertThat(status.holdsExposure())
                        .describedAs("%s holds exposure", status)
                        .isEqualTo(status == CreditReservationStatus.RESERVED);
            }
        }
    }

    @Nested
    @DisplayName("invoice lifecycle")
    class InvoiceLifecycle {

        @Test
        @DisplayName("only paid and written-off count as settled")
        void settledMeansSettled() {
            // OVERDUE is emphatically not settled — it is the opposite — and a
            // settled invoice is excluded from dues.
            assertThat(CreditInvoiceStatus.PAID.isSettled()).isTrue();
            assertThat(CreditInvoiceStatus.WRITTEN_OFF.isSettled()).isTrue();
            assertThat(CreditInvoiceStatus.OVERDUE.isSettled()).isFalse();
            assertThat(CreditInvoiceStatus.PARTIALLY_PAID.isSettled()).isFalse();
            assertThat(CreditInvoiceStatus.ISSUED.isSettled()).isFalse();
        }
    }
}
