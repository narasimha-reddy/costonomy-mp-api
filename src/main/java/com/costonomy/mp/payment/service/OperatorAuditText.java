package com.costonomy.mp.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The text an operator's money-moving action leaves in the audit log, always short enough for
 * {@code audit_log.reason} (VARCHAR(500)), so that writing it can never be what fails (D-110).
 *
 * <p>What is kept, in this order of importance: the lead (who-what of the step) and the confirmations the
 * operator gave, then the evidence (its length, a digest of the whole text and its first characters, so that
 * a text kept elsewhere can be matched to the record), and then as much of the note as still fits. A text that
 * is cut ends in an ellipsis.
 */
final class OperatorAuditText {

    /** {@code audit_log.reason} is VARCHAR(500). */
    static final int MAX = 500;

    /** The first characters of the evidence that are kept in the record. */
    static final int EVIDENCE_KEPT = 120;

    /** The confirmation a part on a payment the provider does not know needs from every person (D-110). */
    static final String UNKNOWN_PAYMENT_CONFIRMED =
            "[payment unknown to the provider; confirmed: another, retired account, nothing sent from it] ";

    private OperatorAuditText() {
    }

    /**
     * @param lead      what the step is, put first ({@code null} for nothing)
     * @param confirmed the confirmations of a payment the provider does not know, put next
     * @param note      the operator's note, cut to what is left
     * @param evidence  what the operator gave as evidence, or null
     */
    static String of(String lead, boolean confirmed, String note, String evidence) {
        var head = new StringBuilder(lead == null ? "" : lead);
        if (confirmed) {
            head.append(UNKNOWN_PAYMENT_CONFIRMED);
        }
        String ev = evidence(evidence);
        String full = head + (note == null ? "" : note.trim()) + ev;
        if (full.length() <= MAX) {
            return full;
        }
        int room = Math.max(0, MAX - head.length() - ev.length());
        String n = note == null ? "" : clip(note.trim(), room);
        return clip(head + n + ev, MAX);
    }

    /** ` Evidence (412 chars, sha256 0123456789abcdef): first characters...`, or nothing for no evidence. */
    static String evidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return "";
        }
        String trimmed = evidence.trim();
        return " Evidence (" + trimmed.length() + " chars, sha256 " + digest(trimmed) + "): "
                + clip(trimmed, EVIDENCE_KEPT);
    }

    /** The first sixteen hex digits of the SHA-256 of the text. */
    static String digest(String text) {
        try {
            var sha = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** At most {@code max} characters, ending in an ellipsis if cut, and never in the middle of a surrogate pair. */
    static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        if (max <= 1) {
            return text.substring(0, Math.max(0, max));
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
