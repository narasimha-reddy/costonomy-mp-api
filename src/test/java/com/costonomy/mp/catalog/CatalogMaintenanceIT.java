package com.costonomy.mp.catalog;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bulk edits of a supplier's catalog must change only what they say (D-146). The rate sheet and the item-variants
 * screen used to reset stock, status and GST for rows that did not mention them, and a supplier could undo an admin's
 * disabling with a status change.
 */
@AutoConfigureMockMvc
@DisplayName("catalog maintenance")
class CatalogMaintenanceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Seller(String token, long storeId, long productId) {
    }

    private Seller newSeller() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Metro Pvt Ltd", "displayName", "Metro",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Metro store", "addressLine1", "Road No 36", "city", "Hyderabad",
                        "state", "Telangana", "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        long storeId = created.get("stores").get(0).get("id").asLong();
        return new Seller(token, storeId, TestCatalog.freshProduct(jdbc, "paneer"));
    }

    private long listSku(Seller seller, String gst, String availability) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of(
                "canonicalProductId", seller.productId(), "skuCode", "PNR-" + System.nanoTime(),
                "name", "Paneer", "packSize", 1, "packUnit", "KG",
                "sellingPrice", "400", "gstRate", gst));
        body.put("availability", availability);
        var created = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus", body);
        assertThat(created.at("/error").isMissingNode() || created.at("/error").isNull())
                .as(created.toString()).isTrue();
        return created.at("/data/id").asLong();
    }

    private String liveAvailability(long skuId) {
        return jdbc.queryForObject("select availability from supplier_offer where supplier_sku_id = ? "
                + "and status = 'ACTIVE'", String.class, skuId);
    }

    private BigDecimal liveGst(long skuId) {
        return jdbc.queryForObject("select gst_rate from supplier_offer where supplier_sku_id = ? "
                + "and status = 'ACTIVE'", BigDecimal.class, skuId);
    }

    private BigDecimal livePrice(long skuId) {
        return jdbc.queryForObject("select selling_price from supplier_offer where supplier_sku_id = ? "
                + "and status = 'ACTIVE'", BigDecimal.class, skuId);
    }

    private String skuStatus(long skuId) {
        return jdbc.queryForObject("select status from supplier_sku where id = ?", String.class, skuId);
    }

    @Test
    @DisplayName("a rate-sheet row with no availability leaves an out-of-stock SKU out of stock")
    void rateSheetKeepsStock() throws Exception {
        var seller = newSeller();
        long sku = listSku(seller, "5", "OUT_OF_STOCK");

        var response = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/rate-sheet",
                Map.of("rows", List.of(Map.of("skuId", sku, "sellingPrice", "420"))));

        assertThat(response.at("/error").isMissingNode() || response.at("/error").isNull())
                .as(response.toString()).isTrue();
        assertThat(livePrice(sku)).isEqualByComparingTo("420");
        assertThat(liveAvailability(sku)).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    @DisplayName("a rate-sheet row that names availability still changes it")
    void rateSheetCanChangeStock() throws Exception {
        var seller = newSeller();
        long sku = listSku(seller, "5", "OUT_OF_STOCK");

        api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/rate-sheet",
                Map.of("rows", List.of(Map.of("skuId", sku, "sellingPrice", "420", "availability", "AVAILABLE"))));

        assertThat(liveAvailability(sku)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("an item-variants update changes only the price: a delisted SKU stays delisted and GST and stock stay")
    void variantUpdateChangesOnlyWhatItSays() throws Exception {
        var seller = newSeller();
        long sku = listSku(seller, "12", "OUT_OF_STOCK");
        assertThat(api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + sku, Map.of("status", "INACTIVE")))
                .isEqualTo(200);

        var response = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/products/"
                        + seller.productId() + "/variants/batch",
                Map.of("canonicalProductId", seller.productId(),
                        "variants", List.of(Map.of("skuId", sku, "sellingPrice", "430"))));

        assertThat(response.at("/error").isMissingNode() || response.at("/error").isNull())
                .as(response.toString()).isTrue();
        assertThat(livePrice(sku)).isEqualByComparingTo("430");
        assertThat(skuStatus(sku)).as("still delisted").isEqualTo("INACTIVE");
        assertThat(liveGst(sku)).as("GST not reset to 5").isEqualByComparingTo("12");
        assertThat(liveAvailability(sku)).as("stock not reset").isEqualTo("OUT_OF_STOCK");
    }

    @Test
    @DisplayName("a supplier cannot list again a SKU that Costonomy disabled")
    void disabledStaysDisabled() throws Exception {
        var seller = newSeller();
        long sku = listSku(seller, "5", "AVAILABLE");
        jdbc.update("update supplier_sku set status = 'DISABLED' where id = ?", sku);

        var attempt = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/supplier-skus/" + sku)
                        .header("Authorization", "Bearer " + seller.token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andReturn().getResponse().getContentAsString());

        assertThat(attempt.at("/error/code").asText()).as(attempt.toString()).isEqualTo("VALIDATION_ERROR");
        assertThat(skuStatus(sku)).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("a supplier can still delist and relist their own SKU")
    void delistAndRelistStillWork() throws Exception {
        var seller = newSeller();
        long sku = listSku(seller, "5", "AVAILABLE");

        assertThat(api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + sku, Map.of("status", "INACTIVE")))
                .isEqualTo(200);
        assertThat(skuStatus(sku)).isEqualTo("INACTIVE");
        assertThat(api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + sku, Map.of("status", "ACTIVE")))
                .isEqualTo(200);
        assertThat(skuStatus(sku)).isEqualTo("ACTIVE");
    }
}
