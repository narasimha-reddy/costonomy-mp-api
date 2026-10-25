package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.service.CourierFacts;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.CourierPayableSyncSource;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Builds the courier facts of a food delivery and hands them to the payable (D-204).
 *
 * <p>The restaurant pays the courier's fee as booked (held, then settled down to it), so its contribution is that fee and
 * the platform's share is nothing unless the fee and the cost differ. A supplier funds none of it.
 */
@Component
@Slf4j
public class FoodCourierPayableSync implements CourierPayableSyncSource {

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryAttemptRepository attempts;
    private final CourierPayableService payables;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FoodCourierPayableSync(FoodDeliveryRepository deliveries, FoodDeliveryAttemptRepository attempts,
                                  CourierPayableService payables, JdbcTemplate jdbc,
                                  PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.payables = payables;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Sync after the caller's transaction commits, in a transaction of its own. Never throws into the caller. */
    public void syncSoon(Long foodDeliveryId) {
        com.costonomy.mp.delivery.service.AfterCommit.run("courier payable of food delivery " + foodDeliveryId,
                () -> tx.executeWithoutResult(s -> sync(foodDeliveryId)));
    }

    /** Sync one food delivery in the caller's transaction (it joins). */
    public void sync(Long foodDeliveryId) {
        var d = deliveries.findById(foodDeliveryId).orElse(null);
        if (d != null) {
            payables.sync(facts(d));
        }
    }

    CourierFacts facts(FoodDelivery d) {
        var st = d.getStatus();
        boolean cancelled = st == FoodDeliveryStatus.CANCELLED;
        // FAILED is two things: no courier took it (never picked up), or the courier had the food and could not hand it
        // over. Only the second owes the courier, and only pickedUpAt tells them apart.
        boolean failedAfterPickup = st == FoodDeliveryStatus.FAILED && d.getPickedUpAt() != null;
        boolean pickedUp = st.isInFlight() || st == FoodDeliveryStatus.DELIVERED || st == FoodDeliveryStatus.RETURNED
                || failedAfterPickup;
        var list = attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(d.getId()).stream()
                .map(a -> new CourierFacts.Attempt(a.getId(), a.getProviderCode(), a.getProviderDeliveryId(),
                        a.getOutcome(), a.getQuotedAmount(), a.getEndedCause()))
                .toList();
        BigDecimal fee = d.getFee() == null ? BigDecimal.ZERO : d.getFee();
        return new CourierFacts(CourierPayable.FOOD_DELIVERY, d.getId(), null, d.getOutletId(), null, d.getCurrency(),
                cancelled ? null : d.getProviderDeliveryId(), fee,
                !cancelled && pickedUp, !cancelled && st == FoodDeliveryStatus.DELIVERED,
                !cancelled && (st == FoodDeliveryStatus.RETURNED || failedAfterPickup),
                fee, BigDecimal.ZERO, restaurantRefundedInFull(d), list);
    }

    /**
     * Operations returned the whole fee after a failure at hand-over (D-204): the restaurant then bears none of the
     * courier's cost, so the buyer contribution is zero and the platform carries it. An ordinary pickup returns only the
     * unused part, which is less than what was held, so it is not this.
     */
    private static boolean restaurantRefundedInFull(FoodDelivery d) {
        return d.getMaxFee() != null && d.getMaxFee().signum() > 0
                && d.getFeeRefundedAmount().compareTo(d.getMaxFee()) >= 0
                && d.getStatus() != FoodDeliveryStatus.CANCELLED;
    }

    @Override
    public String sourceType() {
        return CourierPayable.FOOD_DELIVERY;
    }

    @Override
    public List<Candidate> candidatesAfter(Timestamp afterUpdatedAt, long afterId, int settleLagSeconds, int limit) {
        return jdbc.query("""
                select d.id, d.updated_at from food_delivery d
                 where (d.updated_at > ? or (d.updated_at = ? and d.id > ?))
                   and d.updated_at <= utc_timestamp(6) - interval ? second
                   and exists (select 1 from food_delivery_attempt a
                                where a.food_delivery_id = d.id and a.provider_delivery_id is not null)
                 order by d.updated_at, d.id limit ?
                """, (rs, i) -> new Candidate(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(2)),
                afterUpdatedAt, afterUpdatedAt, afterId, settleLagSeconds, limit);
    }

    @Override
    public void syncOne(long deliveryId) {
        tx.executeWithoutResult(s -> sync(deliveryId));
    }

    private static final String MISSING = """
              from food_delivery_attempt a
             where a.provider_delivery_id is not null and a.provider_code is not null
               and not exists (select 1 from courier_payable p
                                where p.source_type = 'FOOD_DELIVERY' and p.delivery_id = a.food_delivery_id
                                  and p.attempt_id = a.id)
            """;

    @Override
    public long countEligibleWithoutPayable() {
        Long n = jdbc.queryForObject("select count(distinct a.food_delivery_id) " + MISSING, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Long> idsEligibleWithoutPayable(long afterId, int limit) {
        return jdbc.queryForList("select a.food_delivery_id " + MISSING + " and a.food_delivery_id > ? "
                + "group by a.food_delivery_id order by a.food_delivery_id limit ?", Long.class, afterId, limit);
    }

    @Override
    public long oldestEligibleWithoutPayableSeconds() {
        Long s = jdbc.queryForObject("select coalesce(max(timestampdiff(second, a.created_at, utc_timestamp(6))), 0) "
                + MISSING, Long.class);
        return s == null ? 0 : s;
    }
}
