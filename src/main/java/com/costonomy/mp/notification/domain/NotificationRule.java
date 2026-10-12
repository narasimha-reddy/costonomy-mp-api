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
        String targetType,
        /**
         * The payload field holding what the notification should open, when that
         * is not the event's own aggregate.
         *
         * <p>A delivery event's aggregate is the delivery, but every screen either
         * side has for it is keyed by the order — so a notification pointing at
         * the delivery id opens {@code /tracking/2} when it meant order 5, which
         * is a different restaurant's order. Null means the aggregate is right,
         * which it is for most events.
         */
        String targetIdField) {

    /** Most events point at their own aggregate. */
    public NotificationRule(String eventType, Audience audience, NotificationCategory category,
                            boolean critical, List<NotificationChannel> channels,
                            String title, String body, String targetType) {
        this(eventType, audience, category, critical, channels, title, body, targetType, null);
    }

    /** Which side of a transaction is being told. */
    public enum Audience {
        /** Everyone with a grant on the outlet in the payload. */
        OUTLET,
        /** Everyone with a grant on the supplier store in the payload. */
        SUPPLIER_STORE,
        /** Only the people with a grant on the supplier store in the payload who hold CREDIT_VIEW there (D-173). */
        SUPPLIER_STORE_CREDIT
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
     *
     * <p>Two small forms keep a sentence readable when a field is missing, which pre-deploy rows and a failed lookup
     * both produce: {@code [ text {field}]} appears only when its fields are present, and {@code {field|fallback}}
     * falls back to the given words.
     */
    public String render(Map<String, String> fields) {
        // Optional parts, [ like this {field}]: kept only when every field named inside is present and not blank,
        // otherwise dropped whole, so a missing outlet name leaves no "()" and a missing order number no gap.
        String rendered = OPTIONAL.matcher(body).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(allPresent(m.group(1), fields) ? m.group(1) : ""));
        // A fallback for a missing name, {field|fallback}, so a sentence never starts with nothing.
        rendered = FALLBACK.matcher(rendered).replaceAll(m -> {
            String value = fields.get(m.group(1));
            return java.util.regex.Matcher.quoteReplacement(
                    value == null || value.isBlank() ? m.group(2) : value);
        });
        for (var field : fields.entrySet()) {
            rendered = rendered.replace("{" + field.getKey() + "}", field.getValue());
        }
        // Any placeholder the payload did not supply is dropped rather than left
        // as literal braces in front of a user.
        return rendered.replaceAll("\\{[a-zA-Z0-9_]+}", "").replaceAll("\\s{2,}", " ").trim();
    }

    private static final java.util.regex.Pattern OPTIONAL = java.util.regex.Pattern.compile("\\[([^\\[\\]]*)]");
    private static final java.util.regex.Pattern FALLBACK =
            java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_]+)\\|([^}]*)}");
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private static boolean allPresent(String part, Map<String, String> fields) {
        var names = PLACEHOLDER.matcher(part);
        while (names.find()) {
            String value = fields.get(names.group(1));
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
