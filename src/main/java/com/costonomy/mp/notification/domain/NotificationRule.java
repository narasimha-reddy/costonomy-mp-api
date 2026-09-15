package com.costonomy.mp.notification.domain;

import java.util.List;
import java.util.Map;

/**
 * What one domain event means to a person.
 *
 * <p>The whole mapping lives in {@link NotificationRules} as data. The alternative
 * — a {@code notify(...)} call in each service — spreads the decision across five
 * modules and makes "why did nobody get told" a search rather than a lookup. It
 * also makes the wording of a rejection a thing that lives next to the code that
 * rejects, which is how two events end up phrased differently for no reason.
 *
 * @param audience  whose inbox this lands in. Derived from the event's payload —
 *                  a supplier order concerns the restaurant that placed it and the
 *                  store filling it, and they are usually told different things.
 * @param critical  doc 08 §5. Critical notifications ignore preferences, so this
 *                  is not a wording decision: it decides whether a user can turn
 *                  something off.
 * @param channels  what to attempt beyond the inbox. SMS only where doc 08 §4's
 *                  "selected critical events" genuinely applies — an SMS for every
 *                  status change trains people to ignore them.
 * @param title     a fixed heading
 * @param body      a template over named payload fields, never the payload itself
 */
public record NotificationRule(
        String eventType,
        Audience audience,
        NotificationCategory category,
        boolean critical,
        List<NotificationChannel> channels,
        String title,
        String body,
        String targetType) {

    /** Which side of a transaction is being told. */
    public enum Audience {
        /** Everyone with a grant on the outlet in the payload. */
        OUTLET,
        /** Everyone with a grant on the supplier store in the payload. */
        SUPPLIER_STORE
    }

    /**
     * Render the body.
     *
     * <p><b>Named fields only.</b> Anything the template does not ask for by name
     * cannot appear in a notification, which is what keeps doc 08 §8's forbidden
     * values — an OTP, a card number, a provider secret — out of a message that
     * gets pushed to a lock screen and mirrored to a watch. Interpolating the
     * whole payload would make that a matter of hoping no producer ever adds the
     * wrong field.
     */
    public String render(Map<String, String> fields) {
        String rendered = body;
        for (var field : fields.entrySet()) {
            rendered = rendered.replace("{" + field.getKey() + "}", field.getValue());
        }
        // Any placeholder the payload did not supply is dropped rather than left
        // as literal braces in front of a user.
        return rendered.replaceAll("\\{[a-zA-Z0-9_]+}", "").replaceAll("\\s{2,}", " ").trim();
    }
}
