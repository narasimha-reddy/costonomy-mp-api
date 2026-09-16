package com.costonomy.mp.catalog;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pack states an amount, one way or the other.
 *
 * <p>Doc 01 §7: the canonical product is the axis every comparison turns on, and a
 * comparison of two prices only means anything if both name the same measure. A
 * SKU packed in KG says its amount in its unit; a SKU packed in PKT does not, and
 * has to say it separately. These tests are that sentence, enforced.
 */
@AutoConfigureMockMvc
class SkuMeasureIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;
    private String token;
    private long storeId;
    private long productId;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        token = api.loginFresh();
        var created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Spice Co Pvt Ltd", "displayName", "Spice Co",
                "firstStore", Map.of("name", "Spice Co store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        storeId = created.get("stores").get(0).get("id").asLong();
        productId = TestCatalog.freshProduct(jdbc, "turmeric");
    }

    private Map<String, Object> sku(String packUnit, Object measureValue, Object measureUnit) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "canonicalProductId", productId,
                "skuCode", "TUR-" + System.nanoTime(),
                "name", "Turmeric Powder",
                "packSize", 1,
                "packUnit", packUnit,
                "sellingPrice", "220",
                "gstRate", "5"));
        if (measureValue != null) body.put("measureValue", measureValue);
        if (measureUnit != null) body.put("measureUnit", measureUnit);
        return body;
    }

    /**
     * 400, not 422. These are conditionally-required fields — the same class of
     * mistake as a missing name, which this endpoint already answers 400 — rather
     * than a well-formed request the business refuses. `ErrorCodeTest` draws that
     * line and this stays on the validation side of it.
     */
    private int create(Map<String, Object> body) throws Exception {
        return api.postStatus(token, "/api/v1/supplier-stores/" + storeId + "/skus", body);
    }

    @Test
    @DisplayName("1 PKT of 500 GM is stored as both")
    void containerCarriesItsMeasure() throws Exception {
        long id = api.post(token, "/api/v1/supplier-stores/" + storeId + "/skus",
                sku("PKT", 500, "GM")).at("/data/id").asLong();

        var row = jdbc.queryForMap(
                "select pack_unit, measure_value, measure_unit from supplier_sku where id = ?", id);
        assertThat(row.get("pack_unit")).isEqualTo("PKT");
        assertThat(row.get("measure_unit")).isEqualTo("GM");
        assertThat(row.get("measure_value")).asString().startsWith("500");
    }

    @Test
    @DisplayName("a container with no measure is refused")
    void containerWithoutMeasureIsRefused() throws Exception {
        // "1 PKT" says how the goods are bundled and never how much is bought.
        assertThat(create(sku("PKT", null, null))).isEqualTo(400);
        assertThat(create(sku("CASE", 24, null))).isEqualTo(400);
        assertThat(create(sku("TIN", null, "KG"))).isEqualTo(400);
    }

    @Test
    @DisplayName("a measure on a unit that is already an amount is refused")
    void amountWithMeasureIsRefused() throws Exception {
        // "1 KG of 500 GM" is two statements of one quantity, and two chances to
        // disagree — with the disagreement found by whoever received the weight.
        assertThat(create(sku("KG", 500, "GM"))).isEqualTo(400);
    }

    @Test
    @DisplayName("contents must be measurable, and not the pack's own unit")
    void measureMustMakeSense() throws Exception {
        assertThat(create(sku("CASE", 2, "BULK"))).isEqualTo(400);   // a riddle
        assertThat(create(sku("PKT", 3, "PKT"))).isEqualTo(400);     // circular
        assertThat(create(sku("CASE", 24, "PKT"))).isEqualTo(200);   // ordinary
    }

    @Test
    @DisplayName("units are normalised on the way in, so one spelling reaches the database")
    void unitsAreNormalised() throws Exception {
        long id = api.post(token, "/api/v1/supplier-stores/" + storeId + "/skus",
                sku("packet", 500, "g")).at("/data/id").asLong();

        // Comparison depends on this: "packet"/"PKT" and "g"/"GM" must not become
        // separate units that no query can bring back together.
        var row = jdbc.queryForMap(
                "select pack_unit, measure_unit from supplier_sku where id = ?", id);
        assertThat(row.get("pack_unit")).isEqualTo("PKT");
        assertThat(row.get("measure_unit")).isEqualTo("GM");
    }

    @Test
    @DisplayName("changing KG to PKT without a measure is refused")
    void switchingToAContainerNeedsAMeasure() throws Exception {
        long id = api.post(token, "/api/v1/supplier-stores/" + storeId + "/skus",
                sku("KG", null, null)).at("/data/id").asLong();

        // The dangerous edit: only packUnit is sent, so a validation that looked
        // at the request alone would see no measure to object to.
        assertThat(api.patchStatus(token, "/api/v1/supplier-skus/" + id,
                Map.of("packUnit", "PKT"))).isEqualTo(400);

        assertThat(api.patchStatus(token, "/api/v1/supplier-skus/" + id,
                Map.of("packUnit", "PKT", "measureValue", 500, "measureUnit", "GM")))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("moving back to a plain unit clears the measure")
    void switchingAwayClearsTheMeasure() throws Exception {
        long id = api.post(token, "/api/v1/supplier-stores/" + storeId + "/skus",
                sku("PKT", 500, "GM")).at("/data/id").asLong();

        assertThat(api.patchStatus(token, "/api/v1/supplier-skus/" + id,
                Map.of("packUnit", "KG"))).isEqualTo(200);

        // Left behind, it would be a stale 500 GM on a SKU now sold by the kilo.
        var row = jdbc.queryForMap(
                "select measure_value, measure_unit from supplier_sku where id = ?", id);
        assertThat(row.get("measure_value")).isNull();
        assertThat(row.get("measure_unit")).isNull();
    }

    @Test
    @DisplayName("an unknown unit is refused rather than stored")
    void unknownUnitIsRefused() throws Exception {
        assertThat(create(sku("FIRKIN", null, null))).isEqualTo(400);
    }
}
