package com.costonomy.mp.notification;

import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.notification.domain.NotificationCategory;
import com.costonomy.mp.notification.domain.NotificationChannel;
import com.costonomy.mp.notification.domain.NotificationRule;
import com.costonomy.mp.notification.domain.NotificationRules;
import com.costonomy.mp.trust.domain.DisputeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification catalogue, and the templates it renders. Doc 08 §1, §4, §5, §8.
 *
 * <p>The catalogue is data, so these are the tests that keep it honest: every rule
 * is renderable, every critical rule is one doc 08 §4 actually lists, and no
 * template can put a secret in front of a user.
 */
class NotificationRulesTest {

    @Nested
    @DisplayName("rendering")
    class Rendering {

        private NotificationRule rule(String body) {
            return new NotificationRule("Test", NotificationRule.Audience.OUTLET,
                    NotificationCategory.ORDERS, false, List.of(NotificationChannel.IN_APP),
                    "Title", body, "SUPPLIER_ORDER");
        }

        @Test
        @DisplayName("a template fills in the fields it names")
        void fieldsAreInterpolated() {
            assertThat(rule("{supplierName} accepted order {orderNumber}.")
                    .render(Map.of("supplierName", "ABC Foods", "orderNumber", "MP-1")))
                    .isEqualTo("ABC Foods accepted order MP-1.");
        }

        @Test
        @DisplayName("a field the template doesn't name never appears")
        void unnamedFieldsCannotLeak() {
            // The point of templating over named fields. Doc 08 §8 forbids an OTP,
            // a card number or a secret from being logged — and a notification goes
            // further than a log: it lands on a lock screen and is mirrored to a
            // watch. Interpolating a whole payload would make this a matter of
            // hoping no producer ever adds the wrong field.
            String rendered = rule("Order {orderNumber} accepted.").render(Map.of(
                    "orderNumber", "MP-1",
                    "otp", "483920",
                    "cardNumber", "4111111111111111",
                    "providerSecret", "sk_live_abc"));

            assertThat(rendered).isEqualTo("Order MP-1 accepted.")
                    .doesNotContain("483920")
                    .doesNotContain("4111")
                    .doesNotContain("sk_live");
        }

        @Test
        @DisplayName("a missing field leaves no braces in front of a user")
        void missingFieldsAreDropped() {
            // Payloads vary; a template that asks for a reason nobody supplied
            // should read as a slightly terse sentence, not as "{reason}".
            assertThat(rule("Your procurement was rejected. {reason}")
                    .render(Map.of()))
                    .isEqualTo("Your procurement was rejected.");
        }

