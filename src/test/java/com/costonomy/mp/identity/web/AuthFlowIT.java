package com.costonomy.mp.identity.web;

import com.costonomy.mp.identity.domain.OtpStatus;
import com.costonomy.mp.identity.domain.RefreshTokenStatus;
import com.costonomy.mp.identity.repository.OtpVerificationRepository;
import com.costonomy.mp.identity.repository.RefreshTokenRepository;
import com.costonomy.mp.identity.repository.UserRepository;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authentication flow, end to end, against a real database.
 *
 * <p>Covers the controls doc 09 §1 requires — expiry, attempt limit, resend
 * cooldown, rotation and revocation — through the HTTP API rather than by calling
 * services, because several of them only hold if the controller, the service and
 * the schema agree.
 *
 * <p>The mock OTP provider is configured with a fixed code in the {@code test}
 * profile, which is how these tests know what to submit. Nothing reads the code
 * out of the response or the log, because it is never in either.
 */
@AutoConfigureMockMvc
class AuthFlowIT extends AbstractIntegrationTest {

    private static final String MOCK_CODE = "123456";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private OtpVerificationRepository otps;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Unique per test, so the per-phone cooldown and hourly limit never collide. */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(1000);

    private static String freshPhone() {
        return "99990" + String.format("%05d", PHONE_SEQ.incrementAndGet());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private JsonNode requestOtp(String phone) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("phone", phone, "purpose", "LOGIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode verifyOtp(String phone, String code, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("phone", phone, "otp", code, "purpose", "LOGIN"))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode login(String phone) throws Exception {
        requestOtp(phone);
        return verifyOtp(phone, MOCK_CODE, 200).get("data");
    }

    // ── Login ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("requesting a code never returns the code itself")
        void otpIsNeverInTheResponse() throws Exception {
            String phone = freshPhone();
            JsonNode response = requestOtp(phone);

            // Doc 09 §16. Asserted explicitly because a "helpful" debug field is
            // exactly the kind of thing that gets added and then ships.
            assertThat(response.toString()).doesNotContain(MOCK_CODE);
            assertThat(response.at("/data/maskedPhone").asText()).contains("******");
            assertThat(response.at("/data/expiresAt").isNull()).isFalse();
            assertThat(response.at("/data/maxAttempts").asInt()).isPositive();
        }

        @Test
        @DisplayName("a first-time number becomes a user on verification")
        void firstLoginCreatesUser() throws Exception {
            String phone = freshPhone();
            assertThat(users.findByPhone("+91" + phone)).isEmpty();

            JsonNode session = login(phone);

            assertThat(session.get("accessToken").asText()).isNotBlank();
            assertThat(session.get("refreshToken").asText()).isNotBlank();
            assertThat(session.at("/user/phone").asText()).isEqualTo("+91" + phone);
            assertThat(users.findByPhone("+91" + phone)).isPresent();
        }

        @Test
        @DisplayName("the same number in different formats is one user, not two")
        void normalisesToOneUser() throws Exception {
            String national = freshPhone();
            login(national);

            // Same human, typed with the country code this time. A second row
            // here would mean two accounts with independent permissions.
            String e164 = "+91" + national;
            mvc.perform(post("/api/v1/auth/otp/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("phone", e164, "purpose", "LOGIN"))))
                    // Blocked by the resend cooldown, which is itself the proof:
                    // the cooldown is keyed on the normalised number.
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("OTP_RESEND_TOO_SOON"));

            assertThat(users.findByPhone(e164)).isPresent();
            assertThat(users.count()).isPositive();
        }

        @Test
        @DisplayName("a wrong code says how many attempts remain")
        void wrongCodeReportsRemainingAttempts() throws Exception {
            String phone = freshPhone();
            requestOtp(phone);

            JsonNode error = verifyOtp(phone, "000000", 422);

            assertThat(error.at("/error/code").asText()).isEqualTo("OTP_INVALID");
            // §23A.7 shows this to the user.
            assertThat(error.at("/error/details/attemptsRemaining").asInt()).isEqualTo(4);
        }

        @Test
        @DisplayName("the attempt limit is enforced and is terminal")
        void attemptsAreLimited() throws Exception {
            String phone = freshPhone();
            requestOtp(phone);

            for (int i = 0; i < 4; i++) {
                verifyOtp(phone, "000000", 422);
            }
            // Fifth wrong attempt exhausts the budget.
            JsonNode exhausted = verifyOtp(phone, "000000", 429);
            assertThat(exhausted.at("/error/code").asText()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");

            // And the correct code no longer works — otherwise the limit would
            // only slow an attacker down rather than stop them.
            JsonNode afterExhaustion = verifyOtp(phone, MOCK_CODE, 422);
            assertThat(afterExhaustion.at("/error/code").asText()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        @DisplayName("a second code request within the cooldown is refused with a retry hint")
        void resendCooldownIsEnforced() throws Exception {
            String phone = freshPhone();
            requestOtp(phone);

            mvc.perform(post("/api/v1/auth/otp/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("phone", phone, "purpose", "LOGIN"))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("OTP_RESEND_TOO_SOON"))
                    // §23A.7 renders a countdown from this.
                    .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        }

        @Test
        @DisplayName("verifying with no outstanding code reports expiry, not 'no code requested'")
        void unknownChallengeLooksLikeExpiry() throws Exception {
            // Saying "no code was requested" would confirm to an attacker whether
            // a given number is mid-login.
            JsonNode error = verifyOtp(freshPhone(), MOCK_CODE, 422);
            assertThat(error.at("/error/code").asText()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        @DisplayName("an expired code is refused")
        @Transactional
        void expiredCodeIsRefused() throws Exception {
            String phone = freshPhone();
            requestOtp(phone);

            var challenge = otps.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(
                    "+91" + phone, com.costonomy.mp.identity.domain.OtpPurpose.LOGIN).orElseThrow();
            challenge.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
            otps.saveAndFlush(challenge);

            JsonNode error = verifyOtp(phone, MOCK_CODE, 422);
            assertThat(error.at("/error/code").asText()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        @DisplayName("requesting a new code retires the previous one")
        void newCodeSupersedesTheOld() throws Exception {
            String phone = freshPhone();
            requestOtp(phone);

            // Age the challenge past the resend cooldown, to simulate a
            // legitimate resend a few minutes later. Done in SQL because
            // created_at is @CreationTimestamp and updatable = false, so setting
            // it on the entity is silently ignored.
            var first = otps.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(
                    "+91" + phone, com.costonomy.mp.identity.domain.OtpPurpose.LOGIN).orElseThrow();
            // UTC_TIMESTAMP rather than a java.sql.Timestamp: the JVM here runs in
            // IST while the database stores UTC (doc 02 §1), and a Timestamp
            // converted through the JVM default zone lands 5.5 hours in the
            // *future* — which reads as an even fresher challenge.
            jdbc.update(
                    "update otp_verification set created_at = date_sub(utc_timestamp(6), interval 5 minute) where id = ?",
                    first.getId());

            requestOtp(phone);

            // Without superseding, two live challenges would mean two attempt
            // budgets against the same six-digit space.
            var superseded = otps.findById(first.getId()).orElseThrow();
            assertThat(superseded.getStatus()).isEqualTo(OtpStatus.SUPERSEDED);
            assertThat(otps.findByPhoneAndPurposeAndStatus("+91" + phone,
                    com.costonomy.mp.identity.domain.OtpPurpose.LOGIN, OtpStatus.PENDING))
                    .hasSize(1);
        }
    }

    // ── Refresh tokens ───────────────────────────────────────────────────

    @Nested
    @DisplayName("refresh tokens")
    class Refresh {

        @Test
        @DisplayName("refreshing rotates the token and invalidates the old one")
        void rotationInvalidatesPredecessor() throws Exception {
            JsonNode session = login(freshPhone());
            String original = session.get("refreshToken").asText();

            String rotatedBody = mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", original))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String replacement = json.readTree(rotatedBody).at("/data/refreshToken").asText();
            assertThat(replacement).isNotEqualTo(original);

            // The replacement works.
            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", replacement))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("replaying a rotated token revokes every session for that user")
        void replayRevokesAllSessions() throws Exception {
            JsonNode session = login(freshPhone());
            String original = session.get("refreshToken").asText();
            Long userId = session.at("/user/id").asLong();

            String rotatedBody = mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", original))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String replacement = json.readTree(rotatedBody).at("/data/refreshToken").asText();

            // An attacker replays the copy they stole before the user refreshed.
            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", original))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

            // Rotation alone would only reject the attacker's copy and tell us
            // nothing. Because we cannot know which holder is legitimate, both
            // lose access and the real user re-authenticates with an OTP.
            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", replacement))))
                    .andExpect(status().isUnauthorized());

            assertThat(refreshTokens.findByUserIdAndStatus(userId, RefreshTokenStatus.ACTIVE))
                    .describedAs("no session should survive a detected replay")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown refresh token is rejected")
        void unknownTokenRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("refreshToken", "not-a-real-token"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("logout revokes the token")
        void logoutRevokes() throws Exception {
            JsonNode session = login(freshPhone());
            String accessToken = session.get("accessToken").asText();
            String refreshToken = session.get("refreshToken").asText();

            mvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                    .andExpect(status().isOk());

            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Protected endpoints ──────────────────────────────────────────────

    @Nested
    @DisplayName("protected endpoints")
    class Protected {

        @Test
        @DisplayName("/auth/me requires a token and returns the caller")
        void meRequiresAuthentication() throws Exception {
            mvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    // Spring Security rejects before the dispatcher, so this also
                    // proves SecurityConfig's entry point emits our envelope
                    // rather than Spring's HTML error page.
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            JsonNode session = login(freshPhone());

            mvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Bearer " + session.get("accessToken").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user.id").value(session.at("/user/id").asLong()))
                    // Empty until Phase 4, but present — the client routes on this
                    // and must never infer a role locally (§23A.30).
                    .andExpect(jsonPath("$.data.memberships").isArray());
        }

        @Test
        @DisplayName("a forged token is rejected")
        void forgedTokenRejected() throws Exception {
            mvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Devices ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("devices")
    class Devices {

        @Test
        @DisplayName("a push token moves to whichever user registered it last")
        void pushTokenIsReassigned() throws Exception {
            String sharedToken = "fcm-token-" + freshPhone();

            JsonNode first = login(freshPhone());
            registerDevice(first.get("accessToken").asText(), sharedToken);

            JsonNode second = login(freshPhone());
            registerDevice(second.get("accessToken").asText(), sharedToken);

            // Not a duplicate row: the first user must stop receiving the second
            // user's order notifications, which carry values and supplier names
            // between unrelated businesses.
            mvc.perform(get("/api/v1/devices")
                            .header("Authorization", "Bearer " + first.get("accessToken").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());

            mvc.perform(get("/api/v1/devices")
                            .header("Authorization", "Bearer " + second.get("accessToken").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].platform").value("ANDROID"));
        }

        @Test
        @DisplayName("a device belonging to someone else cannot be unregistered")
        void cannotUnregisterAnotherUsersDevice() throws Exception {
            JsonNode owner = login(freshPhone());
            String body = registerDevice(owner.get("accessToken").asText(), "fcm-" + freshPhone());
            long deviceId = json.readTree(body).at("/data/id").asLong();

            JsonNode stranger = login(freshPhone());

            // Doc 09 §3: changing an id must not reach another tenant's record.
            // Reported as not-found rather than forbidden, so the id space cannot
            // be probed for which devices exist.
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/devices/" + deviceId)
                            .header("Authorization", "Bearer " + stranger.get("accessToken").asText()))
                    .andExpect(status().isNotFound());
        }

        private String registerDevice(String accessToken, String pushToken) throws Exception {
            return mvc.perform(post("/api/v1/devices")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "platform", "ANDROID",
                                    "pushToken", pushToken,
                                    "appVersion", "0.1.0"))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
    }
}
