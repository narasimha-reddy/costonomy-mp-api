package com.costonomy.mp.trust;

import com.costonomy.mp.trust.domain.DisputeCategory;
import com.costonomy.mp.trust.domain.DisputeStatus;
import com.costonomy.mp.trust.domain.RatingModerationStatus;
import com.costonomy.mp.trust.domain.ReceivingItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state machines and quantity rules behind receiving, disputes and ratings.
 * Doc 03 §11–12, doc 01 §22–24.
 */
class TrustLifecycleTest {

    private static ReceivingItem line(String accepted, String received,
                                      String damaged, String missing) {
        var item = new ReceivingItem();
        item.setAcceptedQuantity(new BigDecimal(accepted));
        item.setReceivedQuantity(new BigDecimal(received));
        item.setDamagedQuantity(new BigDecimal(damaged));
        item.setMissingQuantity(new BigDecimal(missing));
        return item;
    }

    @Nested
    @DisplayName("receiving quantities")
    class Quantities {

        @Test
        @DisplayName("a line that arrived in full is not a discrepancy")
        void fullDeliveryIsClean() {
            assertThat(line("10", "10", "0", "0").isShort()).isFalse();
        }

        @Test
        @DisplayName("damaged and missing are each a discrepancy on their own")
        void bothKindsCount() {
            // Doc 04 §15's example: 8 received, 1 damaged, 1 missing. Two different
            // problems, and collapsing them would make "you sent me broken eggs"
            // and "you sent me no eggs" the same complaint.
            assertThat(line("10", "9", "1", "0").isShort()).isTrue();
            assertThat(line("10", "9", "0", "1").isShort()).isTrue();
            assertThat(line("10", "8", "1", "1").isShort()).isTrue();
        }

        @Test
        @DisplayName("the three quantities partition what was accepted")
        void quantitiesAddUp() {
            // The invariant ReceivingService enforces. Without it, a tap-through
            // records a perfect delivery nobody counted — the blind "Complete"
            // button §23A.22 forbids.
            var item = line("10", "8", "1", "1");
            assertThat(item.getReceivedQuantity()
                    .add(item.getDamagedQuantity())
                    .add(item.getMissingQuantity()))
                    .isEqualByComparingTo(item.getAcceptedQuantity());
        }

        @Test
        @DisplayName("a wholly missing line is still a line")
        void nothingArrivedIsStillRecorded() {
            // Zero received is a fact about the delivery, not an absence of one —
            // and it is what makes the fill rate reflect reality.
            var item = line("10", "0", "0", "10");
            assertThat(item.isShort()).isTrue();
            assertThat(item.getReceivedQuantity()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("dispute lifecycle")
    class Disputes {

        @Test
        @DisplayName("the documented path is walkable")
        void documentedPath() {
            // Doc 03 §12.
            assertThat(DisputeStatus.OPEN.canTransitionTo(DisputeStatus.UNDER_REVIEW)).isTrue();
            assertThat(DisputeStatus.UNDER_REVIEW
                    .canTransitionTo(DisputeStatus.RESPONDED)).isTrue();
            assertThat(DisputeStatus.RESPONDED.canTransitionTo(DisputeStatus.RESOLVED)).isTrue();
        }

        @Test
        @DisplayName("a dispute can be rejected from open or under review")
        void rejectionIsReachableEarly() {
            assertThat(DisputeStatus.OPEN.canTransitionTo(DisputeStatus.REJECTED)).isTrue();
            assertThat(DisputeStatus.UNDER_REVIEW.canTransitionTo(DisputeStatus.REJECTED)).isTrue();
        }

        @Test
        @DisplayName("a response is not the end of the conversation")
        void respondedCanReopen() {
            // The restaurant may not accept the answer. Making RESPONDED terminal
            // would let a supplier close a dispute by replying to it.
            assertThat(DisputeStatus.RESPONDED
                    .canTransitionTo(DisputeStatus.UNDER_REVIEW)).isTrue();
        }

        @Test
        @DisplayName("resolved and rejected are final")
        void terminalIsTerminal() {
            for (DisputeStatus terminal : List.of(DisputeStatus.RESOLVED, DisputeStatus.REJECTED)) {
                assertThat(terminal.isTerminal()).isTrue();
                for (DisputeStatus target : DisputeStatus.values()) {
                    assertThat(terminal.canTransitionTo(target))
                            .describedAs("%s → %s", terminal, target).isFalse();
                }
            }
        }

        @Test
        @DisplayName("all seven categories are accepted, and nothing else is")
        void categoriesAreClosed() {
            // Doc 04 §16 lists exactly these. A free-text category would make the
            // dispute intelligence doc 01 §23 wants unreadable.
            for (String valid : List.of("WRONG_PRODUCT", "SHORT_QUANTITY", "DAMAGED",
                    "EXPIRED", "QUALITY", "INCORRECT_INVOICE", "OTHER")) {
                assertThat(DisputeCategory.isValid(valid))
                        .describedAs(valid).isTrue();
            }
            assertThat(DisputeCategory.isValid("LATE")).isFalse();
            assertThat(DisputeCategory.isValid("wrong_product")).isFalse();
            assertThat(DisputeCategory.isValid(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("rating moderation")
    class Moderation {

        @Test
        @DisplayName("a rating counts until it is hidden")
        void onlyPublishedCounts() {
            // Doc 01 §24: public subject to moderation. Hiding is what removes it
            // from the average and from ranking — otherwise moderation is cosmetic.
            assertThat(RatingModerationStatus.PUBLISHED.isVisible()).isTrue();
            assertThat(RatingModerationStatus.HIDDEN.isVisible()).isFalse();
        }
    }
}