        @Test
        @DisplayName("every rule in the catalogue renders to something readable")
        void everyRuleRenders() {
            for (NotificationRule rule : NotificationRules.all()) {
                String rendered = rule.render(Map.of());
                assertThat(rendered)
                        .describedAs("%s renders", rule.eventType())
                        .isNotBlank()
                        .doesNotContain("{")
                        .doesNotContain("}");
                assertThat(rule.title()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("the catalogue")
    class Catalogue {

        @Test
        @DisplayName("everything doc 08 §4 calls critical is critical")
        void criticalEventsAreMarkedCritical() {
            // Doc 08 §4's list, and the reason it matters: doc 08 §5 lets a user
            // mute anything that is not on it.
            for (String eventType : List.of(
                    "SupplierOrderAccepted", "SupplierOrderRejected", "SupplierOrderExpired",
                    "PaymentFailed", "DriverAssigned", "DeliveryDelivered",
                    "CreditApproved", "CreditModified", "CreditOverdue",
                    "ProcurementApprovalRequested", "DisputeCreated")) {

                var rules = NotificationRules.forEvent(eventType);
                assertThat(rules).describedAs("%s has a rule", eventType).isNotEmpty();
                assertThat(rules).describedAs("%s is critical", eventType)
                        .anyMatch(NotificationRule::critical);
            }
        }

        @Test
        @DisplayName("SMS is reserved for the few events that need acting on today")
        void smsIsRare() {
            // Doc 08 §4: "SMS for selected critical events". An SMS for every status
            // change trains people to ignore SMS, which costs us the one that matters.
            var smsEvents = NotificationRules.all().stream()
                    .filter(rule -> rule.channels().contains(NotificationChannel.SMS))
                    .map(NotificationRule::eventType)
                    .distinct()
                    .toList();

            // The two Intent events are the successors of the two order ones,
            // not additions to them: under D-088 a supplier answers a request
            // rather than an order, so "they said no" and "they never answered"
            // now happen one step earlier. Both still cost the kitchen its day,
            // which is the test this list has always applied.
            assertThat(smsEvents).containsExactlyInAnyOrder(
                    "SupplierOrderRejected", "SupplierOrderExpired",
                    "IntentDeclined", "IntentExpired",
                    "PaymentFailed", "CreditOverdue");
        }

        @Test
        @DisplayName("nothing goes out by SMS that isn't critical")
        void smsImpliesCritical() {
            // An SMS costs money and interrupts someone. If it is worth that, it is
            // worth being un-mutable — and if it is mutable, it is not worth an SMS.
            assertThat(NotificationRules.all())
                    .filteredOn(rule -> rule.channels().contains(NotificationChannel.SMS))
                    .allMatch(NotificationRule::critical);
        }

        @Test
        @DisplayName("every rule writes to the inbox")
        void everyRuleIsAlsoInApp() {
            // The inbox is the durable channel; push and SMS are best-effort on top.
            // A rule that only pushed would vanish for anyone who missed the buzz.
            assertThat(NotificationRules.all())
                    .allMatch(rule -> rule.channels().contains(NotificationChannel.IN_APP));
        }

        @Test
        @DisplayName("a high-frequency event never becomes a notification")
        void noisyEventsAreNotNotified() {
            // A location update arrives every few seconds. Pushing it would be the
            // fastest way to get the app's notifications turned off entirely.
            for (String noisy : List.of(
                    "DeliveryLocationUpdated", "DeliveryEtaChanged", "CreditReserved")) {
                assertThat(NotificationRules.forEvent(noisy))
                        .describedAs("%s is not notified", noisy).isEmpty();
            }
        }

        @Test
        @DisplayName("rules match the event names the system actually publishes")
        void ruleEventNamesMatchReality() {
            // The bug this catches is silent in both directions: a rule for an event
            // nobody publishes is dead, and an event with a subtly different name
            // reaches nobody. Both halves were real — delivery published
            // "DeliveryDRIVER_ASSIGNED" and disputes "DisputeRESOLVED" before the
            // names were centralised on the enums.
            assertThat(DeliveryStatus.DRIVER_ASSIGNED.eventName()).isEqualTo("DriverAssigned");
            assertThat(DeliveryStatus.DELIVERED.eventName()).isEqualTo("DeliveryDelivered");
            assertThat(DisputeStatus.RESOLVED.eventName()).isEqualTo("DisputeResolved");

            assertThat(NotificationRules.forEvent(
                    DeliveryStatus.DRIVER_ASSIGNED.eventName())).isNotEmpty();
            assertThat(NotificationRules.forEvent(
                    DeliveryStatus.DELIVERED.eventName())).isNotEmpty();
            assertThat(NotificationRules.forEvent(
                    DisputeStatus.RESOLVED.eventName())).isNotEmpty();
        }

        @Test
        @DisplayName("both sides of an order are told, each in their own words")
        void bothSidesAreNotified() {
            var expired = NotificationRules.forEvent("SupplierOrderExpired");

            assertThat(expired).hasSize(2);
            assertThat(expired).extracting(NotificationRule::audience)
                    .containsExactlyInAnyOrder(NotificationRule.Audience.OUTLET,
                            NotificationRule.Audience.SUPPLIER_STORE);
            // Different wording, because it means different things to each: one
            // needs to re-source, the other has lost the order.
            assertThat(expired.get(0).body()).isNotEqualTo(expired.get(1).body());
        }
    }

    @Nested
    @DisplayName("channels")
    class Channels {

        @Test
        @DisplayName("only the inbox stays inside the building")
        void outboundChannels() {
            assertThat(NotificationChannel.IN_APP.isOutbound()).isFalse();
            assertThat(NotificationChannel.PUSH.isOutbound()).isTrue();
            assertThat(NotificationChannel.SMS.isOutbound()).isTrue();
        }
    }
}
