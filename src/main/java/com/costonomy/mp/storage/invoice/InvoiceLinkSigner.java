package com.costonomy.mp.storage.invoice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Signed, expiring page tokens for local storage (D-113). A token names one page of one outlet and an
 * expiry, and carries an HMAC over them: {@code <b64(outlet|page|expiry)>.<b64(hmac)>}. It names a page
 * row, never a storage key, so the route cannot be pointed at an arbitrary file.
 *
 * <p>With no secret configured a random one is made at start: links then die on restart, which is
 * harmless for links that live five minutes.
 */
@Component
public class InvoiceLinkSigner {

    public record Claims(long outletId, long pageId, Instant expiresAt) {
    }

    private final byte[] secret;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public InvoiceLinkSigner(@Value("${costonomy.mp.invoices.link-secret:}") String configured) {
        this(configured, Clock.systemUTC());
    }

    public InvoiceLinkSigner(String configured, Clock clock) {
        this.clock = clock;
        if (configured == null || configured.isBlank()) {
            this.secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String sign(long outletId, long pageId, Instant expiresAt) {
        String payload = outletId + "|" + pageId + "|" + expiresAt.getEpochSecond();
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(mac(payload));
    }

    /** The claims when the signature is ours and the token has not expired; empty otherwise. */
    public Optional<Claims> verify(String token) {
        try {
            if (token == null || token.length() > 300) {
                return Optional.empty();
            }
            int dot = token.indexOf('.');
            if (dot < 1) {
                return Optional.empty();
            }
            var dec = Base64.getUrlDecoder();
            String payload = new String(dec.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] given = dec.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, mac(payload))) {
                return Optional.empty();
            }
            String[] parts = payload.split("\\|");
            if (parts.length != 3) {
                return Optional.empty();
            }
            var claims = new Claims(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Instant.ofEpochSecond(Long.parseLong(parts[2])));
            return claims.expiresAt().isAfter(clock.instant()) ? Optional.of(claims) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
