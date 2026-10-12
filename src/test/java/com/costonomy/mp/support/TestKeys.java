package com.costonomy.mp.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/** costonomy-common-api's signing key, as the tests' stand-in for common uses it (RS256, served as JWKS). */
public final class TestKeys {

    public static final String ISSUER = "https://common.test/costonomy-common-api";
    private static final RSAKey KEY = generate();

    private TestKeys() {
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String jwks() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    /**
     * An mp-mobile access token for {@code userId}, as common issues it after phone + code. Long-lived: the starter
     * checks expiry against the app's clock, which some tests move days or months ahead.
     */
    public static String accessToken(long userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(String.valueOf(userId))
                .audience(List.of("MARKETPLACE", "COMMON")).claim("azp", "mp-mobile").claim("sid", "sid-" + userId)
                .claim("amr", List.of("otp")).claim("token_use", "access").jwtID("jti-" + System.nanoTime())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(java.time.Duration.ofDays(3650)))).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
