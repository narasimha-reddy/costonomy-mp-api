package com.costonomy.mp.credit.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of a reminder and of the supplier digest (D-171, D-173). Composed here, on the server, so the money and the
 * dates are formatted once and the supplier's preview is byte for byte what the restaurant receives. Pure.
 */
public final class CreditReminderText {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    /** Invoice numbers named in one message before it says "+N more". */
    private static final int NAMED = 3;

    private CreditReminderText() {
    }

    /** One invoice a message is about. */
    public record Item(String invoiceNumber, BigDecimal outstanding, LocalDate dueDate, CreditDueState state) {
    }

    /**
     * "Sri Dairy: ₹6,500 overdue since 24 Sep (INV-1). Pay in Mandi or tell them you paid." Items already past
     * their due date come first (worded "overdue since" when any is past its grace period, "was due on" when all
     * are still inside it), then what is due today or soon. A note from the supplier follows.
     */
    public static String reminder(String supplierName, List<Item> items, String note) {
        String name = cleanNote(supplierName);
        if (name == null) {
            name = "Your supplier";
        }
        var past = items.stream().filter(i -> i.state() == CreditDueState.OVERDUE
                || i.state() == CreditDueState.IN_GRACE).toList();
        var upcoming = items.stream().filter(i -> i.state() == CreditDueState.DUE_TODAY
                || i.state() == CreditDueState.DUE_SOON).toList();

        var sentences = new ArrayList<String>();
        if (!past.isEmpty()) {
            boolean overdue = past.stream().anyMatch(i -> i.state() == CreditDueState.OVERDUE);
            LocalDate earliest = past.stream().map(Item::dueDate).min(LocalDate::compareTo).orElseThrow();
            sentences.add("%s %s %s%s.".formatted(money(total(past)),
                    overdue ? "overdue since" : "was due on", DAY.format(earliest), named(past)));
        }
        if (!upcoming.isEmpty()) {
            boolean today = upcoming.stream().allMatch(i -> i.state() == CreditDueState.DUE_TODAY);
            LocalDate earliest = upcoming.stream().map(Item::dueDate).min(LocalDate::compareTo).orElseThrow();
            sentences.add("%s %s%s.".formatted(money(total(upcoming)),
                    today ? "due today" : "due on " + DAY.format(earliest), named(upcoming)));
        }
        String text = name + ": " + String.join(" ", sentences) + " Pay in Mandi or tell them you paid.";
        String clean = cleanNote(note);
        return clean == null ? text : text + " Message from " + name + ": " + clean;
    }

    /** A note as it is sent: whitespace collapsed to single spaces and no braces, so the preview equals what is delivered. */
    public static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String clean = note.replace("{", "").replace("}", "").replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String named(List<Item> items) {
        var numbers = items.stream().map(Item::invoiceNumber).limit(NAMED).toList();
        String list = String.join(", ", numbers);
        if (items.size() > NAMED) {
            list += " +" + (items.size() - NAMED) + " more";
        }
        return " (" + list + ")";
    }

    private static BigDecimal total(List<Item> items) {
        return items.stream().map(Item::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Rupees the way an Indian reader groups them: ₹6,500 and ₹12,34,567.50. Whole rupees carry no decimals, anything
     * else two. Never rounds a figure the app would show differently: amounts here have at most two decimals already.
     */
    public static String money(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String plain = rounded.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String fraction = plain.substring(plain.indexOf('.') + 1);

        var grouped = new StringBuilder();
        int length = whole.length();
        if (length <= 3) {
            grouped.append(whole);
        } else {
            String lastThree = whole.substring(length - 3);
            String rest = whole.substring(0, length - 3);
            for (int i = 0; i < rest.length(); i++) {
                if (i > 0 && (rest.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(rest.charAt(i));
            }
            grouped.append(',').append(lastThree);
        }
        String text = "₹" + grouped + ("00".equals(fraction) ? "" : "." + fraction);
        return negative ? "-" + text : text;
    }

    /** What the digest says, one part per thing that is not zero; empty when there is nothing to say (D-173). */
    public static String digest(int claimsWaiting, int claimsStale, BigDecimal overdue, int overdueRestaurants,
                                BigDecimal dueThisWeek, int requestsPending, int payoutsPending) {
        var parts = new ArrayList<String>();
        if (claimsWaiting > 0) {
            String claims = claimsWaiting + (claimsWaiting == 1 ? " payment claim waiting" : " payment claims waiting");
            parts.add(claimsStale > 0 ? claims + " (" + claimsStale + " for 7+ days)" : claims);
        }
        if (overdue.signum() > 0) {
            parts.add(money(overdue) + " overdue from " + overdueRestaurants
                    + (overdueRestaurants == 1 ? " restaurant" : " restaurants"));
        }
        if (dueThisWeek.signum() > 0) {
            parts.add(money(dueThisWeek) + " due this week");
        }
        if (requestsPending > 0) {
            parts.add(requestsPending + (requestsPending == 1 ? " credit request" : " credit requests") + " to answer");
        }
        if (payoutsPending > 0) {
            parts.add(payoutsPending + (payoutsPending == 1 ? " payout" : " payouts") + " pending");
        }
        return String.join(" · ", parts);
    }
}
