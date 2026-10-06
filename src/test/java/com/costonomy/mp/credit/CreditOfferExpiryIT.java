package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.credit.service.CreditLifecycleService;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * An offer the restaurant never accepts lapses after 14 India days (B9b, TM15, SU13, D-137). The exact day boundary is
 * in {@code CreditOfferExpiryTest}; here the job, the races, the notices and the way back.
 */
@AutoConfigureMockMvc
class CreditOfferExpiryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private CreditLifecycleService lifecycleSpy;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditLifecycleSupport l;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        l = new CreditLifecycleSupport(e, jdbc, relay);
        pool = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** An APPROVED offer on different terms, waiting for the restaurant. */
    private Line offer() throws Exception {
        var line = s.creditLine("200000", Map.of("approvedLimit", "100000"));
        assertThat(l.status(line.agreementId())).isEqualTo("APPROVED");
        return line;
    }

    /** The offer was made {@code days} days ago (whole days, so a boundary hour never matters). */
    private void offeredDaysAgo(Line line, int days) {
        jdbc.update("update credit_agreement set offer_made_at = date_sub(now(6), interval ? day) where id = ?",
                days, line.agreementId());
    }

    private Reply accept(Line line) throws Exception {
        return e.call("POST", line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept",
                null, Map.of());
    }

    @Test
    @DisplayName("approving on modified terms stamps when the offer was made; approving as asked does not")
    void offerIsStamped() throws Exception {
        var offer = offer();
        assertThat(l.column(offer.agreementId(), "offer_made_at")).isNotNull();
        var seen = e.call("GET", offer.seller().token(), "/api/v1/credit/agreements/" + offer.agreementId(), null, null);
        assertThat(seen.data().at("/offerMadeAt").asText()).isNotBlank();
        assertThat(seen.data().at("/offerExpiresOn").asText()).matches("\\d{4}-\\d{2}-\\d{2}");

        var asked = s.creditLine("200000");
        assertThat(l.status(asked.agreementId())).isEqualTo("ACTIVE");
        var activeView = e.call("GET", asked.seller().token(), "/api/v1/credit/agreements/" + asked.agreementId(),
                null, null);
        assertThat(activeView.data().at("/offerExpiresOn").isNull()).isTrue();
    }

    @Test
    @DisplayName("SU13/TM15: an offer 15 days old expires; 12 days old does not; the job is idempotent")
    void expiresAfterFourteenDays() throws Exception {
        var old = offer();
        var fresh = offer();
        offeredDaysAgo(old, 15);
        offeredDaysAgo(fresh, 12);

        creditJobs.expireOffers();
        creditJobs.expireOffers();

        assertThat(l.status(old.agreementId())).isEqualTo("EXPIRED");
        assertThat(l.status(fresh.agreementId())).isEqualTo("APPROVED");
        assertThat(l.audits("CREDIT_OFFER_EXPIRED", old.agreementId())).isEqualTo(1);
        assertThat(l.events("CreditOfferExpired", old.agreementId())).describedAs("one notice, not one per run")
                .isEqualTo(1);
        assertThat(l.events("CreditOfferExpired", fresh.agreementId())).isZero();
        var seen = e.call("GET", old.buyer().token(), "/api/v1/credit/agreements/" + old.agreementId(), null, null);
        assertThat(seen.data().at("/status").asText()).isEqualTo("EXPIRED");
        assertThat(seen.data().at("/canFund").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("only an unaccepted offer expires: a REQUESTED request, an ACTIVE line and a SUSPENDED line never do, however old")
    void onlyApprovedOffersExpire() throws Exception {
        var active = s.creditLine("200000");
        var suspended = s.creditLine("200000");
        assertThat(e.suspend(suspended, "Review").status()).isEqualTo(200);
        jdbc.update("update credit_agreement set offer_made_at = date_sub(now(6), interval 90 day), "
                + "created_at = date_sub(now(6), interval 90 day), updated_at = date_sub(now(6), interval 90 day) "
                + "where id in (?, ?)", active.agreementId(), suspended.agreementId());

        creditJobs.expireOffers();

        assertThat(l.status(active.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.status(suspended.agreementId())).isEqualTo("SUSPENDED");
        assertThat(l.events("CreditOfferExpired", active.agreementId())).isZero();
    }

    @Test
    @DisplayName("the supplier editing the offer starts the 14 days again")
    void editingTheOfferRestartsTheClock() throws Exception {
        var offer = offer();
        offeredDaysAgo(offer, 13);
        var edited = e.call("POST", offer.seller().token(), "/api/v1/credit/agreements/" + offer.agreementId()
                + "/modify", null, Map.of("approvedLimit", "90000", "creditPeriodDays", 30, "reason", "Better terms"));
        assertThat(edited.status()).describedAs(edited.body().toString()).isEqualTo(200);
        assertThat(l.status(offer.agreementId())).isEqualTo("APPROVED");

        creditJobs.expireOffers();

        assertThat(l.status(offer.agreementId())).describedAs("a new offer version has a new 14 days")
                .isEqualTo("APPROVED");
        var recent = jdbc.queryForObject("select timestampdiff(hour, offer_made_at, now(6)) from credit_agreement "
                + "where id = ?", Integer.class, offer.agreementId());
        assertThat(recent).isLessThan(2);
    }

    @Test
    @DisplayName("both sides are told: the restaurant (in-app, push) and the supplier (in-app, push); never SMS")
    void bothSidesAreNotified() throws Exception {
        var offer = offer();
        l.registerDevice(offer.buyer().token());
        l.registerDevice(offer.seller().token());
        offeredDaysAgo(offer, 20);

        creditJobs.expireOffers();

        var payload = json.readTree(l.payload("CreditOfferExpired", offer.agreementId()));
        assertThat(payload.at("/outletId").asLong()).isEqualTo(offer.buyer().outletId());
        assertThat(payload.at("/supplierStoreId").asLong()).isEqualTo(offer.seller().storeId());
        l.relayEvents("CreditOfferExpired", offer.agreementId());
        var sent = l.notificationsFor("CreditOfferExpired", offer.agreementId());
        assertThat(sent).extracting(m -> m.get("audience")).containsExactlyInAnyOrder("OUTLET", "SUPPLIER_STORE");
        for (var row : sent) {
            assertThat((String) row.get("title")).isEqualTo("Credit offer expired");
            assertThat((String) row.get("body")).describedAs("each side is told the other's name")
                    .contains("OUTLET".equals(row.get("audience")) ? "ABC Foods" : "Paradise");
        }
        assertThat(l.deliveries("CreditOfferExpired", offer.agreementId(), "PUSH")).isEqualTo(2);
        assertThat(l.deliveries("CreditOfferExpired", offer.agreementId(), "SMS")).isZero();
    }

    @Test
    @DisplayName("an expired offer cannot be accepted or approved, and the restaurant may ask again on the same line")
    void expiredOfferIsDeadAndCanBeRequestedAgain() throws Exception {
        var offer = offer();
        offeredDaysAgo(offer, 15);
        creditJobs.expireOffers();
        assertThat(l.status(offer.agreementId())).isEqualTo("EXPIRED");

        var late = accept(offer);
        assertThat(late.status()).describedAs(late.body().toString()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("INVALID_STATE_TRANSITION");
        var approve = e.call("POST", offer.seller().token(), "/api/v1/credit/agreements/" + offer.agreementId()
                + "/approve", null, Map.of());
        assertThat(approve.status()).isEqualTo(409);
        assertThat(l.status(offer.agreementId())).isEqualTo("EXPIRED");
        assertThat(e.agreementRow(offer.agreementId()).get("reserved_amount")).isNotNull();

        var again = e.call("POST", offer.buyer().token(), "/api/v1/credit/requests", null,
                Map.of("supplierStoreId", offer.seller().storeId(), "outletId", offer.buyer().outletId(),
                        "requestedLimit", "50000", "requestedDays", 30));
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data().at("/id").asLong()).isEqualTo(offer.agreementId());
        assertThat(again.data().at("/status").asText()).isEqualTo("REQUESTED");
        assertThat(l.column(offer.agreementId(), "offer_made_at")).describedAs("a new round has no offer yet").isNull();
        var approved = e.call("POST", offer.seller().token(), "/api/v1/credit/agreements/" + offer.agreementId()
                + "/approve", null, Map.of());
        assertThat(approved.data().at("/status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an offer accepted on day 13 stays ACTIVE for ever: the job never touches it")
    void acceptedOfferIsSafe() throws Exception {
        var offer = offer();
        offeredDaysAgo(offer, 13);
        assertThat(accept(offer).status()).isEqualTo(200);
        offeredDaysAgo(offer, 40);

        creditJobs.expireOffers();

        assertThat(l.status(offer.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.events("CreditOfferExpired", offer.agreementId())).isZero();
    }

    /** Holds the agreement row so the accept and the job both queue behind it, then let go together. */
    private final class Holder {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> done;

        Holder(long agreementId) throws Exception {
            done = pool.submit(() -> {
                try (Connection c = jdbc.getDataSource().getConnection()) {
                    c.setAutoCommit(false);
                    try (var st = c.createStatement()) {
                        st.execute("select id from credit_agreement where id = " + agreementId + " for update");
                    }
                    held.countDown();
                    release.await();
                    c.commit();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                return null;
            });
            assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
        }

        void release() throws Exception {
            release.countDown();
            done.get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("accept racing the expiry job: exactly one wins, and the loser changes and announces nothing (3 rounds)")
    void acceptRacingExpiry() throws Exception {
        for (int round = 0; round < 3; round++) {
            var offer = offer();
            offeredDaysAgo(offer, 15);
            var holder = new Holder(offer.agreementId());
            // Queue behind the held row in a different order each round, so both interleavings are exercised.
            boolean acceptFirst = round != 1;
            Future<Reply> accepting = null;
            Future<?> sweeping = null;
            for (int turn = 0; turn < 2; turn++) {
                if ((turn == 0) == acceptFirst) {
                    accepting = pool.submit(() -> accept(offer));
                } else {
                    sweeping = pool.submit(() -> {
                        creditJobs.expireOffers();
                        return null;
                    });
                }
                Thread.sleep(300);
            }
            holder.release();
            Reply accepted = accepting.get(30, TimeUnit.SECONDS);
            sweeping.get(30, TimeUnit.SECONDS);

            String status = l.status(offer.agreementId());
            int expiredEvents = l.events("CreditOfferExpired", offer.agreementId());
            int approvedEvents = l.events("CreditApproved", offer.agreementId());
            int activated = l.audits("CREDIT_ACTIVATED", offer.agreementId());
            if (status.equals("ACTIVE")) {
                assertThat(approvedEvents).isEqualTo(1);
                assertThat(accepted.status()).describedAs("round " + round + " " + accepted.body()).isEqualTo(200);
                assertThat(expiredEvents).describedAs("accepted: no expiry notice").isZero();
                assertThat(activated).isEqualTo(1);
                assertThat(l.audits("CREDIT_OFFER_EXPIRED", offer.agreementId())).isZero();
            } else {
                assertThat(status).describedAs("round " + round).isEqualTo("EXPIRED");
                assertThat(accepted.status()).describedAs("round " + round + " " + accepted.body()).isEqualTo(409);
                assertThat(expiredEvents).isEqualTo(1);
                assertThat(activated).describedAs("expired: never activated").isZero();
                assertThat(approvedEvents).describedAs("no activation notice for an expired offer").isZero();
            }
            assertThat(l.column(offer.agreementId(), "activated_at") != null)
                    .describedAs("activated only if it is ACTIVE").isEqualTo(status.equals("ACTIVE"));
        }
    }

    @Test
    @DisplayName("a job that read the offer before it was accepted does not expire it afterwards: the locked row is looked at again")
    void staleCandidateIsNotExpired() throws Exception {
        var offer = offer();
        offeredDaysAgo(offer, 20);
        assertThat(accept(offer).status()).isEqualTo(200);
        offeredDaysAgo(offer, 20);

        boolean expired = lifecycleSpy.expireOffer(offer.agreementId(), CreditEdgeSupport.today());

        assertThat(expired).isFalse();
        assertThat(l.status(offer.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.events("CreditOfferExpired", offer.agreementId())).isZero();
    }

    @Test
    @DisplayName("one agreement failing in the job does not stop the others, and is retried by the next run")
    void oneFailureDoesNotStopTheSweep() throws Exception {
        var a = offer();
        var b = offer();
        offeredDaysAgo(a, 20);
        offeredDaysAgo(b, 20);
        doThrow(new IllegalStateException("boom")).when(lifecycleSpy).expireOffer(eq(a.agreementId()), any());

        creditJobs.expireOffers();

        assertThat(l.status(b.agreementId())).isEqualTo("EXPIRED");
        assertThat(l.status(a.agreementId())).isEqualTo("APPROVED");
        org.mockito.Mockito.reset(lifecycleSpy);
        creditJobs.expireOffers();
        assertThat(l.status(a.agreementId())).isEqualTo("EXPIRED");
    }
}
