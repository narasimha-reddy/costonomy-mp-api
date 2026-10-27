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
 * chain all agree. The identity calls the app now makes to costonomy-common-api (creating a restaurant or supplier,
 * editing outlets and stores, inviting a member) go to the tests' stand-in for common instead ({@link CommonRoutes},
 * D-208).
 */
public final class ApiClient {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(50_000);

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

    /**
     * Register or sign in, returning an access token. Sign-in is costonomy-common-api's (phone + code in mp-mobile);
     * the tests' stand-in for common creates the account on first use and signs the token ({@link LocalCommon}).
     */
    public String login(String phone) {
        return LocalCommon.signIn(phone);
    }

    public String loginFresh() throws Exception {
        return login(freshPhone());
    }

    public JsonNode post(String token, String path, Object body) throws Exception {
        var common = CommonRoutes.handle("POST", path, token, body);
        if (common.isPresent()) {
            return common.get().body();
        }
        String response = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    public JsonNode postWithHeader(String token, String path, Object body, String headerName, String headerValue) throws Exception {
        var common = CommonRoutes.handle("POST", path, token, body);
        if (common.isPresent()) {
            return common.get().body();
        }
        String response = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header(headerName, headerValue)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    public int postStatus(String token, String path, Object body) throws Exception {
        var common = CommonRoutes.handle("POST", path, token, body);
        if (common.isPresent()) {
            return common.get().status();
        }
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    public int postStatusWithHeader(String token, String path, Object body, String headerName, String headerValue) throws Exception {
        var common = CommonRoutes.handle("POST", path, token, body);
        if (common.isPresent()) {
            return common.get().status();
        }
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header(headerName, headerValue)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    public JsonNode get(String token, String path) throws Exception {
        var common = CommonRoutes.handle("GET", path, token, null);
        if (common.isPresent()) {
            return common.get().body();
        }
        String response = mvc.perform(
                        MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    public int getStatus(String token, String path) throws Exception {
        var common = CommonRoutes.handle("GET", path, token, null);
        if (common.isPresent()) {
            return common.get().status();
        }
        return mvc.perform(
                        MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    public int patchStatus(String token, String path, Object body) throws Exception {
        var common = CommonRoutes.handle("PATCH", path, token, body);
        if (common.isPresent()) {
            return common.get().status();
        }
        return mvc.perform(MockMvcRequestBuilders.patch(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }
}
