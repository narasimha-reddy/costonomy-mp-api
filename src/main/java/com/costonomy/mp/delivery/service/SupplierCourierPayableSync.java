package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
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

/** Builds the courier facts of a supplier delivery and hands them to the payable (D-204). */
@Component
@Slf4j
public class SupplierCourierPayableSync implements CourierPayableSyncSource {

    private final DeliveryRepository deliveries;
    private final DeliveryProviderAttemptRepository attempts;
    private final CourierPayableService payables;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SupplierCourierPayableSync(DeliveryRepository deliveries, DeliveryProviderAttemptRepository attempts,
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
    public void syncSoon(Long deliveryId) {
        AfterCommit.run("courier payable of delivery " + deliveryId, () -> tx.executeWithoutResult(s -> sync(deliveryId)));
    }

    /** Sync one delivery in the caller's transaction (it joins). */
    public void sync(Long deliveryId) {
        var d = deliveries.findById(deliveryId).orElse(null);
        if (d != null) {
            payables.sync(facts(d));
        }
    }

    CourierFacts facts(Delivery d) {
        var order = d.getSupplierOrderId() == null ? null : jdbc.query(
                "select delivery_fee, delivery_funded_amount, status from supplier_order where id = ?",
                (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)}, d.getSupplierOrderId())
                .stream().findFirst().orElse(null);
        boolean refunded = order != null && "CANCELLED".equals(order[2]);

        boolean ours = d.getMode() == DeliveryMode.COSTONOMY && d.getStatus() != DeliveryStatus.CANCELLED;
        var st = d.getStatus();
        boolean pickedUp = st.isInFlight() || st == DeliveryStatus.DELIVERED
                || (st == DeliveryStatus.DELIVERY_FAILED && d.getPickedUpAt() != null);

        var list = attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId()).stream()
                .map(a -> new CourierFacts.Attempt(a.getId(), a.getProviderCode(), a.getProviderDeliveryId(),
                        a.getOutcome(), a.getQuotedAmount(), a.getEndedCause()))
                .toList();
        return new CourierFacts(CourierPayable.SUPPLIER_DELIVERY, d.getId(), d.getSupplierOrderId(), d.getOutletId(),
                d.getSupplierStoreId(), d.getCurrency(),
                ours ? d.getProviderDeliveryId() : null, d.getFee(),
                ours && pickedUp, ours && st == DeliveryStatus.DELIVERED,
                ours && st == DeliveryStatus.DELIVERY_FAILED && pickedUp,
                order == null ? BigDecimal.ZERO : (BigDecimal) order[0],
                order == null ? BigDecimal.ZERO : (BigDecimal) order[1], refunded, list);
    }

    @Override
    public String sourceType() {
        return CourierPayable.SUPPLIER_DELIVERY;
    }

    @Override
    public List<Candidate> candidatesAfter(Timestamp afterUpdatedAt, long afterId, int settleLagSeconds, int limit) {
        return jdbc.query("""
                select d.id, d.updated_at from delivery d
                 where (d.updated_at > ? or (d.updated_at = ? and d.id > ?))
                   and d.updated_at <= utc_timestamp(6) - interval ? second
                   and exists (select 1 from delivery_provider_attempt a
                                where a.delivery_id = d.id and a.provider_delivery_id is not null)
                 order by d.updated_at, d.id limit ?
                """, (rs, i) -> new Candidate(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(2)),
                afterUpdatedAt, afterUpdatedAt, afterId, settleLagSeconds, limit);
    }

    @Override
    public void syncOne(long deliveryId) {
        tx.executeWithoutResult(s -> sync(deliveryId));
    }

    private static final String MISSING = """
              from delivery_provider_attempt a
             where a.provider_delivery_id is not null and a.provider_code is not null
               and not exists (select 1 from courier_payable p
                                where p.source_type = 'SUPPLIER_DELIVERY' and p.delivery_id = a.delivery_id
                                  and p.attempt_id = a.id)
            """;

    @Override
    public long countEligibleWithoutPayable() {
        Long n = jdbc.queryForObject("select count(distinct a.delivery_id) " + MISSING, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Long> idsEligibleWithoutPayable(long afterId, int limit) {
        return jdbc.queryForList("select a.delivery_id " + MISSING + " and a.delivery_id > ? "
                + "group by a.delivery_id order by a.delivery_id limit ?", Long.class, afterId, limit);
    }

    @Override
    public long oldestEligibleWithoutPayableSeconds() {
        Long s = jdbc.queryForObject("select coalesce(max(timestampdiff(second, a.created_at, utc_timestamp(6))), 0) "
                + MISSING, Long.class);
        return s == null ? 0 : s;
    }
}
