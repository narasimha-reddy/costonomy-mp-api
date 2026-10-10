package com.costonomy.mp.wallet.invoice.costapi;

import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads the cost app's supplier and SKU lists (D-114), the same way its own upload screen does:
 * {@link CostApiSession.Call#SUPPLIER_LIST} and {@link CostApiSession.Call#SKU_LIST}, each with
 * {@code ?outlet=<the mapped cost outlet>&userId=<configured cost user>&status=false}. Only those two GETs can be
 * expressed: the session builds the request from its fixed list of calls (D-115). The caller's marketplace outlet is
 * never sent; the cost outlet is the one it is mapped to. Only id, name (and for SKUs unit, price per unit and
 * category) are kept.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.reader.provider", havingValue = "HTTP")
@Slf4j
public class HttpCostCatalog implements CostCatalog {

    static final int MAX_ROWS = 5000;

    private final CostApiSession session;
    private final String userId;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public HttpCostCatalog(CostApiSession session,
                           @Value("${costonomy.mp.invoices.reader.user-id:0}") String userId,
                           @Value("${costonomy.mp.invoices.lookups.timeout:PT15S}") Duration timeout) {
        this.session = session;
        this.userId = userId;
        this.timeout = timeout;
    }

    @Override
    public List<SupplierOption> suppliers(long costOutletId) {
        var out = new ArrayList<SupplierOption>();
        for (JsonNode row : rows(CostApiSession.Call.SUPPLIER_LIST, costOutletId)) {
            Long id = id(row);
            String name = text(row, "supplierName", InvoiceReading.MAX_NAME);
            if (id != null && name != null && !disabled(row)) {
                out.add(new SupplierOption(id, name));
            }
        }
        return out;
    }

    @Override
    public List<SkuOption> skus(long costOutletId) {
        var out = new ArrayList<SkuOption>();
        for (JsonNode row : rows(CostApiSession.Call.SKU_LIST, costOutletId)) {
            Long id = id(row);
            String name = text(row, "skuName", InvoiceReading.MAX_NAME);
            if (id != null && name != null && !disabled(row)) {
                BigDecimal price = number(row, "itemPrice");
                out.add(new SkuOption(id, name, text(row, "unit", InvoiceReading.MAX_UNIT),
                        InvoiceReading.price(price != null ? price : number(row, "initialItemPrice")),
                        text(row, "categoryName", InvoiceReading.MAX_CATEGORY)));
            }
        }
        return out;
    }

    /** One of the two list GETs for the cost outlet; the rows of its JSON array (or of its {@code list}). */
    List<JsonNode> rows(CostApiSession.Call call, long costOutletId) {
        var query = new LinkedHashMap<String, String>();
        query.put("outlet", Long.toString(costOutletId));
        query.put("userId", userId);
        query.put("status", "false");
        try {
            var response = session.call(call, query, null, timeout);
            if (response.statusCode() / 100 != 2) {
                throw new CostApiUnavailableException("The cost app's list answered with status "
                        + response.statusCode() + ".");
            }
            JsonNode root = json.readTree(response.body());
            JsonNode array = root != null && root.isObject() ? root.get("list") : root;
            var rows = new ArrayList<JsonNode>();
            if (array != null && array.isArray()) {
                for (JsonNode row : array) {
                    if (rows.size() >= MAX_ROWS) {
                        break;
                    }
                    if (row != null && row.isObject()) {
                        rows.add(row);
                    }
                }
            }
            return rows;
        } catch (IOException e) {
            throw new CostApiUnavailableException("The cost app's list could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CostApiUnavailableException("Reading the cost app's list was interrupted.");
        }
    }

    private static Long id(JsonNode row) {
        JsonNode v = row.get("id");
        return v != null && v.canConvertToLong() && v.asLong() > 0 ? v.asLong() : null;
    }

    private static boolean disabled(JsonNode row) {
        JsonNode v = row.get("isDisabled");
        return v != null && v.asBoolean(false);
    }

    private static String text(JsonNode row, String field, int max) {
        JsonNode v = row.get(field);
        if (v == null || !v.isValueNode() || v.isNull()) {
            return null;
        }
        String t = v.asText().strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static BigDecimal number(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            return v.isTextual() ? new BigDecimal(v.asText().strip()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
