package com.costonomy.mp.credit;

import com.costonomy.mp.access.service.RolePermissionCatalog;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.service.CreditDigestService;
import com.costonomy.mp.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The daily supplier digest (B14, D-148): content from the server's own counts, skipped when everything is zero, once per
 * store per India day, in-app and push only, and only to the store's people who may see credit.
 */
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditDigestIT extends CreditClockedIT {

    @Autowired CreditDigestService digest;
    @Autowired RolePermissionCatalog catalog;

    private void at(int hour, int minute) {
        setNow(nowIst().toLocalDate().atTime(hour, minute));
    }

    private long digestEvents(long store) {
        return events("CreditSupplierDigest", store);
    }

    private List<Long> notified(long store) {
        l.relayEvents("CreditSupplierDigest", store);
        return jdbc.queryForList("select user_id from notification where event_type = 'CreditSupplierDigest' "
                + "and target_id = ? order by id", Long.class, store);
    }

    /** Store with a ₹6,500 overdue invoice (₹1,000 repaid from the wallet), ₹3,000 due in 2 days, two claims (one 8 days old), a request and a payout. */
    private Line busyStore() throws Exception {
        var line = s.creditLine("200000");
        long overdue = s.invoice(line, "65", 100);
        long soon = s.invoice(line, "30", 100);
        setDue(overdue, today().minusDays(10), 5);
        setDue(soon, today().plusDays(2), 5);
        long stale = e.claim(line.buyer().token(), overdue, "500.00").data().get("id").asLong();
        assertThat(e.claim(line.buyer().token(), soon, "100.00").status()).isEqualTo(201);
        jdbc.update("update credit_payment_claim set created_at = date_sub(now(6), interval 8 day) where id = ?", stale);
        // A request from another restaurant, waiting for an answer.
        var asker = s.newBuyer();
        assertThat(s.api.postStatus(asker.token(), "/api/v1/credit/requests", Map.of("supplierStoreId",
                line.seller().storeId(), "outletId", asker.outletId(), "requestedLimit", "50000", "requestedDays", 30,
                "purpose", "PROCUREMENT"))).isEqualTo(200);
        // A wallet repayment leaves a pending payout for the supplier.
        s.topUp(line.buyer(), "20000.00");
        var repaid = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(overdue)));
        assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(201);
        return line;
    }

    private static final String BUSY_MESSAGE = "2 payment claims waiting (1 for 7+ days) · ₹5,500 overdue from 1 restaurant"
            + " · ₹3,000 due this week · 1 credit request to answer · 1 payout pending";

    @Test
    @DisplayName("the digest says what the server counts: claims (and stale), overdue and restaurants, due this week, requests, payouts")
    void digestContent() throws Exception {
        var line = busyStore();
        long store = line.seller().storeId();
        at(8, 30);

        digest.sendDigests();

        assertThat(digestEvents(store)).isEqualTo(1);
        var payload = json.readTree(l.payload("CreditSupplierDigest", store));
        assertThat(payload.get("message").asText()).isEqualTo(BUSY_MESSAGE);
        assertThat(payload.get("supplierStoreId").asLong()).isEqualTo(store);
        var log = jdbc.queryForMap("select * from credit_digest_log where supplier_store_id = ?", store);
        assertThat(log.get("digest_date").toString()).isEqualTo(today().toString());
        assertThat(((Number) log.get("claims_waiting")).intValue()).isEqualTo(2);
        assertThat(((Number) log.get("claims_stale")).intValue()).isEqualTo(1);
        assertThat(log.get("overdue_amount").toString()).isEqualTo("5500.0000");
        assertThat(((Number) log.get("overdue_restaurants")).intValue()).isEqualTo(1);
        assertThat(log.get("due_week_amount").toString()).isEqualTo("3000.0000");
        assertThat(((Number) log.get("requests_pending")).intValue()).isEqualTo(1);
        assertThat(((Number) log.get("payouts_pending")).intValue()).isEqualTo(1);
        assertThat(log.get("sent")).isEqualTo(true);
    }

    @Test
    @DisplayName("only the parts that are not zero appear: an overdue-only store reads just the overdue line")
    void zeroPartsAreLeftOut() throws Exception {
        var line = s.creditLine("200000");
        long overdue = s.invoice(line, "65", 100);
        setDue(overdue, today().minusDays(10), 5);
        at(8, 30);
        digest.sendDigests();
        assertThat(json.readTree(l.payload("CreditSupplierDigest", line.seller().storeId())).get("message").asText())
                .isEqualTo("₹6,500 overdue from 1 restaurant");
    }

    @Test
    @DisplayName("everything zero: nothing is sent (no event, no notification), and the day is logged as looked at")
    void emptyIsSkipped() throws Exception {
        var line = s.creditLine("200000");   // a live line, no invoices, no claims, no requests
        long store = line.seller().storeId();
        at(8, 30);
        digest.sendDigests();
        assertThat(digestEvents(store)).isZero();
        assertThat(notified(store)).isEmpty();
        assertThat(jdbc.queryForObject("select sent from credit_digest_log where supplier_store_id = ?", Integer.class,
                store)).isZero();

        // Something turns up later the same day: it waits for tomorrow's digest.
        long overdue = s.invoice(line, "65", 100);
        setDue(overdue, today().minusDays(10), 5);
        at(14, 0);
        digest.sendDigests();
        assertThat(digestEvents(store)).isZero();
        setNow(today().plusDays(1).atTime(8, 30));
        digest.sendDigests();
        assertThat(digestEvents(store)).isEqualTo(1);
    }

    @Test
    @DisplayName("once per store per India day: not before 08:30, not again later that day, not after 20:00, again tomorrow")
    void oncePerDay() throws Exception {
        var line = busyStore();
        long store = line.seller().storeId();

        at(8, 29);
        digest.sendDigests();
        assertThat(digestEvents(store)).describedAs("before 08:30").isZero();
        at(8, 30);
        digest.sendDigests();
        at(8, 40);
        digest.sendDigests();
        at(15, 0);
        digest.sendDigests();
        assertThat(digestEvents(store)).isEqualTo(1);

        setNow(today().plusDays(1).atTime(20, 0));
        digest.sendDigests();
        assertThat(digestEvents(store)).describedAs("the window closed at 20:00").isEqualTo(1);
        setNow(today().plusDays(1).atTime(9, 0));
        digest.sendDigests();
        digest.sendDigests();
        assertThat(digestEvents(store)).describedAs("a new India day").isEqualTo(2);
        assertThat(e.count("select count(*) from credit_digest_log where supplier_store_id = ?", store)).isEqualTo(2);
    }

    @Test
    @DisplayName("two nodes at once send one digest; the database refuses a second log row for a store and day")
    void twoNodesSendOnce() throws Exception {
        var line = busyStore();
        long store = line.seller().storeId();
        at(9, 0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var runs = List.of(
                    pool.submit(() -> { start.await(); digest.sendDigests(); return null; }),
                    pool.submit(() -> { start.await(); digest.sendDigests(); return null; }));
            start.countDown();
            for (var run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(digestEvents(store)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into credit_digest_log (supplier_store_id, digest_date, sent, "
                + "claims_waiting, claims_stale, overdue_amount, overdue_restaurants, due_week_amount, requests_pending, "
                + "payouts_pending) values (?, ?, 1, 0, 0, 0, 0, 0, 0, 0)", store, today()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("recipients: the store's people who hold CREDIT_VIEW, in-app and push, never SMS; a person without CREDIT_VIEW is not told")
    void recipientsAreCreditViewers() throws Exception {
        var line = busyStore();
        long store = line.seller().storeId();
        String owner = line.seller().token();
        String finance = l.staff(line, "SUP_FINANCE_STAFF");
        // A person of the store whose role cannot see credit at all.
        String blind = blindUser(line);
        l.registerDevice(owner);
        l.registerDevice(blind);

        at(8, 30);
        digest.sendDigests();
        var users = notified(store);

        assertThat(users).containsExactlyInAnyOrder(e.userId(owner), e.userId(finance));
        assertThat(users).doesNotContain(e.userId(blind));
        assertThat(l.deliveries("CreditSupplierDigest", store, "PUSH")).describedAs("owner's device only").isEqualTo(1);
        assertThat(l.deliveries("CreditSupplierDigest", store, "SMS")).isZero();
        var inbox = l.notificationsFor("CreditSupplierDigest", store);
        assertThat(inbox).hasSize(2);
        assertThat(inbox.get(0).get("title")).isEqualTo("Credit today");
        assertThat(inbox.get(0).get("body")).isEqualTo(BUSY_MESSAGE);
        assertThat(inbox.get(0).get("audience")).isEqualTo("SUPPLIER_STORE_CREDIT");
        // Control: the finance user really can see this store's credit (so being told is not an accident).
        assertThat(e.call("GET", finance, "/api/v1/supplier-stores/" + store + "/credit/receivables", null, null).status())
                .isEqualTo(200);
        assertThat(e.call("GET", blind, "/api/v1/supplier-stores/" + store + "/credit/receivables", null, null).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("stores are separate: each digest holds only that store's figures and reaches only that store's people")
    void storesAreIsolated() throws Exception {
        var a = busyStore();
        var b = s.creditLine("200000");
        long bInvoice = s.invoice(b, "12", 100);
        setDue(bInvoice, today().minusDays(10), 5);
        long storeA = a.seller().storeId();
        long storeB = b.seller().storeId();

        at(8, 30);
        digest.sendDigests();

        assertThat(json.readTree(l.payload("CreditSupplierDigest", storeB)).get("message").asText())
                .isEqualTo("₹1,200 overdue from 1 restaurant");
        assertThat(json.readTree(l.payload("CreditSupplierDigest", storeA)).get("message").asText())
                .isEqualTo(BUSY_MESSAGE);
        assertThat(notified(storeA)).containsExactly(e.userId(a.seller().token()));
        assertThat(notified(storeB)).containsExactly(e.userId(b.seller().token()));
    }

    /** A user with a grant on the store whose role has STORE_VIEW and nothing about credit. */
    private String blindUser(Line line) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = s.api.login(phone);
        long userId = e.userId(token);
        String code = "TEST_NO_CREDIT_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into role (code, name, scope, description, status, created_at, updated_at, version) "
                + "values (?, 'No credit', 'SUPPLIER', 'test: sees the store, not credit', 'ACTIVE', now(6), now(6), 0)",
                code);
        jdbc.update("insert into role_permission (role_id, permission_id, created_at) "
                + "select r.id, p.id, now(6) from role r join permission p on p.code = 'STORE_VIEW' where r.code = ?", code);
        catalog.refresh();
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status, granted_at, created_at,
                                       updated_at, version)
                select ?, r.id, 'SUPPLIER_STORE', ?, 'ACTIVE', now(6), now(6), now(6), 0 from role r where r.code = ?
                """, userId, line.seller().storeId(), code);
        return token;
    }
}
