package com.costonomy.mp.notification;

import com.costonomy.mp.notification.domain.NotificationRule;
import com.costonomy.mp.notification.domain.NotificationRule.Audience;
import com.costonomy.mp.notification.domain.NotificationRules;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the order notifications say, by payment method and delivery mode (flow review 6, 15, 28, 29).
 */
class NotificationWordingTest {

    private static NotificationRule only(String event, String variant, Audience audience) {
        var rules = NotificationRules.forEvent(event, variant).stream()
                .filter(r -> r.audience() == audience).toList();
        assertThat(rules).describedAs("%s#%s for %s", event, variant, audience).hasSize(1);
        return rules.get(0);
    }

    @Nested
    @DisplayName("the supplier's order confirmation")
    class Confirmed {

        private final Map<String, String> fields = Map.of(
                "orderNumber", "MP-261007-000123", "restaurantName", "Spice Route",
                "dueDate", "7 Nov 2026");

        @Test
        @DisplayName("a credit order says on credit with the due date, never paid for")
        void creditOrder() {
            var text = only("SupplierOrderConfirmed", "CREDIT_DUE", Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("MP-261007-000123").contains("Spice Route")
                    .contains("on credit").contains("7 Nov 2026")
                    .doesNotContainIgnoringCase("paid");
        }

        @Test
        @DisplayName("a credit order whose due date is not known yet still says on credit")
        void creditOrderWithoutDate() {
            var text = only("SupplierOrderConfirmed", "CREDIT", Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("on credit").doesNotContainIgnoringCase("paid").doesNotContain("7 Nov");
        }

        @Test
        @DisplayName("a wallet or online order says it was paid up front, and names the restaurant")
        void prepaidOrder() {
            var text = only("SupplierOrderConfirmed", null, Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("Spice Route").contains("MP-261007-000123")
                    .containsIgnoringCase("paid").doesNotContain("credit");
        }

        @Test
        @DisplayName("the producer picks the wording from the payment method and the due date")
        void variantChoice() {
            assertThat(OrderReleaseService.confirmationVariant("CREDIT", LocalDate.of(2026, 11, 7)))
                    .isEqualTo("CREDIT_DUE");
            assertThat(OrderReleaseService.confirmationVariant("CREDIT", null)).isEqualTo("CREDIT");
            assertThat(OrderReleaseService.confirmationVariant("PREPAID", null)).isNull();
            assertThat(OrderReleaseService.confirmationVariant("WALLET", LocalDate.of(2026, 11, 7))).isNull();
        }

        @Test
        @DisplayName("exactly one Order confirmed reaches the supplier: the request's own event no longer adds a second")
        void oneConfirmationPerOrder() {
            var confirmedFor = NotificationRules.all().stream()
                    .filter(r -> r.audience() == Audience.SUPPLIER_STORE)
                    .filter(r -> r.title().equals("Order confirmed"))
                    .map(r -> r.eventType().split("#")[0]).distinct().toList();

            assertThat(confirmedFor).containsExactly("SupplierOrderConfirmed");
            assertThat(NotificationRules.forEvent("IntentOrdered")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the buyer's packed notification")
    class Packed {

        private final Map<String, String> fields = Map.of(
                "orderNumber", "MP-261007-000123", "supplierName", "Sri Balaji");

        @Test
        @DisplayName("pickup: ready for pickup, waiting for collection")
        void pickup() {
            var rule = only("SupplierOrderReady", "PICKUP", Audience.OUTLET);

            assertThat(rule.title()).isEqualTo("Ready for pickup");
            assertThat(rule.render(fields)).contains("waiting for collection");
        }

        @Test
        @DisplayName("partner delivery: packed, a delivery partner is being arranged, nothing about collection")
        void partnerDelivery() {
            var rule = only("SupplierOrderReady", "COSTONOMY_DELIVERY", Audience.OUTLET);

            assertThat(rule.title()).doesNotContain("pickup");
            assertThat(rule.render(fields)).contains("MP-261007-000123")
                    .contains("delivery partner is being arranged")
                    .doesNotContainIgnoringCase("collection").doesNotContainIgnoringCase("pickup");
        }

        @Test
        @DisplayName("the supplier's own delivery: packed and delivered by the supplier")
        void ownDelivery() {
            var rule = only("SupplierOrderReady", "SUPPLIER_DELIVERY", Audience.OUTLET);

            assertThat(rule.title()).doesNotContain("pickup");
            assertThat(rule.render(fields)).contains("will be delivered by Sri Balaji")
                    .doesNotContainIgnoringCase("collection");
        }

        @Test
        @DisplayName("an unknown mode keeps the plain wording rather than losing the notification")
        void unknownModeKeepsDefault() {
            assertThat(NotificationRules.forEvent("SupplierOrderReady", "SOMETHING_NEW")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the supplier's request and rating notifications")
    class RequestAndRating {

        @Test
        @DisplayName("a new request names the restaurant and the outlet")
        void newRequestNamesRestaurant() {
            var text = only("IntentSent", null, Audience.SUPPLIER_STORE).render(Map.of(
                    "reference", "REQ-77", "restaurantName", "Spice Route", "outletName", "Indiranagar"));

            assertThat(text).contains("Spice Route").contains("Indiranagar").contains("REQ-77")
                    .doesNotContain("A restaurant is asking");
        }

        @Test
        @DisplayName("a rating names the order by its number and opens that order, not the rating row")
        void ratingUsesOrderNumber() {
            var rule = only("RatingSubmitted", null, Audience.SUPPLIER_STORE);
            var text = rule.render(Map.of("orderNumber", "MP-261007-000123", "supplierOrderId", "109",
                    "overall", "4", "restaurantName", "Spice Route"));

            assertThat(text).contains("MP-261007-000123").contains("Spice Route").contains("4 out of 5")
                    .doesNotContain("109");
            assertThat(rule.targetType()).isEqualTo("SUPPLIER_ORDER");
            assertThat(rule.targetIdField()).isEqualTo("supplierOrderId");
        }

        @Test
        @DisplayName("a failed payment opens its order, not the payment row")
        void paymentFailedOpensTheOrder() {
            var rule = only("PaymentFailed", null, Audience.OUTLET);

            assertThat(rule.targetType()).isEqualTo("SUPPLIER_ORDER");
            assertThat(rule.targetIdField()).isEqualTo("supplierOrderId");
        }
    }

    @Test
    @DisplayName("no restaurant-facing text says Driver: it is the delivery partner")
    void deliveryPartnerNotDriver() {
        List<NotificationRule> outlet = NotificationRules.all().stream()
                .filter(r -> r.audience() == Audience.OUTLET).toList();

        assertThat(outlet).isNotEmpty();
        for (var rule : outlet) {
            assertThat(rule.title() + " " + rule.body())
                    .describedAs(rule.eventType())
                    .doesNotContainIgnoringCase("driver");
        }
        assertThat(only("DriverAssigned", null, Audience.OUTLET).title()).isEqualTo("Delivery partner on the way");
    }
}
