package com.costonomy.mp.identity.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * MSG91 SMS OTP. Production adapter.
 *
 * <p>Uses MSG91's <em>flow</em> API to send a code we generated, rather than
 * MSG91's own OTP endpoints which would generate and verify the code themselves.
 * Letting the provider own the code would put expiry, attempt limits and resend
 * cooldown — all specified in doc 09 §1 — outside our control, and would make the
 * mock a materially different flow from production rather than a drop-in.
 *
 * <p>Credentials come from the environment; doc 09 §4 forbids them in source.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.otp", havingValue = "MSG91")
@Slf4j
public class Msg91OtpProvider implements OtpProvider {

    private final RestClient client;
    private final String authKey;
    private final String templateId;
    private final String senderId;

    public Msg91OtpProvider(
            @Value("${costonomy.mp.msg91.base-url:https://control.msg91.com}") String baseUrl,
            @Value("${costonomy.mp.msg91.auth-key}") String authKey,
            @Value("${costonomy.mp.msg91.template-id}") String templateId,
            @Value("${costonomy.mp.msg91.sender-id:COSTON}") String senderId) {

        this.authKey = authKey;
        this.templateId = templateId;
        this.senderId = senderId;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutFactory())
                .build();
    }

    @Override
    public OtpSendResult send(String phone, String code) {
        try {
            JsonNode response = client.post()
                    .uri("/api/v5/flow/")
                    .header("authkey", authKey)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "template_id", templateId,
                            "sender", senderId,
                            // MSG91 wants the number without the leading '+'.
                            "mobiles", phone.startsWith("+") ? phone.substring(1) : phone,
                            "otp", code))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // The response body can echo the request, which contains
                        // the code. Never log it; the status is enough to act on.
                        throw new OtpDeliveryException(
                                "MSG91 rejected the send with status " + res.getStatusCode());
                    })
                    .body(JsonNode.class);

            String reference = response != null && response.hasNonNull("request_id")
                    ? response.get("request_id").asText()
                    : null;

            return new OtpSendResult(reference);

        } catch (OtpDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            // Message only, never the exception's response body.
            throw new OtpDeliveryException("Could not reach MSG91: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String name() {
        return "MSG91";
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Bounded, because a hung SMS gateway must not hold a request thread —
        // and the user is staring at a spinner (§23A.6 "provider failure" state).
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return factory;
    }
}
