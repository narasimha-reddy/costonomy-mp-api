package com.costonomy.mp.notification;

import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.notification.domain.NotificationChannel;
import com.costonomy.mp.notification.domain.NotificationRule;
import com.costonomy.mp.notification.domain.NotificationRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The notification rules of the credit reminder and the supplier digest (D-142, D-148). */
class CreditReminderRulesTest {

    @Test
    @DisplayName("a reminder goes to the restaurant: T-3 in-app only, due-day and weekly in-app and push, SMS only on the SMS variant; only the SMS one is critical")
    void reminderChannelsByVariant() {
        assertThat(NotificationRules.forEvent(CreditEvents.REMINDER, "IN_APP").get(0).channels())
                .containsExactly(NotificationChannel.IN_APP);
        assertThat(NotificationRules.forEvent(CreditEvents.REMINDER, "PUSH").get(0).channels())
                .containsExactly(NotificationChannel.IN_APP, NotificationChannel.PUSH);
        assertThat(NotificationRules.forEvent(CreditEvents.REMINDER, "SMS").get(0).channels())
                .containsExactly(NotificationChannel.IN_APP, NotificationChannel.PUSH, NotificationChannel.SMS);
        // No variant, or one nobody wrote a rule for, never uses SMS.
        assertThat(NotificationRules.forEvent(CreditEvents.REMINDER).get(0).channels())
                .doesNotContain(NotificationChannel.SMS);
        assertThat(NotificationRules.forEvent(CreditEvents.REMINDER, "SOMETHING").get(0).channels())
                .doesNotContain(NotificationChannel.SMS);
        for (String variant : new String[]{"IN_APP", "PUSH", "SMS"}) {
            var rule = NotificationRules.forEvent(CreditEvents.REMINDER, variant).get(0);
            assertThat(rule.audience()).isEqualTo(NotificationRule.Audience.OUTLET);
            // Only the SMS one is critical (an SMS rule must be); the rest a restaurant may mute.
            assertThat(rule.critical()).describedAs(variant).isEqualTo("SMS".equals(variant));
            assertThat(rule.body()).isEqualTo("{message}");
        }
    }

}
