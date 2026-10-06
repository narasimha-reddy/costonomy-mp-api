package com.costonomy.mp.discovery;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Typeahead matches aliases by prefix with an indexed query, not by loading the whole alias table (D-148). */
@AutoConfigureMockMvc
class SearchSuggestionsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("an alias is suggested by its prefix, and only by its prefix")
    void aliasByPrefix() throws Exception {
        var api = new ApiClient(mvc, json);
        String token = api.loginFresh();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        String alias = "zqx" + System.nanoTime();
        jdbc.update("insert into canonical_product_alias (canonical_product_id, alias, normalized_alias) "
                + "values (?, ?, ?)", productId, alias, alias);

        var byPrefix = api.get(token, "/api/v1/search/suggestions?q=" + alias.substring(0, 8));
        var names = new ArrayList<String>();
        byPrefix.at("/data").forEach(s -> names.add(s.at("/text").asText(s.at("/term").asText())));

        assertThat(byPrefix.at("/data").toString()).contains(alias);

        // The middle of an alias is not a prefix.
        var middle = api.get(token, "/api/v1/search/suggestions?q=" + alias.substring(3, 10));
        assertThat(middle.at("/data").toString()).doesNotContain(alias);
    }
}
