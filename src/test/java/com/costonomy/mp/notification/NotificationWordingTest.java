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
        @DisplayName("a wallet order says it was paid, and names the restaurant")
        void walletOrder() {
            var text = only("SupplierOrderConfirmed", "WALLET", Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("Spice Route").contains("MP-261007-000123")
                    .containsIgnoringCase("paid").doesNotContain("credit");
        }

        @Test
        @DisplayName("a card order is secured, collected when the supplier marks it ready, never 'paid'")
        void cardOrder() {
            var text = only("SupplierOrderConfirmed", "PREPAID", Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("Spice Route").contains("MP-261007-000123")
                    .contains("secured").contains("collected when you mark it ready")
                    .doesNotContainIgnoringCase("paid").doesNotContain("credit");
        }

        @Test
        @DisplayName("no variant (an old outbox row, a payment method added later) is neutral: neither paid nor credit")
        void defaultIsNeutral() {
            var text = only("SupplierOrderConfirmed", null, Audience.SUPPLIER_STORE).render(fields);

            assertThat(text).contains("MP-261007-000123").contains("Spice Route").contains("confirmed")
                    .doesNotContainIgnoringCase("paid").doesNotContainIgnoringCase("credit")
                    .doesNotContainIgnoringCase("up front");
            // An unknown variant lands on the same neutral rule.
            assertThat(only("SupplierOrderConfirmed", "SOMETHING_NEW", Audience.SUPPLIER_STORE).render(fields))
                    .isEqualTo(text);
        }

        @Test
        @DisplayName("the producer picks the wording from the payment method and the due date")
        void variantChoice() {
            assertThat(OrderReleaseService.confirmationVariant("CREDIT", LocalDate.of(2026, 11, 7)))
                    .isEqualTo("CREDIT_DUE");
            assertThat(OrderReleaseService.confirmationVariant("CREDIT", null)).isEqualTo("CREDIT");
            assertThat(OrderReleaseService.confirmationVariant("PREPAID", null)).isEqualTo("PREPAID");
            assertThat(OrderReleaseService.confirmationVariant("WALLET", LocalDate.of(2026, 11, 7)))
                    .isEqualTo("WALLET");
            assertThat(OrderReleaseService.confirmationVariant("SOMETHING_NEW", null)).isNull();
            assertThat(OrderReleaseService.confirmationVariant(null, null)).isNull();
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

    @Nested
    @DisplayName("text stays readable when a field is missing (pre-deploy outbox rows, a failed lookup)")
    class MissingFields {

        private final java.util.regex.Pattern BROKEN = java.util.regex.Pattern.compile(
                "\\(\\s*\\)|\\[|]|[{}]|^\\s*[.,:]|\\s[.,:]|\\s{2,}|\\s$");

        private void readsCleanly(String text) {
            assertThat(text).isNotBlank();
            assertThat(BROKEN.matcher(text).find()).describedAs("'%s'", text).isFalse();
            assertThat(Character.isUpperCase(text.charAt(0))).describedAs("'%s' starts a sentence", text).isTrue();
        }

        @Test
        @DisplayName("every order-confirmed variant reads correctly with no restaurant, and with no fields at all")
        void confirmed() {
            for (String variant : new String[]{null, "WALLET", "PREPAID", "CREDIT", "CREDIT_DUE"}) {
                var rule = only("SupplierOrderConfirmed", variant, Audience.SUPPLIER_STORE);
                readsCleanly(rule.render(Map.of("orderNumber", "MP-1", "dueDate", "7 Nov 2026")));
                readsCleanly(rule.render(Map.of()));
            }
            assertThat(only("SupplierOrderConfirmed", "CREDIT_DUE", Audience.SUPPLIER_STORE)
                    .render(Map.of("orderNumber", "MP-1", "dueDate", "7 Nov 2026"))).contains("7 Nov 2026");
        }

        @Test
        @DisplayName("a new request without a restaurant says 'A restaurant'; without an outlet there is no empty bracket")
        void newRequest() {
            var rule = only("IntentSent", null, Audience.SUPPLIER_STORE);

            assertThat(rule.render(Map.of("reference", "REQ-77")))
                    .isEqualTo("A restaurant is asking what you can supply. Request REQ-77.");
            assertThat(rule.render(Map.of("reference", "REQ-77", "restaurantName", "Spice Route")))
                    .isEqualTo("Spice Route is asking what you can supply. Request REQ-77.");
            assertThat(rule.render(Map.of("reference", "REQ-77", "restaurantName", "Spice Route",
                    "outletName", "Indiranagar")))
                    .isEqualTo("Spice Route (Indiranagar) is asking what you can supply. Request REQ-77.");
            readsCleanly(rule.render(Map.of()));
        }

        @Test
        @DisplayName("an old rating row (no order number, no restaurant) still reads as a sentence")
        void oldRating() {
            var rule = only("RatingSubmitted", null, Audience.SUPPLIER_STORE);

            assertThat(rule.render(Map.of("overall", "4"))).isEqualTo("A restaurant rated your order: 4 out of 5.");
            readsCleanly(rule.render(Map.of("overall", "4")));
            readsCleanly(rule.render(Map.of("overall", "4", "orderNumber", "MP-1")));
        }

        @Test
        @DisplayName("the supplier's own delivery reads correctly without a supplier name")
        void ownDeliveryWithoutName() {
            var rule = only("SupplierOrderReady", "SUPPLIER_DELIVERY", Audience.OUTLET);

            readsCleanly(rule.render(Map.of("orderNumber", "MP-1")));
            assertThat(rule.render(Map.of("orderNumber", "MP-1"))).contains("delivered by the supplier");
        }
    }

    @Test
    @DisplayName("a variant keeps every audience its event's own rule has: a variant replaces the list, so nobody may be dropped")
    void variantsCoverEveryAudience() {
        var byEvent = NotificationRules.all().stream()
                .collect(java.util.stream.Collectors.groupingBy(NotificationRule::eventType));
        var variants = byEvent.keySet().stream().filter(k -> k.contains("#")).toList();

        assertThat(variants).isNotEmpty();
        for (String key : variants) {
            String base = key.substring(0, key.indexOf('#'));
            var baseAudiences = byEvent.get(base).stream().map(NotificationRule::audience).collect(
                    java.util.stream.Collectors.toSet());
            var variantAudiences = byEvent.get(key).stream().map(NotificationRule::audience).collect(
                    java.util.stream.Collectors.toSet());
            assertThat(variantAudiences).describedAs("%s must cover the audiences of %s", key, base)
                    .containsAll(baseAudiences);
        }
    }
}
