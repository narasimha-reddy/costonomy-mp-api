package com.costonomy.mp.storage.invoice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceLinkSignerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private static InvoiceLinkSigner at(String secret, Instant now) {
        return new InvoiceLinkSigner(secret, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a fresh token gives back its outlet, page and expiry")
    void roundTrip() {
        var signer = at("test-link-key", NOW);
        var token = signer.sign(7, 99, NOW.plusSeconds(300));
        var claims = signer.verify(token).orElseThrow();
        assertThat(claims.outletId()).isEqualTo(7);
        assertThat(claims.pageId()).isEqualTo(99);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("a token stops working at its expiry")
    void expires() {
        var token = at("k", NOW).sign(7, 99, NOW.plus(Duration.ofMinutes(5)));
        assertThat(at("k", NOW.plus(Duration.ofMinutes(4))).verify(token)).isPresent();
        assertThat(at("k", NOW.plus(Duration.ofMinutes(5))).verify(token)).isEmpty();
        assertThat(at("k", NOW.plus(Duration.ofHours(1))).verify(token)).isEmpty();
    }

    @Test
    @DisplayName("a changed page, outlet or expiry, a wrong key and garbage are all refused")
    void tamper() {
        var signer = at("k", NOW);
        var token = signer.sign(7, 99, NOW.plusSeconds(300));
        var enc = Base64.getUrlEncoder().withoutPadding();
        String signature = token.substring(token.indexOf('.') + 1);
        for (String payload : new String[]{"7|100|" + NOW.plusSeconds(300).getEpochSecond(),
                "8|99|" + NOW.plusSeconds(300).getEpochSecond(), "7|99|" + NOW.plusSeconds(99999).getEpochSecond()}) {
            var forged = enc.encodeToString(payload.getBytes()) + "." + signature;
            assertThat(signer.verify(forged)).describedAs(payload).isEmpty();
        }
        assertThat(at("another key", NOW).verify(token)).isEmpty();
        for (String junk : new String[]{null, "", ".", "abc", "a.b", token + "x", token.replace('.', '_'), "%%%.%%%"}) {
            assertThat(signer.verify(junk)).describedAs(String.valueOf(junk)).isEmpty();
        }
    }
}
