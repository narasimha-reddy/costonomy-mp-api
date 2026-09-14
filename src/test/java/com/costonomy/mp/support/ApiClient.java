package com.costonomy.mp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A small HTTP helper for integration tests.
 *
 * <p>Tests drive the API rather than calling services directly, because most of
 * what Phase 4 has to guarantee — that a scope check actually runs, that a denial
 * looks like a 404 — only holds if the controller, the service and the security
 * chain all agree.
 */
public final class ApiClient {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(50_000);
    private static final String MOCK_CODE = "123456";

    private final MockMvc mvc;
    private final ObjectMapper json;

    public ApiClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    /** A phone number no other test has used, so OTP cooldowns never collide. */
    public static String freshPhone() {
        return "98760" + String.format("%05d", PHONE_SEQ.incrementAndGet());
    }

    /** Register or sign in, returning an access token. */
    public String login(String phone) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", phone, "purpose", "LOGIN"))))
                .andReturn();

        String body = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("phone", phone, "otp", MOCK_CODE, "purpose", "LOGIN"))))
                .andReturn().getResponse().getContentAsString();

        return json.readTree(body).at("/data/accessToken").asText();
    }

    public String loginFresh() throws Exception {
        return login(freshPhone());
    }

    public JsonNode post(String token, String path, Object body) throws Exception {
        String response = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    public int postStatus(String token, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    public JsonNode get(String token, String path) throws Exception {
        String response = mvc.perform(
                        MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    public int getStatus(String token, String path) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    public int patchStatus(String token, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }
}
