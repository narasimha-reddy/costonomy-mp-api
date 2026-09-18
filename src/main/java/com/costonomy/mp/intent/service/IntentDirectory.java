package com.costonomy.mp.intent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The labels an intent needs to be readable, in one query per screen.
 *
 * <p>Read-only and outside the module's entities on purpose. An intent stores a
 * SKU id; a screen needs "Paneer 5 KG pack — Amul" and a picture. Fetching that
 * through the catalog's repositories would be a {@code findById} per line, so a
 * ten-line request would issue eleven queries to render one card. This batches.
 *
 * <p>It reaches across to {@code supplier_sku} and {@code canonical_product}
 * through SQL rather than through the catalog module's repositories, which is the
 * same trade {@code ProcurementDirectory} makes: a projection for display is not
 * a claim on another module's entities, and nothing here writes.
 */
@Component
@RequiredArgsConstructor
public class IntentDirectory {

    private final JdbcTemplate jdbc;

    /**
     * Everything a line needs to render, plus the two facts that decide whether
     * it can still be ordered.
     *
     * <p>No "can this store trade" flag here. That rule is store status plus the
     * organisation's lifecycle plus today's operating hours, and it already lives
     * in {@code ProcurementDirectory.stores}. A second copy would drift, and the
     * copy that drifted would be the one deciding whether an order can be placed.
     *
     * @param skuStatus the SKU's own status — a delisted pack is not orderable
     *                  however good the offer looked when it was added
     */
    public record SkuLabel(
            Long skuId,
            Long canonicalProductId,
            Long supplierStoreId,
            String skuName,
            String productName,
            String packLabel,
            String imageUrl,
            String packUnit,
            BigDecimal packSize,
            String skuStatus) {
    }

    @Transactional(readOnly = true)
    public Map<Long, SkuLabel> skus(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(skuIds.size(), "?"));

        Map<Long, SkuLabel> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.canonical_product_id, s.supplier_store_id, s.name as sku_name,
                       s.pack_size, s.pack_unit, s.status as sku_status,
                       coalesce(s.image_url, p.image_url) as image_url,
                       p.name as product_name
                  from supplier_sku s
                  join canonical_product p on p.id = s.canonical_product_id
                 where s.id in (%s)
                """.formatted(placeholders),
                rs -> {
                    BigDecimal packSize = rs.getBigDecimal("pack_size");
                    String packUnit = rs.getString("pack_unit");
                    result.put(rs.getLong("id"), new SkuLabel(
                            rs.getLong("id"),
                            rs.getLong("canonical_product_id"),
                            rs.getLong("supplier_store_id"),
                            rs.getString("sku_name"),
                            rs.getString("product_name"),
                            packLabel(packSize, packUnit),
                            rs.getString("image_url"),
                            packUnit,
                            packSize,
                            rs.getString("sku_status")));
                },
                skuIds.toArray());
        return result;
    }

    /**
     * "5 KG", not "5.0000 KG".
     *
     * <p>{@code stripTrailingZeros} on a whole number can leave scientific
     * notation — {@code 5.0000} becomes {@code 5E+0} — so the scale is pulled back
     * to zero when nothing is lost by it.
     */
    private static String packLabel(BigDecimal packSize, String packUnit) {
        if (packSize == null) {
            return packUnit == null ? "" : packUnit;
        }
        BigDecimal trimmed = packSize.stripTrailingZeros();
        if (trimmed.scale() < 0) {
            trimmed = trimmed.setScale(0);
        }
        return "%s %s".formatted(trimmed.toPlainString(), packUnit);
    }
}
