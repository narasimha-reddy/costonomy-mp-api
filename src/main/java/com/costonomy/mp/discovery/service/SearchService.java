package com.costonomy.mp.discovery.service;

import com.costonomy.mp.catalog.domain.CanonicalProduct;
import com.costonomy.mp.catalog.domain.Normalization;
import com.costonomy.mp.catalog.repository.CanonicalProductAliasRepository;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.discovery.domain.Serviceability;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search suggestions and supplier search. Doc 04 §8, doc 07 §9.
 *
 * <p>Suggestions cover the typing state §23A.10 describes. They return canonical
 * products and categories, matched on name and configured alias — the same
 * vocabulary the catalog search uses, so a suggestion always leads somewhere.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_SUGGESTIONS = 10;

    private final CanonicalProductRepository products;
    private final CanonicalProductAliasRepository aliases;
    private final DiscoveryDirectory directory;
    private final JdbcTemplate jdbc;

    /**
     * Type-ahead suggestions.
     *
     * <p>Matched term first, so the client can show what it matched on: typing
     * "dah" surfaces "Dahi" rather than "Curd", which is what the restaurant
     * expects to see while typing even though the product is called Curd.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryDtos.SuggestionResponse> suggest(String query) {
        String normalized = Normalization.normalize(query);
        if (normalized.length() < 2) {
            // One character matches almost everything and is never a useful
            // suggestion list.
            return List.of();
        }

        // LinkedHashMap keyed on the displayed term: a product matching on both
        // its name and an alias should appear once.
        Map<String, DiscoveryDtos.SuggestionResponse> suggestions = new LinkedHashMap<>();

        for (CanonicalProduct product : products.searchByPrefix(
                normalized, PageRequest.of(0, MAX_SUGGESTIONS))) {
            suggestions.putIfAbsent(product.getName(), new DiscoveryDtos.SuggestionResponse(
                    product.getName(), "PRODUCT", product.getId(), null));
        }

        aliases.findAll().stream()
                .filter(alias -> alias.getNormalizedAlias().startsWith(normalized))
                .limit(MAX_SUGGESTIONS)
                .forEach(alias -> suggestions.putIfAbsent(alias.getAlias(),
                        new DiscoveryDtos.SuggestionResponse(
                                alias.getAlias(), "ALIAS", alias.getCanonicalProductId(), null)));

        jdbc.query("""
                select name from product_category
                 where status = 'ACTIVE' and lower(name) like concat(?, '%')
                 order by display_order limit 5
                """,
                rs -> {
                    String name = rs.getString(1);
                    suggestions.putIfAbsent(name, new DiscoveryDtos.SuggestionResponse(
                            name, "CATEGORY", null, name));
                },
                normalized);

        return suggestions.values().stream().limit(MAX_SUGGESTIONS).toList();
    }

    /**
     * Supplier search. Doc 04 §8.
     *
     * <p>Secondary to product search by design — doc 01 §25: a restaurant looks for
     * paneer, not for a paneer supplier. This exists for the case where they
     * already know who they want to buy from.
     *
     * <p>Only tradeable stores are returned. When an outlet is given, each result
     * carries the distance and whether that store actually delivers there, so the
     * client never offers a supplier that would fail at checkout.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryDtos.SupplierSearchResult> searchSuppliers(String query, Long outletId) {
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.length() < 2) {
            return List.of();
        }

        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        List<DiscoveryDtos.SupplierSearchResult> results = new ArrayList<>();
        jdbc.query("""
                select s.id, o.display_name, s.name, s.city, s.latitude, s.longitude,
                       (select count(*) from supplier_sku k
                         where k.supplier_store_id = s.id and k.status = 'ACTIVE') as product_count
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.status = 'ACTIVE'
                   and o.lifecycle_status = 'ACTIVE'
                   and (lower(o.display_name) like concat('%', ?, '%')
                        or lower(s.name) like concat('%', ?, '%'))
                 order by o.display_name
                 limit 25
                """,
                rs -> {
                    BigDecimal latitude = rs.getBigDecimal(5);
                    BigDecimal longitude = rs.getBigDecimal(6);
                    Double distance = outlet == null ? null : Serviceability.distanceKm(
                            outlet.latitude(), outlet.longitude(), latitude, longitude);

                    results.add(new DiscoveryDtos.SupplierSearchResult(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            Serviceability.round(distance),
                            // Without an outlet there is nothing to be serviceable
                            // *to*, so the flag is true rather than a false claim.
                            outlet == null || distance == null || distance <= 25.0,
                            rs.getInt(7)));
                },
                term, term);

        return results;
    }
}
