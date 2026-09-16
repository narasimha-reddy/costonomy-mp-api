package com.costonomy.mp.common.error;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A URL that matches no controller is the caller's mistake, not ours.
 *
 * <p>This exists because it was not true: an unmatched path fell through to the
 * static resource resolver, which raises {@code NoResourceFoundException} — a
 * type nothing handled — so the catch-all turned a client's typo into a
 * <b>500 INTERNAL_ERROR</b> telling them to try again. It cost a real debugging
 * session, and a unit test over the error catalogue could never have caught it:
 * the mapping that was missing lives in the dispatcher, not in the enum.
 */
@AutoConfigureMockMvc
class UnmappedRouteIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        // Authenticated on purpose. Unauthenticated, security answers 401 before
        // the dispatcher ever looks for a handler — which is correct, and is also
        // why this bug survived: it only appears once you are past the filter,
        // which is exactly where every real client is.
        token = new ApiClient(mvc, json).loginFresh();
    }

    @Test
    @DisplayName("an unknown path under the API is 404, not 500")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/search?q=butter").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("and says nothing about our internals while doing it")
    void revealsNothing() throws Exception {
        mvc.perform(get("/api/v1/no/such/thing").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                // Not the resolver's name, not the resource path, not a stack frame.
                .andExpect(jsonPath("$.error.message").value("We couldn't find what you're looking for."));
    }
}
