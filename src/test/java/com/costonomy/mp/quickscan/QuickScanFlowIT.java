package com.costonomy.mp.quickscan;

import com.costonomy.mp.quickscan.domain.QuickScanPayment;
import com.costonomy.mp.quickscan.provider.MockPayoutProvider;
import com.costonomy.mp.quickscan.provider.PayoutProvider;
import com.costonomy.mp.quickscan.provider.PayoutProviderException;
import com.costonomy.mp.quickscan.repository.QuickScanPaymentRepository;
import com.costonomy.mp.quickscan.service.QuickScanJobs;
import com.costonomy.mp.quickscan.service.QuickScanService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * QuickScan wallet payments end to end (D-106): pay any UPI merchant from the
 * wallet, sandbox only. The property under test throughout is that the wallet
 * debit and the payout outcome always agree — never a debit with no payout
 * attempt, never money missing after a payout that did not reach the shop.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.quickscan.enabled=true")
class QuickScanFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QuickScanJobs quickScanJobs;
    @Autowired private QuickScanService quickScanService;
    @Autowired private QuickScanPaymentRepository quickScanPayments;
    @SpyBean private MockPayoutProvider payoutProvider;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        reset(payoutProvider);
    }

    private record Buyer(String token, long outletId) {
    }

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    private void topUp(Buyer buyer, String amount) throws Exception {
        api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up",
                Map.of("amount", amount));
    }

    private MockHttpServletResponse payCall(Buyer buyer, String vpa, String amount, String key) throws Exception {
        return payCall(buyer.token(), buyer.outletId(), vpa, amount, key, "WALLET");
    }

    private MockHttpServletResponse payCall(String token, long outletId, String vpa, String amount,
                                            String key, String method) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/outlets/" + outletId + "/quickscan/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new java.util.HashMap<>(Map.of(
                                "payeeVpa", vpa, "payeeName", "Shop", "amount", amount,
                                "note", "test", "method", method)))))
                .andReturn().getResponse();
    }

    private JsonNode pay(Buyer buyer, String vpa, String amount) throws Exception {
        var response = payCall(buyer, vpa, amount, UUID.randomUUID().toString());
        assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).at("/data");
    }

    private BigDecimal balance(Buyer buyer) {
        return jdbc.queryForObject(
                "select coalesce((select balance from wallet where outlet_id = ?), 0)",
                BigDecimal.class, buyer.outletId());
    }

    private String status(long paymentId) {
        return jdbc.queryForObject(
                "select status from quickscan_payment where id = ?", String.class, paymentId);
    }

    private int paymentCount(Buyer buyer) {
        return jdbc.queryForObject(
                "select count(*) from quickscan_payment where outlet_id = ?", Integer.class, buyer.outletId());
    }

    private int ledgerRows(long paymentId) {
        return jdbc.queryForObject("select count(*) from wallet_transaction where reference in (?, ?)",
                Integer.class, "quickscan-" + paymentId, "quickscan-return-" + paymentId);
    }

    private void backdate(long paymentId, int minutes) {
        backdateColumn(paymentId, "updated_at", minutes);
    }

    /**
     * Pushes one timestamp column back by {@code minutes}, straight in SQL so
     * {@code @UpdateTimestamp}/{@code @CreationTimestamp} cannot overwrite it on
     * the next save — the same trick {@link #backdate} already used for
     * {@code updated_at}, generalised for {@code created_at}, {@code paid_at}
     * and {@code checked_at} (D-106's job-claim and settlement-backoff windows).
     */
    private void backdateColumn(long paymentId, String column, int minutes) {
        jdbc.update("update quickscan_payment set " + column
                + " = date_sub(utc_timestamp(6), interval ? minute) where id = ?", minutes, paymentId);
    }

    private long currentUserId(String token) throws Exception {
        return api.get(token, "/api/v1/auth/me").at("/data/user/id").asLong();
    }

    /**
     * Inserts a PAYOUT_PENDING row straight in SQL, bypassing
     * {@code QuickScanService.payFromWallet} entirely — the only way to get a
     * fresh (zero-attempt) row that stays fresh, since the service's own
     * request-path claims a row the instant it creates one.
     */
    private long insertPendingRow(long outletId, long createdBy, String amount) {
        String key = "qs-test-" + UUID.randomUUID();
        jdbc.update("""
                insert into quickscan_payment (outlet_id, created_by, payee_vpa, payee_name, amount, fee_amount,
                                                method, status, attempts, idempotency_key, created_at, updated_at, version)
                values (?, ?, 'shop@okhdfcbank', 'Shop', ?, 0, 'WALLET', 'PAYOUT_PENDING', 0, ?,
                        utc_timestamp(6), utc_timestamp(6), 0)
                """, outletId, createdBy, amount, key);
        return jdbc.queryForObject("select id from quickscan_payment where idempotency_key = ?", Long.class, key);
    }

    private void grant(long userId, long outletId, String roleCode) {
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'OUTLET', ?, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, outletId, roleCode);
    }

    // ── Happy path ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("paying")
    class Paying {

        @Test
        @DisplayName("pays a shop, debits the wallet once and finishes PAID within the request")
        void paysAndFinishesSynchronously() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = pay(buyer, "shop@okhdfcbank", "250.00");

            assertThat(response.get("status").asText()).isEqualTo("PAID");
            assertThat(response.get("amount").decimalValue()).isEqualByComparingTo("250.00");
            assertThat(response.get("total").decimalValue()).isEqualByComparingTo("250.00");
            assertThat(balance(buyer)).isEqualByComparingTo("750.00");
            assertThat(paymentCount(buyer)).isEqualTo(1);
            String reference = "quickscan-" + response.get("id").asLong();
            assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?",
                    Integer.class, reference)).isEqualTo(1);
            var row = jdbc.queryForMap(
                    "select kind, direction from wallet_transaction where reference = ?", reference);
            assertThat(row).containsEntry("kind", "QUICKSCAN_PAYMENT").containsEntry("direction", "DEBIT");
        }

        @Test
        @DisplayName(".13 fails immediately and the money is back in the wallet exactly once")
        void refusedPayoutReturnsMoneyOnce() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = pay(buyer, "shop@okhdfcbank", "100.13");
            long paymentId = response.get("id").asLong();

            assertThat(response.get("status").asText()).isEqualTo("FAILED");
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(2);

            // Running the job again changes nothing: a FAILED row is not claimable.
            quickScanJobs.run();
            assertThat(status(paymentId)).isEqualTo("FAILED");
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(2);
        }

        @Test
        @DisplayName(".23 is accepted as pending, then PAID once the job settles it")
        void pendingThenSettledPaid() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = pay(buyer, "shop@okhdfcbank", "100.23");
            long paymentId = response.get("id").asLong();
            assertThat(response.get("status").asText()).isEqualTo("PAYOUT_PENDING");
            assertThat(jdbc.queryForObject(
                    "select provider_payout_id from quickscan_payment where id = ?", String.class, paymentId))
                    .isNotBlank();

            backdate(paymentId, 5);
            quickScanJobs.run();

            assertThat(status(paymentId)).isEqualTo("PAID");
            assertThat(balance(buyer)).isEqualByComparingTo("899.77");
        }

        @Test
        @DisplayName(".31 is accepted as pending, then REVERSED on settlement, and the money returns once")
        void pendingThenReversedReturnsMoney() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = pay(buyer, "shop@okhdfcbank", "100.31");
            long paymentId = response.get("id").asLong();
            assertThat(response.get("status").asText()).isEqualTo("PAYOUT_PENDING");

            backdate(paymentId, 5);
            quickScanJobs.run();

            assertThat(status(paymentId)).isEqualTo("FAILED");
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(2);

            // Settling again (e.g. the job's next run) cannot return it twice.
            backdate(paymentId, 5);
            quickScanJobs.run();
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(2);
        }

        @Test
        @DisplayName("a transient provider failure is retried up to 5 attempts, then NEEDS_REVIEW with the money left out")
        void transientFailureCapsAtNeedsReview() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            doThrow(new PayoutProviderException("down", true, "GATEWAY_ERROR"))
                    .when(payoutProvider).createPayout(anyString(), any(), any(), anyString(), anyString());

            var response = pay(buyer, "shop@okhdfcbank", "100.00");
            long paymentId = response.get("id").asLong();
            assertThat(response.get("status").asText()).isEqualTo("PAYOUT_PENDING");

            for (int attempt = 0; attempt < 4; attempt++) {
                backdate(paymentId, 6);
                quickScanJobs.run();
            }

            assertThat(status(paymentId)).isEqualTo("NEEDS_REVIEW");
            verify(payoutProvider, times(5)).createPayout(anyString(), any(), any(), anyString(), anyString());
            // Out of the wallet and not returned: the outcome is unknown.
            assertThat(balance(buyer)).isEqualByComparingTo("900.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(1);
        }

        @Test
        @DisplayName("an unexpected error from the provider after the debit commits still returns 200 "
                + "PAYOUT_PENDING, debited once, and the job finishes it")
        void unexpectedProviderErrorAfterDebitStillReturnsPending() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            // Not a PayoutProviderException — the provider adapter itself blowing
            // up (a bug, a timeout library throwing something unchecked), which
            // sendPayout does not catch. Before the fix this propagated out of
            // payFromWallet after the debit had already committed, so the client
            // saw an error although the wallet was charged.
            doThrow(new IllegalStateException("boom"))
                    .when(payoutProvider).createPayout(anyString(), any(), any(), anyString(), anyString());

            var response = payCall(buyer, "shop@okhdfcbank", "100.00", UUID.randomUUID().toString());
            assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
            var body = json.readTree(response.getContentAsString()).at("/data");
            long paymentId = body.get("id").asLong();
            assertThat(body.get("status").asText()).isEqualTo("PAYOUT_PENDING");
            assertThat(balance(buyer)).isEqualByComparingTo("900.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(1);

            // Restore the provider and let the row age past both the job's
            // fresh-row delay and its stuck-claim window, the same way a real
            // retry would arrive on the next job run.
            reset(payoutProvider);
            backdateColumn(paymentId, "created_at", 6);
            backdate(paymentId, 6);

            quickScanJobs.run();

            assertThat(status(paymentId)).isEqualTo("PAID");
            assertThat(balance(buyer)).isEqualByComparingTo("900.00");
            assertThat(ledgerRows(paymentId)).isEqualTo(1);
        }
    }

    // ── Job claiming ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("job claiming")
    class JobClaiming {

        @Test
        @DisplayName("does not claim a fresh row created less than 30s ago, but does once it ages past that "
                + "(the charged-then-errored race, D-106)")
        void freshRowExcludedThenIncludedAfterJobClaimDelay() throws Exception {
            var buyer = newBuyer();
            long actorId = currentUserId(buyer.token());
            long paymentId = insertPendingRow(buyer.outletId(), actorId, "100.00");

            assertThat(findClaimableIds()).doesNotContain(paymentId);

            backdateColumn(paymentId, "created_at", 1); // 60s > JOB_CLAIM_DELAY (30s)

            assertThat(findClaimableIds()).contains(paymentId);

            // This row was inserted straight in SQL rather than through a real
            // payFromWallet call, so it never resolves on its own and would
            // otherwise sit here as a genuinely claimable row for every other
            // test's quickScanJobs.run() to pick up (findClaimable has no
            // per-test or per-outlet scope) — delete it rather than let it leak.
            jdbc.update("delete from quickscan_payment where id = ?", paymentId);
        }

        // Mirrors QuickScanService.JOB_CLAIM_DELAY (30s) and STUCK_AFTER (5m),
        // both package-private to the service package; the values are pinned
        // here the same way every other backdate(...) call in this class already
        // pins STUCK_AFTER by using 5/6 minutes.
        private List<Long> findClaimableIds() {
            var now = Instant.now();
            return quickScanPayments.findClaimable(
                            now.minus(Duration.ofSeconds(30)), now.minus(Duration.ofMinutes(5)),
                            PageRequest.of(0, 100))
                    .stream().map(QuickScanPayment::getId).toList();
        }
    }

    // ── Settlement backoff (D-106) ──────────────────────────────────────

    @Nested
    @DisplayName("settlement backoff")
    class SettlementBackoff {

        @Test
        @DisplayName("a PAID payout is not re-fetched within 6h of its last check, is re-fetched once that "
                + "passes, and never once paid_at is older than 48h")
        void paidRowBacksOff() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            // .23 is accepted as PENDING; backdating past STUCK_AFTER lets the job
            // fetch it and settle it PAID on the first check.
            var response = pay(buyer, "shop@okhdfcbank", "100.23");
            long paymentId = response.get("id").asLong();
            backdate(paymentId, 5);
            quickScanJobs.run();
            assertThat(status(paymentId)).isEqualTo("PAID");
            verify(payoutProvider, times(1)).fetchPayout(anyString());

            // Freshly checked: another run must not re-fetch it.
            quickScanJobs.run();
            verify(payoutProvider, times(1)).fetchPayout(anyString());

            // Past PAID_CHECK_EVERY (6h) but still inside PAID_WATCH_FOR (48h): fetched again.
            backdateColumn(paymentId, "checked_at", 7 * 60);
            backdateColumn(paymentId, "paid_at", 7 * 60);
            quickScanJobs.run();
            verify(payoutProvider, times(2)).fetchPayout(anyString());

            // Past PAID_WATCH_FOR (48h): never fetched again, however stale the check.
            backdateColumn(paymentId, "checked_at", 49 * 60);
            backdateColumn(paymentId, "paid_at", 49 * 60);
            quickScanJobs.run();
            verify(payoutProvider, times(2)).fetchPayout(anyString());

            // findSettleable has no per-test or per-outlet scope; leaving this
            // row's provider id behind would make it (harmlessly, but flakily)
            // eligible for a later test's quickScanJobs.run() the moment real
            // time crosses one of the windows above.
            jdbc.update("delete from quickscan_payment where id = ?", paymentId);
        }

        @Test
        @DisplayName("a PENDING payout is fetched at most once a minute")
        void pendingRowBacksOff() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = pay(buyer, "shop@okhdfcbank", "100.23");
            long paymentId = response.get("id").asLong();
            assertThat(response.get("status").asText()).isEqualTo("PAYOUT_PENDING");
            // Stub every fetch to keep answering PENDING, so the row never
            // settles and the only thing under test is how often it is asked.
            doReturn(new PayoutProvider.ProviderPayout("ignored", PayoutProvider.PayoutStatus.PENDING, null, null))
                    .when(payoutProvider).fetchPayout(anyString());

            // Just created: too recent to be checked at all.
            quickScanJobs.run();
            verify(payoutProvider, times(0)).fetchPayout(anyString());

            backdate(paymentId, 2); // past PENDING_CHECK_EVERY (1 min)
            quickScanJobs.run();
            verify(payoutProvider, times(1)).fetchPayout(anyString());

            // Just checked: another run within the minute must not re-fetch it.
            quickScanJobs.run();
            verify(payoutProvider, times(1)).fetchPayout(anyString());

            backdateColumn(paymentId, "checked_at", 2); // past PENDING_CHECK_EVERY again
            quickScanJobs.run();
            verify(payoutProvider, times(2)).fetchPayout(anyString());

            // This row never resolves (the stub always answers PENDING), so
            // without cleanup it stays claimable-for-settlement forever, for
            // any later test's quickScanJobs.run() to pick up once a real
            // minute passes — see the same note in paidRowBacksOff.
            jdbc.update("delete from quickscan_payment where id = ?", paymentId);
        }
    }

    // ── Validation and refusal ───────────────────────────────────────────

    @Nested
    @DisplayName("refusals write nothing")
    class Refusals {

        @Test
        @DisplayName("more than the wallet balance is refused")
        void insufficientBalance() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "100.00");

            var response = payCall(buyer, "shop@okhdfcbank", "100.01", UUID.randomUUID().toString());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("100.00");
            assertThat(paymentCount(buyer)).isZero();
        }

        @Test
        @DisplayName("over the configured maximum is refused")
        void overMaxAmount() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "20000.00");

            var response = payCall(buyer, "shop@okhdfcbank", "10000.01", UUID.randomUUID().toString());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("20000.00");
            assertThat(paymentCount(buyer)).isZero();
        }

        @Test
        @DisplayName("an invalid VPA is refused")
        void invalidVpa() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = payCall(buyer, "not-a-vpa", "100.00", UUID.randomUUID().toString());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(paymentCount(buyer)).isZero();
        }

        @Test
        @DisplayName("UPI-direct is refused: it isn't built yet")
        void upiMethodRefused() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");

            var response = payCall(buyer.token(), buyer.outletId(), "shop@okhdfcbank", "100.00",
                    UUID.randomUUID().toString(), "UPI");

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(balance(buyer)).isEqualByComparingTo("1000.00");
            assertThat(paymentCount(buyer)).isZero();
        }
    }

    // ── Idempotency ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key returns the same payment; a different amount on it is refused")
        void sameKeyReplaysDifferentAmountConflicts() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "1000.00");
            String key = UUID.randomUUID().toString();

            var first = payCall(buyer, "shop@okhdfcbank", "100.00", key);
            assertThat(first.getStatus()).isEqualTo(200);
            long firstId = json.readTree(first.getContentAsString()).at("/data/id").asLong();

            var replay = payCall(buyer, "shop@okhdfcbank", "100.00", key);
            assertThat(replay.getStatus()).isEqualTo(200);
            assertThat(json.readTree(replay.getContentAsString()).at("/data/id").asLong()).isEqualTo(firstId);
            assertThat(paymentCount(buyer)).isEqualTo(1);

            var conflict = payCall(buyer, "shop@okhdfcbank", "100.01", key);
            assertThat(conflict.getStatus()).isEqualTo(409);
            assertThat(paymentCount(buyer)).isEqualTo(1);
        }
    }

    // ── Tenant isolation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("another restaurant gets 404 on pay and on read")
        void anotherTenantGets404() throws Exception {
            var owner = newBuyer();
            topUp(owner, "1000.00");
            var paid = pay(owner, "shop@okhdfcbank", "100.00");
            var stranger = newBuyer();

            assertThat(payCall(stranger.token(), owner.outletId(), "shop@okhdfcbank", "50.00",
                    UUID.randomUUID().toString(), "WALLET").getStatus()).isEqualTo(404);
            assertThat(balance(owner)).isEqualByComparingTo("900.00");

            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/quickscan/payments/" + paid.get("id").asLong())
                            .header("Authorization", "Bearer " + stranger.token()))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("a member without QUICKSCAN_PAY cannot pay, and nothing is debited")
        void memberWithoutPermissionRefused() throws Exception {
            var owner = newBuyer();
            topUp(owner, "1000.00");
            String staffToken = api.loginFresh();
            long staffUserId = currentUserId(staffToken);
            grant(staffUserId, owner.outletId(), "REST_RECEIVING_STAFF");

            var response = payCall(staffToken, owner.outletId(), "shop@okhdfcbank", "50.00",
                    UUID.randomUUID().toString(), "WALLET");

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(balance(owner)).isEqualByComparingTo("1000.00");
            assertThat(paymentCount(owner)).isZero();
        }
    }

    // ── Concurrency ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("two payments that together exceed the balance: exactly one succeeds, five races")
        void concurrentPaymentsSpendOnce() throws Exception {
            for (int race = 0; race < 5; race++) {
                var buyer = newBuyer();
                topUp(buyer, "150.00");

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(2);
                var a = pool.submit(() -> {
                    start.await();
                    return payCall(buyer, "shop@okhdfcbank", "100.00", UUID.randomUUID().toString()).getStatus();
                });
                var b = pool.submit(() -> {
                    start.await();
                    return payCall(buyer, "shop@okhdfcbank", "100.00", UUID.randomUUID().toString()).getStatus();
                });
                start.countDown();
                var outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
                pool.shutdown();

                assertThat(outcomes).containsExactlyInAnyOrder(200, 400);
                assertThat(paymentCount(buyer)).isEqualTo(1);
                assertThat(balance(buyer)).isEqualByComparingTo("50.00");
            }
        }
    }

    // ── Config ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("config")
    class Config {

        @Test
        @DisplayName("shows the wallet balance, and UPI-direct as unavailable")
        void configShowsBalanceAndUpiUnavailable() throws Exception {
            var buyer = newBuyer();
            topUp(buyer, "321.00");

            var response = api.get(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/quickscan/config")
                    .at("/data");

            assertThat(response.get("enabled").asBoolean()).isTrue();
            assertThat(response.get("walletBalance").decimalValue()).isEqualByComparingTo("321.00");
            var methods = response.get("methods");
            assertThat(methods.get(0).get("method").asText()).isEqualTo("WALLET");
            assertThat(methods.get(0).get("available").asBoolean()).isTrue();
            assertThat(methods.get(1).get("method").asText()).isEqualTo("UPI");
            assertThat(methods.get(1).get("available").asBoolean()).isFalse();
            assertThat(methods.get(1).get("reason").asText()).isNotBlank();
        }
    }
}
