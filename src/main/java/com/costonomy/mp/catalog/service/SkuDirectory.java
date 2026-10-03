package com.costonomy.mp.catalog.service;

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
 * How a supplier's pack is described, everywhere it is shown.
 *
 * <p><b>One loader and one shape, deliberately.</b> A SKU appears on the basket,
 * on both sides of a request, on both sides of an order, in search and on the
 * product comparison — and each of those had grown its own idea of which parts
 * to show. So one screen said "Paneer · 1 KG", another "Amul Paneer", and a third
 * just "Paneer", for the same pack. A restaurant comparing two of those screens
 * cannot tell whether they are looking at the same thing.
 *
 * <p>Describing a pack takes a brand from another table, so doing it per line
 * costs a query per line. This batches, and every caller gets the same answer.
 */
@Component
@RequiredArgsConstructor
public class SkuDirectory {

    private final JdbcTemplate jdbc;

    /**
     * Everything needed to render a pack, and nothing that depends on who is
     * looking.
     *
     * <p>The parts are returned rather than a finished string: the client decides
     * how to lay out a secondary line, and a pre-joined label cannot be
     * re-ordered or truncated sensibly on a narrow screen.
     *
     * @param measureValue the contents of the pack where a supplier stated them —
     *                     a 12-pack of 500 ML bottles is {@code packSize} 12,
     *                     {@code packUnit} PACK, {@code measureValue} 500,
     *                     {@code measureUnit} ML. Both are null together or not
     *                     at all, and neither is shown without the other.
     */
    public record SkuDescriptor(
            Long supplierSkuId,
            String productName,
            String skuName,
            String brandName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal measureValue,
            String measureUnit,
            String imageUrl,
            String status) {
    }

    @Transactional(readOnly = true)
    public Map<Long, SkuDescriptor> describe(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        var distinct = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(distinct.size(), "?"));

        Map<Long, SkuDescriptor> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name as sku_name, p.name as product_name, b.name as brand_name,
                       s.pack_size, s.pack_unit, s.measure_value, s.measure_unit,
                       s.status,
                       coalesce(s.image_url, p.image_url) as image_url
                  from supplier_sku s
                  join canonical_product p on p.id = s.canonical_product_id
                  left join brand b on b.id = s.brand_id
                 where s.id in (%s)
                """.formatted(placeholders),
                // A statement block, not an expression: Map.put returns a value,
                // which makes the lambda ambiguous between ResultSetExtractor and
                // RowCallbackHandler.
                rs -> {
                    result.put(rs.getLong("id"), new SkuDescriptor(
                            rs.getLong("id"),
                            rs.getString("product_name"),
                            rs.getString("sku_name"),
                            rs.getString("brand_name"),
                            rs.getBigDecimal("pack_size"),
                            rs.getString("pack_unit"),
                            rs.getBigDecimal("measure_value"),
                            rs.getString("measure_unit"),
                            rs.getString("image_url"),
                            rs.getString("status")));
                },
                distinct.toArray());
        return result;
    }
}
