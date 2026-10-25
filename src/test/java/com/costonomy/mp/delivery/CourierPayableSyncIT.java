package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.service.CourierPayableProperties;
import com.costonomy.mp.delivery.service.CourierPayableSyncService;
import com.costonomy.mp.delivery.service.CourierPayableSyncSource;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payable sync walks deliveries behind a durable cursor (D-206): more eligible rows than one batch, repeated runs,
 * rows older than any window, synced rows never holding back older eligible ones, a failing row not skipped silently,
 * and the audit and backfill for bookings that have no payable.
 */
@AutoConfigureMockMvc
class CourierPayableSyncIT extends AbstractIntegrationTest {

    private static final String PROVIDER = "SYNC_PROVIDER";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private CourierPayableSyncService syncService;
    @Autowired private SupplierCourierPayableSync supplierSync;
    @Autowired private CourierPayableProperties props;
    @Autowired private MeterRegistry meters;

    private ApiClient api;
    private int originalBatch;
    private int originalMaxBatches;
    private Duration originalLag;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, PROVIDER, "MANUAL");
        originalBatch = props.getSyncBatch();
        originalMaxBatches = props.getSyncMaxBatchesPerPass();
        originalLag = props.getSyncSettleLag();
        props.setSyncSettleLag(Duration.ZERO);
        // Start every test from the beginning of history with the sync's own earlier work out of the way.
        syncService.rewind(CourierPayable.SUPPLIER_DELIVERY);
        syncService.rewind(CourierPayable.FOOD_DELIVERY);
    }

    @AfterEach
    void tearDown() {
        props.setSyncBatch(originalBatch);
        props.setSyncMaxBatchesPerPass(originalMaxBatches);
        props.setSyncSettleLag(originalLag);
    }

    /** A delivery with a courier booking that reached the provider and no payable, last touched at {@code updatedAt}. */
    private long eligible(Instant updatedAt) throws Exception {
        var s = DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, "0.00", "0.00");
        String pdid = "sync-" + UUID.randomUUID();
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, ?, 1, 'BOOKING', 'BOOKED', ?, ?, now(6), now(6))
                """, s.deliveryId(), PROVIDER, pdid, new BigDecimal("50.00"));
        jdbc.update("update delivery set status = 'PROVIDER_SELECTED', provider_code = ?, provider_delivery_id = ?, "
                + "fee = 50, mode = 'COSTONOMY' where id = ?", PROVIDER, pdid, s.deliveryId());
        forget(s.deliveryId());
        touch(s.deliveryId(), updatedAt);
        return s.deliveryId();
    }

    private void forget(long deliveryId) {
        jdbc.update("delete e from courier_payable_event e join courier_payable p on p.id = e.payable_id "
                + "where p.source_type = 'SUPPLIER_DELIVERY' and p.delivery_id = ?", deliveryId);
        jdbc.update("delete a from courier_payment_attempt a join courier_payable p on p.id = a.payable_id "
                + "where p.source_type = 'SUPPLIER_DELIVERY' and p.delivery_id = ?", deliveryId);
        jdbc.update("delete from courier_payable where source_type = 'SUPPLIER_DELIVERY' and delivery_id = ?", deliveryId);
    }

    /** Set the row's last-updated time explicitly (naming the column stops the automatic update). */
    private void touch(long deliveryId, Instant updatedAt) {
        jdbc.update("update delivery set updated_at = ? where id = ?",
                Timestamp.from(updatedAt), deliveryId);
    }

    /** Put the cursor just before a region of time, as if everything before it had been visited. */
    private void cursorTo(Instant just) {
        jdbc.update("update courier_payable_sync_cursor set last_updated_at = ?, last_id = 0 where source_type = ?",
                Timestamp.from(just), CourierPayable.SUPPLIER_DELIVERY);
    }

    private static Instant region(int year) {
        return Instant.parse(year + "-03-01T00:00:00Z");
    }

    private boolean hasPayable(long deliveryId) {
        return jdbc.queryForObject("select count(*) from courier_payable where source_type = 'SUPPLIER_DELIVERY' "
                + "and delivery_id = ?", Integer.class, deliveryId) > 0;
    }

    private int payables(long deliveryId) {
        return jdbc.queryForObject("select count(*) from courier_payable where source_type = 'SUPPLIER_DELIVERY' "
                + "and delivery_id = ?", Integer.class, deliveryId);
    }

    @Test
    @DisplayName("more eligible deliveries than one batch are all reached, oldest first, over repeated runs, none twice")
    void moreEligibleThanOneBatch() throws Exception {
        props.setSyncBatch(2);
        props.setSyncMaxBatchesPerPass(1);
        // Ours are the oldest rows after the cursor, so they are the first batches whatever else is in the database.
        Instant base = region(2001);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(eligible(base.plusSeconds(i * 60L)));
        }
        cursorTo(base.minusSeconds(60));

        var first = syncService.run();
        assertThat(first.visited()).as("one batch of two").isEqualTo(2);
        assertThat(ids.stream().filter(this::hasPayable)).as("the two oldest").containsExactly(ids.get(0), ids.get(1));

        syncService.run();
        syncService.run();
        assertThat(ids).allMatch(this::hasPayable);
        assertThat(ids).allSatisfy(id -> assertThat(payables(id)).as("one payable each, none doubled").isEqualTo(1));

        drain();
        assertThat(syncService.run().visited()).as("nothing left to visit").isZero();
        assertThat(syncService.run().visited()).as("and a repeat run changes nothing").isZero();
        assertThat(ids).allSatisfy(id -> assertThat(payables(id)).isEqualTo(1));
    }

    @Test
    @DisplayName("rows already synced do not hold back an older eligible row")
    void syncedRowsDoNotStarveOlderOnes() throws Exception {
        props.setSyncBatch(2);
        props.setSyncMaxBatchesPerPass(1);
        Instant base = region(2002);
        long oldest = eligible(base);
        for (int i = 1; i <= 3; i++) {
            long id = eligible(base.plusSeconds(i * 60L));
            supplierSync.sync(id);              // already in line
            touch(id, base.plusSeconds(i * 60L));
        }
        cursorTo(base.minusSeconds(60));

        syncService.run();

        assertThat(hasPayable(oldest)).as("the oldest is reached in the first batch, not starved by newer synced rows").isTrue();
    }

    @Test
    @DisplayName("a delivery older than any lookback is still reached")
    void rowsOlderThanTheOldLookbackAreReached() throws Exception {
        drain();
        long ancient = eligible(Instant.now().minus(Duration.ofDays(60)));
        // Re-walk from the start: the first pass over a deployment's history reaches the oldest rows too.
        syncService.rewind(CourierPayable.SUPPLIER_DELIVERY);
        props.setSyncBatch(500);
        props.setSyncMaxBatchesPerPass(20);

        syncService.run();

        assertThat(hasPayable(ancient)).isTrue();
    }

    @Test
    @DisplayName("a delivery that changes after the cursor passed it is visited again, once")
    void aLaterChangeIsVisitedAgain() throws Exception {
        long id = eligible(region(2004));
        cursorTo(region(2004).minusSeconds(60));
        drain();
        assertThat(hasPayable(id)).isTrue();
        assertThat(syncService.run().visited()).isZero();

        jdbc.update("update delivery set status = 'DRIVER_ASSIGNED' where id = ?", id);       // updated_at moves on

        assertThat(syncService.run().visited()).as("visited because it changed").isEqualTo(1);
        assertThat(syncService.run().visited()).isZero();
        assertThat(payables(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing delivery is retried, then skipped loudly after three passes, and never blocks the ones behind it")
    void aFailingRowIsNotSkippedSilently() {
        var source = new FailingSource();
        var service = new CourierPayableSyncService(List.of(source), jdbc, props,
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        service.rewind("TEST_SOURCE");
        props.setSyncBatch(10);

        var one = service.run();
        assertThat(source.synced).as("the one before the failure was done, then it stopped").containsExactly(1L);
        assertThat(one.failed()).isEqualTo(1);
        service.run();
        assertThat(source.attempts.get(2L)).isEqualTo(2);
        assertThat(service.health().skipped()).isEmpty();

        var third = service.run();
        assertThat(third.skipped()).isEqualTo(1);
        assertThat(service.health().skipped()).containsExactly("TEST_SOURCE:2");
        assertThat(source.synced).as("and the one behind it was reached").contains(1L, 3L);
        assertThat(service.run().visited()).isZero();

        service.rewind("TEST_SOURCE");
        assertThat(service.health().skipped()).as("a rewind clears the list").isEmpty();
    }

    @Test
    @DisplayName("the audit counts bookings with no payable and exposes it as a gauge; backfill repairs them page by page")
    void auditAndBackfill() throws Exception {
        long a = eligible(Instant.now().minus(Duration.ofHours(5)));
        long b = eligible(Instant.now().minus(Duration.ofHours(4)));
        long c = eligible(Instant.now().minus(Duration.ofHours(3)));

        var health = syncService.audit();

        assertThat(health.missingPayables()).isGreaterThanOrEqualTo(3);
        assertThat(health.oldestMissingSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("courier.payable.sync.missing").tag("source", CourierPayable.SUPPLIER_DELIVERY).gauge().value())
                .isGreaterThanOrEqualTo(3.0);

        long after = 0;
        long previous = -1;
        int pages = 0;
        CourierPayableSyncService.Backfill page;
        do {
            page = syncService.backfill(CourierPayable.SUPPLIER_DELIVERY, after, 2);
            assertThat(page.nextAfterId()).as("pages advance deterministically").isGreaterThanOrEqualTo(previous);
            previous = page.nextAfterId();
            after = page.nextAfterId();
            pages++;
        } while (page.remaining() > 0 && pages < 200);

        assertThat(List.of(a, b, c)).allMatch(this::hasPayable);
        assertThat(syncService.audit().missingPayables()).isZero();
        assertThat(syncService.backfill(CourierPayable.SUPPLIER_DELIVERY, 0, 10).processed()).as("repeating is harmless").isZero();
    }

    @Test
    @DisplayName("backfill and rewind need DELIVERY_FINANCE_OPERATE; support can read the health but not repair")
    void backfillIsGuarded() throws Exception {
        long id = eligible(Instant.now().minus(Duration.ofHours(1)));
        String finance = operator("OPS_FINANCE");
        String support = operator("OPS_SUPPORT");
        String base = "/api/v1/admin/delivery-finance";

        assertThat(api.postStatus(support, base + "/payables/backfill",
                Map.of("sourceType", "SUPPLIER_DELIVERY", "note", "x"))).isEqualTo(403);
        assertThat(api.postStatus(support, base + "/payables/sync-cursor/rewind",
                Map.of("sourceType", "SUPPLIER_DELIVERY", "note", "x"))).isEqualTo(403);
        assertThat(api.postStatus(finance, base + "/payables/backfill",
                Map.of("sourceType", "NOT_A_SOURCE", "note", "x"))).isEqualTo(400);

        var res = api.post(finance, base + "/payables/backfill",
                Map.of("sourceType", "SUPPLIER_DELIVERY", "limit", 500, "note", "repairing"));
        assertThat(res.at("/data/processed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(hasPayable(id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'COURIER_PAYABLE_BACKFILL'",
                Integer.class)).isGreaterThanOrEqualTo(1);

        var queues = api.get(support, base + "/queues").at("/data/totals");
        assertThat(queues.has("bookingsWithoutPayable")).isTrue();
        assertThat(queues.has("payableSyncLagSeconds")).isTrue();
    }

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status, granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0 from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    /** Run passes until the cursor has caught up with everything already in the database. */
    private void drain() {
        int guard = 0;
        props.setSyncBatch(500);
        props.setSyncMaxBatchesPerPass(50);
        while (syncService.run().visited() > 0 && guard++ < 20) {
            // keep going
        }
        props.setSyncBatch(originalBatch);
    }

    /** Three deliveries; the second never syncs. */
    private static final class FailingSource implements CourierPayableSyncSource {
        final List<Long> synced = new ArrayList<>();
        final Map<Long, Integer> attempts = new java.util.HashMap<>();
        private final Timestamp t1 = Timestamp.from(Instant.parse("2020-01-01T00:00:01Z"));
        private final Timestamp t2 = Timestamp.from(Instant.parse("2020-01-01T00:00:02Z"));
        private final Timestamp t3 = Timestamp.from(Instant.parse("2020-01-01T00:00:03Z"));

        @Override
        public String sourceType() {
            return "TEST_SOURCE";
        }

        @Override
        public List<Candidate> candidatesAfter(Timestamp afterUpdatedAt, long afterId, int settleLagSeconds, int limit) {
            var all = List.of(new Candidate(1, t1.toInstant(), t1), new Candidate(2, t2.toInstant(), t2),
                    new Candidate(3, t3.toInstant(), t3));
            return all.stream().filter(c -> c.rawUpdatedAt().after(afterUpdatedAt)
                    || (c.rawUpdatedAt().equals(afterUpdatedAt) && c.id() > afterId)).limit(limit).toList();
        }

        @Override
        public void syncOne(long deliveryId) {
            attempts.merge(deliveryId, 1, Integer::sum);
            if (deliveryId == 2) {
                throw new IllegalStateException("cannot sync 2");
            }
            synced.add(deliveryId);
        }

        @Override
        public long countEligibleWithoutPayable() {
            return 0;
        }

        @Override
        public List<Long> idsEligibleWithoutPayable(long afterId, int limit) {
            return List.of();
        }

        @Override
        public long oldestEligibleWithoutPayableSeconds() {
            return 0;
        }
    }
}
