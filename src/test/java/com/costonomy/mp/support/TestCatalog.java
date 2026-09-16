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

    /**
     * Make a store trade around the clock.
     *
     * <p>Every test that places an order needs this, and the reason is worth
     * stating once rather than ten times: a store's default hours are 10:00 to
     * 21:00, so without it the order suite passes in the afternoon and fails at
     * night. That is the worst kind of red — it arrives on a morning when nobody
     * changed anything, and it points at whichever test happened to run.
     *
     * <p>It is arrangement, in the same class as the {@code lifecycle_status =
     * ACTIVE} these helpers already set: the test is about orders, and it is
     * saying the shop is open.
     */
    public static void tradesAroundTheClock(JdbcTemplate jdbc, long supplierOrganizationId) {
        jdbc.update("""
                update supplier_store
                   set operating_hours_json = ?
                 where supplier_organization_id = ?
                """,
                "{\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\","
                        + "\"SATURDAY\",\"SUNDAY\"],\"opensAt\":\"00:00\",\"closesAt\":\"00:00\"}",
                supplierOrganizationId);
    }
}
