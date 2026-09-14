package com.costonomy.mp.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates canonical products that belong to one test only.
 *
 * <p>Integration tests share a database, and the V7 seed catalog is shared by all
 * of them. A test that stocks "Paneer" and then asserts it sees two offers is
 * really asserting that <em>no other test anywhere</em> stocked paneer — which is
 * true until someone adds one, at which point an unrelated test fails and the
 * failure points at the wrong place.
 *
 * <p>So a test that cares about exactly which offers come back creates its own
 * product. Tests about the seeded catalog itself — search, aliases, categories —
 * legitimately use the seeded rows.
 *
 * <p>Inserting directly is correct here rather than a shortcut: canonical products
 * are platform-owned and there is deliberately no API for a supplier to create
 * one (doc 01 §7).
 */
public final class TestCatalog {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestCatalog() {
    }

    /** A canonical product no other test will touch. */
    public static long freshProduct(JdbcTemplate jdbc, String label) {
        String name = "%s Test Product %d".formatted(label, SEQUENCE.incrementAndGet());
        String normalized = name.toLowerCase();

        jdbc.update("""
                insert into canonical_product
                    (name, normalized_name, base_unit, base_pack_size, status,
                     created_at, updated_at, version)
                values (?, ?, 'KG', 1, 'ACTIVE', now(6), now(6), 0)
                """, name, normalized);

        return jdbc.queryForObject(
                "select id from canonical_product where normalized_name = ?", Long.class, normalized);
    }
}
