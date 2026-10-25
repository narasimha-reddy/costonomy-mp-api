package com.costonomy.mp.delivery;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.provider.payment.MockCourierPaymentRail;
import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import com.costonomy.mp.delivery.service.CourierPayableProperties;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.CourierPaymentService;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.costonomy.mp.delivery.provider.payment.MockCourierPaymentRail.Script.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The courier payable (D-204): what Costonomy owes a provider for a booked courier, derived from the delivery and paid
 * through a rail. Providers used here are disabled rows, so no quote or booking ever picks them.
 */
@AutoConfigureMockMvc
class CourierPayableIT extends AbstractIntegrationTest {

    private static final String API = "PAYEE_API";
    private static final String MANUAL = "PAYEE_MANUAL";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private SupplierCourierPayableSync sync;
    @Autowired private CourierPayableService payableService;
    @Autowired private CourierPaymentService payments;
    @Autowired private CourierPayableRepository payables;
    @Autowired private MockCourierPaymentRail rail;
    @Autowired private CourierPayableProperties props;

    private ApiClient api;
    private Duration originalBackoff;

    @BeforeAll
    static void noop() {
    }

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, API, "MOCK_API");
        DeliveryFixture.provider(jdbc, MANUAL, "MANUAL");
        rail.reset();
        originalBackoff = props.getRetryBackoff();
        props.setRetryBackoff(Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        props.setRetryBackoff(originalBackoff);
        rail.reset();
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private DeliveryFixture.Setup order(String buyerFee, String supplierFunded) throws Exception {
        return DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, buyerFee, supplierFunded);
    }

    private long attempt(long deliveryId, int number, String provider, String outcome, String providerDeliveryId,
                         String quoted, String endedCause) {
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, ended_cause, started_at, ended_at, created_at)
                values (?, ?, ?, 'BOOKING', ?, ?, ?, ?, now(6), ?, now(6))
                """, deliveryId, provider, number, outcome, providerDeliveryId, quoted == null ? null : new BigDecimal(quoted),
                endedCause, endedCause == null ? null : java.sql.Timestamp.from(java.time.Instant.now()));
        return jdbc.queryForObject("select id from delivery_provider_attempt where delivery_id = ? and attempt_number = ?",
                Long.class, deliveryId, number);
    }

    private void delivery(long id, String status, String provider, String providerDeliveryId, String fee, boolean pickedUp,
                          String mode) {
        jdbc.update("update delivery set status = ?, provider_code = ?, provider_delivery_id = ?, fee = ?, mode = ?, "
                        + "picked_up_at = ?, version = version + 1 where id = ?",
                status, provider, providerDeliveryId, new BigDecimal(fee), mode,
                pickedUp ? java.sql.Timestamp.from(java.time.Instant.now()) : null, id);
    }

    private CourierPayable only(long deliveryId) {
        var all = payableService.forDelivery(CourierPayable.SUPPLIER_DELIVERY, deliveryId);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /** A payable that has the goods picked up and is waiting to be paid by the API rail. */
    private CourierPayable dueOnApiRail(String fee) throws Exception {
        var s = order("0.00", "0.00");
        String pdid = "api-" + UUID.randomUUID();
        attempt(s.deliveryId(), 1, API, "BOOKED", pdid, fee, null);
        delivery(s.deliveryId(), "PICKED_UP", API, pdid, fee, true, "COSTONOMY");
        sync.sync(s.deliveryId());
        var p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        return p;
    }

    // ── accrual and funding ────────────────────────────────────────────────

    @Test
    @DisplayName("a booked courier is accrued: known and quoted, but nothing is owed yet")
    void accruedAtBooking() throws Exception {
        var s = order("20.00", "60.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "acc-1", "80.00", null);
        delivery(s.deliveryId(), "PROVIDER_SELECTED", API, "acc-1", "80.00", false, "COSTONOMY");

        sync.sync(s.deliveryId());
        sync.sync(s.deliveryId());   // repeating changes nothing

        var p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.ACCRUED);
        assertThat(p.getAmountDue()).isEqualByComparingTo("0");
        assertThat(p.getQuotedCost()).isEqualByComparingTo("80.00");
        assertThat(p.getSupplierOrderId()).isEqualTo(s.orderId());
        assertThat(p.getPaymentRail()).isEqualTo("MOCK_API");
        assertThat(payableService.history(p.getId())).extracting("eventType").containsExactly("CREATED");
    }

    @Test
    @DisplayName("once the courier has the goods the final cost is owed, split between restaurant, supplier and platform")
    void dueAtPickupWithTheFundingSplit() throws Exception {
        var s = order("20.00", "50.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "due-1", "80.00", null);
        delivery(s.deliveryId(), "PICKED_UP", API, "due-1", "80.00", true, "COSTONOMY");

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        assertThat(p.getDueReason()).isEqualTo("PICKED_UP");
        assertThat(p.getAmountDue()).isEqualByComparingTo("80.00");
        assertThat(p.getBuyerContribution()).isEqualByComparingTo("20.00");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("50.00");
        assertThat(p.getPlatformFunded()).as("the rest").isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a supplier's share is never more than the courier cost, and the platform keeps the difference")
    void supplierNeverPaysMoreThanTheCost() throws Exception {
        var s = order("0.00", "80.00");                 // the supplier agreed to 80 when the quote was 80
        attempt(s.deliveryId(), 1, API, "BOOKED", "cap-1", "80.00", null);
        delivery(s.deliveryId(), "DELIVERED", API, "cap-1", "60.00", true, "COSTONOMY");   // it cost 60

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getAmountDue()).isEqualByComparingTo("60.00");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("60.00");
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("0");
        assertThat(p.getDueReason()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a final cost above the quote is the platform's: the customers' agreed parts do not rise")
    void finalCostAboveTheQuoteIsThePlatformsToBear() throws Exception {
        var s = order("20.00", "40.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "over-1", "80.00", null);
        delivery(s.deliveryId(), "DELIVERED", API, "over-1", "100.00", true, "COSTONOMY");

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getAmountDue()).isEqualByComparingTo("100.00");
        assertThat(p.getBuyerContribution()).isEqualByComparingTo("20.00");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("40.00");
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a final cost below what was collected leaves the platform a margin, shown as a negative platform share")
    void finalCostBelowWhatWasCollected() throws Exception {
        var s = order("90.00", "0.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "low-1", "90.00", null);
        delivery(s.deliveryId(), "DELIVERED", API, "low-1", "70.00", true, "COSTONOMY");

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("-20.00");
    }

    // ── exits: cancellation, reassignment, takeover, failure ────────────────

    @Test
    @DisplayName("a courier cancelled free of charge before pickup owes nothing")
    void cancelledFreeIsVoid() throws Exception {
        var s = order("20.00", "0.00");
        attempt(s.deliveryId(), 1, API, "CANCELLED", "can-1", "80.00", "CUSTOMER_CANCEL");
        delivery(s.deliveryId(), "CANCELLED", API, null, "80.00", false, "COSTONOMY");

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.VOID);
        assertThat(p.getAmountDue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a cancellation fee is owed and is the platform's, even though the goods never moved")
    void cancellationFeeIsOwedAndPlatformFunded() throws Exception {
        var s = order("20.00", "30.00");
        attempt(s.deliveryId(), 1, API, "CANCELLED", "fee-1", "80.00", "CUSTOMER_CANCEL");
        delivery(s.deliveryId(), "CANCELLED", API, null, "80.00", false, "COSTONOMY");
        sync.sync(s.deliveryId());
        var p = only(s.deliveryId());

        payableService.recordCancellationFee(p.getId(), new BigDecimal("15.00"), "user:7", "Provider charged");

        p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        assertThat(p.getDueReason()).isEqualTo("CANCELLATION_FEE");
        assertThat(p.getAmountDue()).isEqualByComparingTo("15.00");
        assertThat(p.getBuyerContribution()).isEqualByComparingTo("0");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("0");
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("15.00");
        sync.sync(s.deliveryId());
        assertThat(only(s.deliveryId()).getAmountDue()).as("a later sync keeps the recorded fee").isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("on reassignment the first courier is void and only the courier that delivered carries the customers' parts")
    void reassignmentVoidsTheFirstCourier() throws Exception {
        var s = order("20.00", "30.00");
        attempt(s.deliveryId(), 1, API, "CANCELLED", "re-1", "70.00", "RIDER_CANCELLED");
        attempt(s.deliveryId(), 2, API, "BOOKED", "re-2", "80.00", null);
        delivery(s.deliveryId(), "DELIVERED", API, "re-2", "80.00", true, "COSTONOMY");

        sync.sync(s.deliveryId());

        var all = payableService.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getState()).isEqualTo(CourierPayableState.VOID);
        assertThat(all.get(1).getState()).isEqualTo(CourierPayableState.DUE);
        assertThat(all.get(1).getBuyerContribution()).isEqualByComparingTo("20.00");
        assertThat(all.get(1).getSupplierContribution()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("a courier that failed after pickup is owed in full, as a post-pickup failure, and the platform bears what is uncovered")
    void failureAfterPickupIsOwed() throws Exception {
        var s = order("0.00", "0.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "fail-1", "80.00", null);
        delivery(s.deliveryId(), "DELIVERY_FAILED", API, "fail-1", "80.00", true, "COSTONOMY");

        sync.sync(s.deliveryId());

        var p = only(s.deliveryId());
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        assertThat(p.getDueReason()).isEqualTo("POST_PICKUP_FAILURE");
        assertThat(p.getAmountDue()).isEqualByComparingTo("80.00");
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("when the supplier takes over a booked delivery the courier that never came owes nothing")
    void supplierTakeoverVoidsTheCourier() throws Exception {
        var s = order("20.00", "0.00");
        attempt(s.deliveryId(), 1, API, "CANCELLED", "own-1", "80.00", "SWITCHED_TO_OWN");
        delivery(s.deliveryId(), "DRIVER_ASSIGNED", null, null, "0.00", false, "SUPPLIER_OWN");

        sync.sync(s.deliveryId());

        assertThat(only(s.deliveryId()).getState()).isEqualTo(CourierPayableState.VOID);
    }

    // ── paying ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an API rail pays a due payable once, and a second request moves no more money")
    void paysOnceThroughTheRail() throws Exception {
        var p = dueOnApiRail("80.00");

        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);
        assertThat(payments.pay(p.getId())).as("already paid").isEqualTo(CourierPaymentService.Result.NOT_CLAIMED);

        var paid = payables.findById(p.getId()).orElseThrow();
        assertThat(paid.getState()).isEqualTo(CourierPayableState.PAID);
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("80.00");
        assertThat(paid.getPaymentReference()).startsWith("MOCK-");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(rail.moneyMoved()).isEqualTo(1);
        assertThat(payableService.history(p.getId())).extracting("eventType")
                .containsSubsequence("CREATED", "PAYMENT_STARTED", "PAYMENT_SUCCEEDED");
    }

    @Test
    @DisplayName("two workers racing to pay the same payable send one payment")
    void concurrentPaymentsSendOne() throws Exception {
        var p = dueOnApiRail("80.00");
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return payments.pay(p.getId()); });
            var b = pool.submit(() -> { start.await(); return payments.pay(p.getId()); });
            start.countDown();
            var results = List.of(a.get(), b.get());
            assertThat(results).containsExactlyInAnyOrder(CourierPaymentService.Result.PAID,
                    CourierPaymentService.Result.NOT_CLAIMED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rail.requests()).hasSize(1);
        assertThat(rail.moneyMoved()).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused payment is retried, and after the attempt limit it waits for a person, who can send it again")
    void failureRetriesThenExhaustsThenAPersonRetries() throws Exception {
        var p = dueOnApiRail("80.00");
        rail.arm(FAIL, FAIL, FAIL, FAIL, FAIL);

        for (int i = 1; i < props.getMaxAttempts(); i++) {
            assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.FAILED);
            assertThat(payables.findById(p.getId()).orElseThrow().getState()).isEqualTo(CourierPayableState.FAILED);
        }
        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.EXHAUSTED);
        var stuck = payables.findById(p.getId()).orElseThrow();
        assertThat(stuck.getState()).isEqualTo(CourierPayableState.EXHAUSTED);
        assertThat(stuck.getLastError()).contains("declined");
        assertThat(payments.pay(p.getId())).as("exhausted payables are not paid by the job").isEqualTo(CourierPaymentService.Result.NOT_CLAIMED);

        payableService.retry(p.getId(), "user:9", "Provider confirmed the account is fixed");
        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);
        assertThat(rail.moneyMoved()).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment whose answer was lost is asked about and found paid, never sent twice")
    void lostAnswerAfterPayingIsFoundNotRepeated() throws Exception {
        var p = dueOnApiRail("80.00");
        rail.arm(LOSE_ANSWER_AFTER_PAYING);

        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.AMBIGUOUS);
        assertThat(payables.findById(p.getId()).orElseThrow().getState()).isEqualTo(CourierPayableState.AMBIGUOUS);
        assertThat(payments.pay(p.getId())).as("not paid again while unknown").isEqualTo(CourierPaymentService.Result.NOT_CLAIMED);

        assertThat(payments.reconcileAmbiguous(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);

        var paid = payables.findById(p.getId()).orElseThrow();
        assertThat(paid.getState()).isEqualTo(CourierPayableState.PAID);
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("80.00");
        assertThat(rail.moneyMoved()).as("the money moved once").isEqualTo(1);
    }

    @Test
    @DisplayName("a payment the rail never received may be sent again, under a new key, once the rail says it has no record")
    void timeoutWithoutPayingIsRetriedUnderANewKey() throws Exception {
        var p = dueOnApiRail("80.00");
        rail.arm(TIME_OUT_WITHOUT_PAYING);

        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.AMBIGUOUS);
        assertThat(payments.reconcileAmbiguous(p.getId())).isEqualTo(CourierPaymentService.Result.FAILED);
        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);

        assertThat(rail.moneyMoved()).isEqualTo(1);
        assertThat(rail.requests()).extracting("idempotencyKey").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unanswered question about an ambiguous payment is asked a bounded number of times, then handed to a person")
    void unansweredLookupsEscalate() throws Exception {
        var p = dueOnApiRail("80.00");
        rail.arm(TIME_OUT_WITHOUT_PAYING);
        rail.lookupAlwaysReturns(com.costonomy.mp.delivery.provider.payment.CourierPaymentRail.Found.UNKNOWN);
        payments.pay(p.getId());
        int original = props.getMaxLookups();
        props.setMaxLookups(3);
        try {
            for (int i = 0; i < 3; i++) {
                jdbc.update("update courier_payable set next_retry_at = null where id = ?", p.getId());
                payments.reconcileAmbiguous(p.getId());
            }
        } finally {
            props.setMaxLookups(original);
        }

        var stuck = payables.findById(p.getId()).orElseThrow();
        assertThat(stuck.getState()).isEqualTo(CourierPayableState.AMBIGUOUS);
        assertThat(stuck.getReconciliationState()).isEqualTo("REQUIRED");
        assertThat(stuck.getNextRetryAt()).as("no longer asked every few minutes").isAfter(java.time.Instant.now().plus(Duration.ofDays(1)));

        // A person checks the bank and says it did not go; it can then be sent again.
        payableService.resolveAmbiguous(p.getId(), false, null, "user:3", "Not in the bank statement");
        rail.reset();
        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);
    }

    @Test
    @DisplayName("a lease that ran out while paying is treated as unknown, then asked about")
    void expiredLeaseBecomesAmbiguous() throws Exception {
        var p = dueOnApiRail("80.00");
        rail.arm(LOSE_ANSWER_AFTER_PAYING);
        payments.pay(p.getId());                       // leaves it AMBIGUOUS with the money gone
        // Put it back to what a crashed worker leaves: PROCESSING under a lease that has run out.
        jdbc.update("update courier_payable set state = 'PROCESSING', claim_token = 'dead', "
                + "claim_expires_at = date_sub(now(6), interval 1 minute) where id = ?", p.getId());
        jdbc.update("update courier_payment_attempt set status = 'SENDING', ended_at = null where payable_id = ?", p.getId());

        assertThat(payments.recoverExpiredLease(p.getId())).isTrue();
        assertThat(payables.findById(p.getId()).orElseThrow().getState()).isEqualTo(CourierPayableState.AMBIGUOUS);
        assertThat(payments.reconcileAmbiguous(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);
        assertThat(rail.moneyMoved()).isEqualTo(1);
    }

    // ── manual rail and operator actions ──────────────────────────────────

    @Test
    @DisplayName("a provider on the manual rail is never paid by the job; a person records the bank payment")
    void manualRailWaitsForAPerson() throws Exception {
        var s = order("0.00", "0.00");
        attempt(s.deliveryId(), 1, MANUAL, "BOOKED", "man-1", "80.00", null);
        delivery(s.deliveryId(), "DELIVERED", MANUAL, "man-1", "80.00", true, "COSTONOMY");
        sync.sync(s.deliveryId());
        var p = only(s.deliveryId());

        assertThat(payments.pay(p.getId())).isEqualTo(CourierPaymentService.Result.NOT_CLAIMED);
        assertThat(payables.readyToPay(java.time.Instant.now(), 100)).doesNotContain(p.getId());
        assertThat(rail.requests()).isEmpty();

        assertThatThrownBy(() -> payableService.markPaid(p.getId(), new BigDecimal("80.00"), " ", null, "user:4", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reference");
        assertThatThrownBy(() -> payableService.markPaid(p.getId(), new BigDecimal("90.00"), "UTR1", null, "user:4", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("more than");

        payableService.markPaid(p.getId(), new BigDecimal("30.00"), "UTR-PART", null, "user:4", "Part payment");
        assertThat(payables.findById(p.getId()).orElseThrow().getState()).isEqualTo(CourierPayableState.DUE);
        payableService.markPaid(p.getId(), new BigDecimal("50.00"), "UTR-REST", null, "user:4", "Balance");

        var paid = payables.findById(p.getId()).orElseThrow();
        assertThat(paid.getState()).isEqualTo(CourierPayableState.PAID);
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("80.00");
        assertThat(paid.getPaymentReference()).isEqualTo("UTR-REST");
        assertThat(payableService.history(p.getId())).extracting("actor").contains("user:4");
    }

    @Test
    @DisplayName("a cost change after payment keeps the state, flags reconciliation, and can be resolved by a person")
    void costChangeAfterPaymentNeedsReconciliation() throws Exception {
        var p = dueOnApiRail("80.00");
        payments.pay(p.getId());

        payableService.recordProviderCredit(p.getId(), new BigDecimal("20.00"), "user:5", "Provider credited a failed leg");

        var flagged = payables.findById(p.getId()).orElseThrow();
        assertThat(flagged.getState()).isEqualTo(CourierPayableState.PAID);
        assertThat(flagged.getAmountDue()).isEqualByComparingTo("60.00");
        assertThat(flagged.overpaid()).as("the provider owes us this").isEqualByComparingTo("20.00");
        assertThat(flagged.getReconciliationState()).isEqualTo("REQUIRED");
        assertThat(payables.unreconciled(50)).extracting(CourierPayable::getId).contains(p.getId());

        payableService.resolveReconciliation(p.getId(), "user:5", "Offset against the next invoice");
        assertThat(payables.findById(p.getId()).orElseThrow().getReconciliationState()).isEqualTo("RESOLVED");
    }

    // ── never overcharge a supplier ──────────────────────────────────────

    private long supplierCharge(DeliveryFixture.Setup s, String amount, String status) {
        jdbc.update("""
                insert into supplier_deduction (supplier_store_id, supplier_order_id, dispute_refund_id, amount, reason,
                                                status, kind, delivery_id)
                values (?, ?, null, ?, 'Costonomy delivery', ?, 'DELIVERY_FUNDING', ?)
                """, s.storeId(), s.orderId(), new BigDecimal(amount), status, s.deliveryId());
        return jdbc.queryForObject("select id from supplier_deduction where delivery_id = ?", Long.class, s.deliveryId());
    }

    private CourierPayable deliveredFunded(DeliveryFixture.Setup s, String fee) {
        attempt(s.deliveryId(), 1, API, "BOOKED", "ovc-" + s.deliveryId(), fee, null);
        delivery(s.deliveryId(), "DELIVERED", API, "ovc-" + s.deliveryId(), fee, true, "COSTONOMY");
        sync.sync(s.deliveryId());
        return only(s.deliveryId());
    }

    @Test
    @DisplayName("a supplier charge not yet taken is reduced when the provider's final bill is lower")
    void pendingChargeIsReducedToTheFinalCost() throws Exception {
        var s = order("0.00", "80.00");
        var p = deliveredFunded(s, "80.00");
        long charge = supplierCharge(s, "80.00", "PENDING");

        payableService.setFinalCost(p.getId(), new BigDecimal("60.00"), "user:8", "Provider invoice");

        assertThat(jdbc.queryForObject("select amount from supplier_deduction where id = ?", BigDecimal.class, charge))
                .isEqualByComparingTo("60.00");
        assertThat(only(s.deliveryId()).getSupplierContribution()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a supplier charge not yet taken is waived if the share turns out to be nothing")
    void pendingChargeIsWaivedWhenTheShareIsNothing() throws Exception {
        var s = order("0.00", "80.00");
        var p = deliveredFunded(s, "80.00");
        long charge = supplierCharge(s, "80.00", "PENDING");

        payableService.setFinalCost(p.getId(), BigDecimal.ZERO, "user:8", "Provider waived the whole fare");

        assertThat(jdbc.queryForObject("select status from supplier_deduction where id = ?", String.class, charge))
                .isEqualTo("WAIVED");
    }

    @Test
    @DisplayName("a charge already taken from a payout is corrected by a credit for the excess, once")
    void takenChargeIsCorrectedByACredit() throws Exception {
        var s = order("0.00", "80.00");
        var p = deliveredFunded(s, "80.00");
        long charge = supplierCharge(s, "80.00", "APPLIED");

        payableService.setFinalCost(p.getId(), new BigDecimal("60.00"), "user:8", "Provider invoice");
        payableService.setFinalCost(p.getId(), new BigDecimal("60.00"), "user:8", "Same figure again");

        var credits = jdbc.queryForList("select kind, status, amount, corrects_deduction_id from supplier_deduction "
                + "where corrects_deduction_id = ?", charge);
        assertThat(credits).hasSize(1);
        assertThat(credits.get(0)).containsEntry("kind", "DELIVERY_FUNDING_CREDIT").containsEntry("status", "PENDING");
        assertThat((BigDecimal) credits.get(0).get("amount")).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("select amount from supplier_deduction where id = ?", BigDecimal.class, charge))
                .as("the original charge is a record of what happened, not rewritten").isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("voiding is refused once money was paid, and needs a reason")
    void voidRules() throws Exception {
        var p = dueOnApiRail("80.00");

        assertThatThrownBy(() -> payableService.voidPayable(p.getId(), "user:6", " ")).isInstanceOf(BusinessException.class);
        payments.pay(p.getId());
        assertThatThrownBy(() -> payableService.voidPayable(p.getId(), "user:6", "Mistake"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("credit");
    }

    @Test
    @DisplayName("the job pass syncs deliveries, then pays what is due")
    void jobPassSyncsAndPays() throws Exception {
        var s = order("0.00", "0.00");
        attempt(s.deliveryId(), 1, API, "BOOKED", "job-1", "80.00", null);
        delivery(s.deliveryId(), "PICKED_UP", API, "job-1", "80.00", true, "COSTONOMY");
        // Not synced by anyone: the pass finds it. The cursor reads only rows older than the settle lag (so a late commit is
        // still seen); the test takes the lag away so a row made a moment ago is in reach.
        var lag = props.getSyncSettleLag();
        props.setSyncSettleLag(Duration.ZERO);
        try {
            var pass = jobs().runOnce();

            assertThat(pass.synced()).isGreaterThanOrEqualTo(1);
            assertThat(only(s.deliveryId()).getState()).isEqualTo(CourierPayableState.PAID);
        } finally {
            props.setSyncSettleLag(lag);
        }
    }

    @Autowired private com.costonomy.mp.delivery.service.CourierPayableJobs jobsBean;

    private com.costonomy.mp.delivery.service.CourierPayableJobs jobs() {
        return jobsBean;
    }
}
