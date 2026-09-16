package com.costonomy.mp.discovery.service;

import com.costonomy.mp.catalog.domain.CanonicalProduct;
import com.costonomy.mp.catalog.domain.Normalization;
import com.costonomy.mp.catalog.repository.CanonicalProductAliasRepository;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
