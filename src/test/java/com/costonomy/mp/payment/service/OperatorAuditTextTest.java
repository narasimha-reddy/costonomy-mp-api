package com.costonomy.mp.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit text of an operator's action always fits audit_log.reason (VARCHAR(500)) and keeps what matters. */
class OperatorAuditTextTest {

    @Test
    @DisplayName("a short note and no evidence are kept whole")
    void shortTextIsUnchanged() {
        assertThat(OperatorAuditText.of(null, false, "  checked with the bank ", null)).isEqualTo("checked with the bank");
        assertThat(OperatorAuditText.of("Always two people: waiting for a second person. ", false, "checked", null))
                .isEqualTo("Always two people: waiting for a second person. checked");
    }

    @Test
    @DisplayName("the longest note and evidence a request can carry (400 and 400, with every lead and confirmation) fit in 500 characters and keep the lead, the confirmation, the evidence's length and digest and its first characters")
    void theLongestInputFits() {
        String note = "n".repeat(400);
        String evidence = "e".repeat(400);
        for (String lead : new String[]{null, "Always two people: waiting for a second person. ",
                "Above ₹10000: waiting for a second person. "}) {
            for (boolean confirmed : new boolean[]{false, true}) {
                String text = OperatorAuditText.of(lead, confirmed, note, evidence);

                assertThat(text.length()).isLessThanOrEqualTo(OperatorAuditText.MAX);
                if (lead != null) {
                    assertThat(text).startsWith(lead);
                }
                assertThat(text.contains(OperatorAuditText.UNKNOWN_PAYMENT_CONFIRMED)).isEqualTo(confirmed);
                assertThat(text).contains("Evidence (400 chars, sha256 " + OperatorAuditText.digest(evidence) + "): "
                        + "e".repeat(OperatorAuditText.EVIDENCE_KEPT - 1) + "…");
                assertThat(text).contains("n".repeat(50)).contains("…");
            }
        }
    }

    @Test
    @DisplayName("the digest identifies the whole evidence: two texts with the same beginning have different digests")
    void theDigestSeparatesLongEvidence() {
        String a = "x".repeat(300) + "A";
        String b = "x".repeat(300) + "B";
        assertThat(OperatorAuditText.evidence(a)).isNotEqualTo(OperatorAuditText.evidence(b));
        assertThat(OperatorAuditText.digest("abc")).isEqualTo("ba7816bf8f01cfea");
        assertThat(OperatorAuditText.evidence("  ")).isEmpty();
        assertThat(OperatorAuditText.evidence(null)).isEmpty();
    }

    @Test
    @DisplayName("a cut never splits a surrogate pair, whatever the text")
    void neverSplitsASurrogatePair() {
        String emoji = "😀";
        for (int offset = 0; offset < 3; offset++) {
            String text = OperatorAuditText.of(null, true, "a".repeat(offset) + emoji.repeat(300), emoji.repeat(300));
            assertThat(text.length()).isLessThanOrEqualTo(OperatorAuditText.MAX);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertThat(i + 1).isLessThan(text.length());
                    assertThat(Character.isLowSurrogate(text.charAt(i + 1))).isTrue();
                } else if (Character.isLowSurrogate(c)) {
                    assertThat(Character.isHighSurrogate(text.charAt(i - 1))).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("clip: at most the length asked, an ellipsis when cut")
    void clip() {
        assertThat(OperatorAuditText.clip("abcdef", 6)).isEqualTo("abcdef");
        assertThat(OperatorAuditText.clip("abcdefg", 6)).isEqualTo("abcde…");
        assertThat(OperatorAuditText.clip("abc", 0)).isEmpty();
    }
}
