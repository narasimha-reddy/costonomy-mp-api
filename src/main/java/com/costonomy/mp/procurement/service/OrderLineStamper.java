package com.costonomy.mp.procurement.service;

import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one place that copies a SKU's handling onto the lines of a new order (D-134): cold chain, catch-weight and the
 * HSN code, and from the lines the order's own cold-chain flag. Every path that creates order lines calls it before
 * saving them, so no path can forget a flag or compute it differently.
 *
 * <p>The flags are snapshots: a supplier changing a declaration later does not change an order already placed. The
 * order's flag is recomputed from its lines each time, never built up line by line, so it can only be true when a
 * line is.
 */
@Component
@RequiredArgsConstructor
public class OrderLineStamper {

    private record SkuFacts(boolean coldChain, boolean catchWeight, String hsnCode) {
    }

    private final JdbcTemplate jdbc;

    /** Stamps every line and sets the order's cold-chain flag; the caller saves both. */
    public void stamp(SupplierOrder order, List<SupplierOrderItem> lines) {
        var ids = lines.stream().map(SupplierOrderItem::getSupplierSkuId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<Long, SkuFacts> facts = new HashMap<>();
        if (!ids.isEmpty()) {
            String in = ids.stream().map(id -> "?").collect(Collectors.joining(","));
            jdbc.query("""
                    select s.id, s.requires_cold_chain, s.is_catch_weight,
                           coalesce(nullif(trim(s.hsn_code), ''), nullif(trim(p.hsn_code), ''))
                      from supplier_sku s left join canonical_product p on p.id = s.canonical_product_id
                     where s.id in (%s)
                    """.formatted(in),
                    rs -> {
                        facts.put(rs.getLong(1), new SkuFacts(rs.getBoolean(2), rs.getBoolean(3), rs.getString(4)));
                    },
                    ids.toArray());
        }

        boolean anyColdChain = false;
        for (var line : lines) {
            var sku = facts.get(line.getSupplierSkuId());
            boolean coldChain = sku != null && sku.coldChain();
            line.setRequiresColdChain(coldChain);
            line.setCatchWeight(sku != null && sku.catchWeight());
            line.setHsnCode(sku == null ? null : sku.hsnCode());
            anyColdChain |= coldChain;
        }
        order.setHasColdChainItems(anyColdChain);
    }
}
