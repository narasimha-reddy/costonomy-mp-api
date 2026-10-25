package com.costonomy.mp.delivery.service;

import java.time.Instant;
import java.util.List;

/**
 * A module that has deliveries which cost courier money (D-204). Each builds the facts of its own deliveries; the payable
 * never reads another module's tables.
 *
 * <p>Deliveries are visited in the order of {@code (updated_at, id)} behind a durable cursor
 * ({@link CourierPayableSyncService}): a delivery that changes moves to the end of that order, so every change is visited,
 * oldest first, and a delivery already visited is not looked at again. That replaces a "newest few within two days" scan,
 * which could starve older eligible deliveries and never reached any older than its window (D-206).
 */
public interface CourierPayableSyncSource {

    /** SUPPLIER_DELIVERY or FOOD_DELIVERY: the key of this source's cursor. */
    String sourceType();

    /** A delivery to visit and when it was last updated. */
    record Candidate(long id, Instant updatedAt, java.sql.Timestamp rawUpdatedAt) {
    }

    /**
     * The next deliveries that can cost money, in {@code (updated_at, id)} order, strictly after the cursor and not newer
     * than the settle lag.
     */
    List<Candidate> candidatesAfter(java.sql.Timestamp afterUpdatedAt, long afterId, int settleLagSeconds, int limit);

    /** Bring one delivery's payables in line, in a transaction of its own. Throws if it cannot. */
    void syncOne(long deliveryId);

    /** Deliveries with a courier booking that reached the provider and no payable for it. The audit's figure. */
    long countEligibleWithoutPayable();

    /** The oldest of those, by delivery id after {@code afterId}: the backfill's deterministic pages. */
    List<Long> idsEligibleWithoutPayable(long afterId, int limit);

    /** How long the oldest of those has been waiting, in seconds, or 0 if none. */
    long oldestEligibleWithoutPayableSeconds();
}
