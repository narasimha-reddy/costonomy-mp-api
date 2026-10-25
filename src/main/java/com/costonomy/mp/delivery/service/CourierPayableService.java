package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableEvent;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.repository.CourierPayableEventRepository;
import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What Costonomy owes the delivery providers (D-204).
 *
 * <p><b>The payable follows the delivery, and is derived from it.</b> {@link #sync} takes the facts of one delivery and
 * brings its payables into line: one per booking attempt that reached the provider. It is idempotent, so it is called
 * from every place a delivery moves and again by a sweep; whichever sees a change first records it, and a missed call is
 * repaired by the next.
 *
 * <p><b>Who bears what.</b> The restaurant's part and the supplier's part are fixed by what each agreed to at checkout and
 * are never raised afterwards; the supplier's part is also never more than the courier actually cost. The platform bears
 * the rest: a final cost above the quote, a cancellation fee, a failed pickup, a courier that ran for nothing. Where the
 * final cost is below what was collected, the platform keeps the difference. Nothing here changes what a customer or a
 * supplier was charged.
 *
 * <p><b>When money is owed.</b> Nothing while a courier is merely booked ({@code ACCRUED}). The final cost once the goods
 * are with the courier, whether or not they arrive. A cancellation fee, if the provider charges one. Nothing, ever, for a
 * courier cancelled free of charge ({@code VOID}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourierPayableService {

    static final String SYSTEM = "SYSTEM";

    private final CourierPayableRepository payables;
    private final CourierPayableEventRepository events;
    private final DeliveryProviderRepository providers;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    // ── sync from a delivery ───────────────────────────────────────────────

    /** Bring the payables of one delivery into line with what happened to it. Safe to repeat. */
    @Transactional
    public void sync(CourierFacts f) {
        if (f == null || f.attempts() == null) {
            return;
        }
        var booked = f.attempts().stream()
                .filter(a -> a.providerDeliveryId() != null && a.providerCode() != null && a.attemptId() != null)
                .toList();
        if (booked.isEmpty()) {
            return;
        }
        // The last courier that actually carried the goods gets the customer contributions; any earlier one that also
        // carried them (a failed delivery re-attempted by another courier) is wholly the platform's cost.
        Long lastRan = null;
        for (var a : booked) {
            if (ran(a, f)) {
                lastRan = a.attemptId();
            }
        }
        for (var a : booked) {
            apply(a, f, ran(a, f), a.attemptId().equals(lastRan));
        }
    }

    private static boolean isCurrent(CourierFacts.Attempt a, CourierFacts f) {
        return f.currentProviderDeliveryId() != null && f.currentProviderDeliveryId().equals(a.providerDeliveryId());
    }

    /** Did this courier have the goods? The current one by the delivery's state; an ended one by why it ended. */
    private static boolean ran(CourierFacts.Attempt a, CourierFacts f) {
        if (isCurrent(a, f)) {
            return f.goodsWithCourier();
        }
        return "DELIVERY_FAILED".equals(a.endedCause()) || "COMPLETED".equals(a.endedCause());
    }

    private void apply(CourierFacts.Attempt a, CourierFacts f, boolean ran, boolean carriesContributions) {
        var existing = payables.findBySourceTypeAndDeliveryIdAndAttemptId(f.sourceType(), f.deliveryId(), a.attemptId());
        var p = existing.orElseGet(() -> create(a, f));
        boolean created = existing.isEmpty();
        var before = snapshot(p);

        boolean current = isCurrent(a, f);
        boolean active = current && !ran && "BOOKED".equals(a.outcome());
        BigDecimal quoted = a.quotedAmount() == null ? BigDecimal.ZERO : money(a.quotedAmount());

        // The cost: the final fee as booked for the courier the delivery is with, the quote for one that ended.
        if (!p.isFinalCostManual()) {
            if (ran) {
                p.setFinalCost(current && f.finalFee() != null ? money(f.finalFee()) : quoted);
            } else {
                p.setFinalCost(null);
            }
        }
        p.setProviderDeliveryId(a.providerDeliveryId());

        BigDecimal base = ran ? Objects.requireNonNullElse(p.getFinalCost(), quoted) : p.getCancellationFee();
        BigDecimal due = base.subtract(p.getProviderCredit());
        if (due.signum() < 0) {
            due = BigDecimal.ZERO;
        }
        p.setAmountDue(due);

        // Who funds it. Only the courier that carried the goods is funded by the customers; the supplier never pays more
        // than the courier cost. The platform is whatever is left (negative when the customer paid more than it cost).
        BigDecimal cost = ran ? due : (active ? quoted : BigDecimal.ZERO);
        BigDecimal buyer = BigDecimal.ZERO;
        BigDecimal supplier = BigDecimal.ZERO;
        if (((ran && carriesContributions) || (active && current)) && !f.customersRefunded()) {
            buyer = money(nz(f.buyerFee()));
            supplier = nz(f.supplierFunded()).min(cost);
            if (supplier.signum() < 0) {
                supplier = BigDecimal.ZERO;
            }
        }
        p.setBuyerContribution(buyer);
        p.setSupplierContribution(money(supplier));
        p.setPlatformFunded(cost.subtract(buyer).subtract(supplier));

        String desired = ran ? CourierPayableState.DUE
                : active ? CourierPayableState.ACCRUED
                : (due.signum() > 0 ? CourierPayableState.DUE : CourierPayableState.VOID);
        moveTo(p, desired, ran, current, f);

        // The supplier's charge is compared with this share by the settlement module, which knows what was charged. Told when
        // the share fell, when the payable is first made (the charge may predate it), and whenever the customers were refunded.
        if (ran && CourierPayable.SUPPLIER_DELIVERY.equals(f.sourceType())
                && (created || f.customersRefunded() || p.getSupplierContribution().compareTo(before.supplier()) < 0)) {
            publisher.publishEvent(new com.costonomy.mp.delivery.domain.SupplierShareReduced(
                    f.deliveryId(), f.supplierOrderId(), p.getSupplierContribution()));
        }
        if (!created && !snapshot(p).equals(before)) {
            payables.save(p);
            event(p, "SYNCED", before.state(), p.getState(), p.getAmountDue(), SYSTEM, null,
                    "cost " + p.getAmountDue().toPlainString() + ", buyer " + buyer.toPlainString()
                            + ", supplier " + supplier.toPlainString() + ", platform " + p.getPlatformFunded().toPlainString());
        } else if (created) {
            payables.save(p);
            event(p, "CREATED", null, p.getState(), p.getAmountDue(), SYSTEM, null,
                    "booked with " + a.providerCode() + ", quoted " + quoted.toPlainString());
        }
    }

    private void moveTo(CourierPayable p, String desired, boolean ran, boolean current, CourierFacts f) {
        String cur = p.getState();
        switch (cur) {
            case CourierPayableState.PAID -> {
                // Settled. If what is owed no longer equals what was paid, a person reconciles; the state stays PAID.
                if (p.getAmountDue().compareTo(p.getAmountPaid()) != 0) {
                    flag(p, "Amount due is now " + p.getAmountDue().toPlainString() + " but "
                            + p.getAmountPaid().toPlainString() + " was paid");
                }
            }
            case CourierPayableState.PROCESSING, CourierPayableState.AMBIGUOUS -> {
                // A payment is in flight or unknown; its outcome decides. A changed amount is flagged, not acted on.
                var last = p.getAmountDue();
                if (last.compareTo(p.getAmountPaid()) != 0 && p.getAmountPaid().signum() > 0) {
                    flag(p, "Amount due changed while a payment was in flight");
                }
            }
            case CourierPayableState.FAILED, CourierPayableState.EXHAUSTED -> {
                // Still owed and still being chased: unless it is no longer owed at all.
                if (CourierPayableState.VOID.equals(desired) && p.getAmountPaid().signum() == 0) {
                    p.setState(CourierPayableState.VOID);
                    p.setNextRetryAt(null);
                }
            }
            default -> {
                if (!cur.equals(desired) && CourierPayableState.canMove(cur, desired)) {
                    if (CourierPayableState.DUE.equals(desired)) {
                        p.setDueAt(p.getDueAt() == null ? Instant.now() : p.getDueAt());
                        p.setDueReason(dueReason(ran, current, f));
                        p.setPaymentRail(railOf(p.getProviderCode()));
                    }
                    p.setState(desired);
                    if (CourierPayableState.VOID.equals(desired) || CourierPayableState.ACCRUED.equals(desired)) {
                        p.setDueAt(null);
                        p.setDueReason(null);
                    }
                } else if (CourierPayableState.DUE.equals(cur)) {
                    p.setDueReason(dueReason(ran, current, f));
                }
            }
        }
    }

    private static String dueReason(boolean ran, boolean current, CourierFacts f) {
        if (!ran) {
            return "CANCELLATION_FEE";
        }
        if (!current) {
            return "POST_PICKUP_FAILURE";
        }
        return f.delivered() ? "DELIVERED" : f.failedAfterPickup() ? "POST_PICKUP_FAILURE" : "PICKED_UP";
    }

    private CourierPayable create(CourierFacts.Attempt a, CourierFacts f) {
        var p = new CourierPayable();
        p.setSourceType(f.sourceType());
        p.setDeliveryId(f.deliveryId());
        p.setAttemptId(a.attemptId());
        p.setSupplierOrderId(f.supplierOrderId());
        p.setOutletId(f.outletId());
        p.setSupplierStoreId(f.supplierStoreId());
        p.setProviderCode(a.providerCode());
        p.setProviderDeliveryId(a.providerDeliveryId());
        p.setCurrency(f.currency() == null ? "INR" : f.currency());
        p.setQuotedCost(a.quotedAmount() == null ? BigDecimal.ZERO : money(a.quotedAmount()));
        p.setPaymentRail(railOf(a.providerCode()));
        p.setState(CourierPayableState.ACCRUED);
        return p;
    }

    private String railOf(String providerCode) {
        return providers.findByCode(providerCode).map(r -> r.getPaymentRail()).orElse("MANUAL");
    }

    // ── adjustments and operator actions (audited) ────────────────────────────

    /** The provider charged for a cancellation. Owed even though the courier never had the goods. */
    @Transactional
    public CourierPayable recordCancellationFee(Long payableId, BigDecimal fee, String actor, String note) {
        var p = lock(payableId);
        requirePositive(fee, "A cancellation fee");
        p.setCancellationFee(money(fee));
        return recompute(p, "CANCELLATION_FEE_RECORDED", actor, note);
    }

    /** The provider credited us (a refund for a failed delivery, a billing correction). */
    @Transactional
    public CourierPayable recordProviderCredit(Long payableId, BigDecimal credit, String actor, String note) {
        var p = lock(payableId);
        requirePositive(credit, "A provider credit");
        p.setProviderCredit(money(credit));
        return recompute(p, "PROVIDER_CREDIT_RECORDED", actor, note);
    }

    /** The provider's final charge, as billed. Fixes the figure; the automatic sync no longer changes it. */
    @Transactional
    public CourierPayable setFinalCost(Long payableId, BigDecimal finalCost, String actor, String note) {
        var p = lock(payableId);
        if (finalCost == null || finalCost.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The final cost cannot be negative.");
        }
        p.setFinalCost(money(finalCost));
        p.setFinalCostManual(true);
        return recompute(p, "FINAL_COST_SET", actor, note);
    }

    /** Re-derive the due amount and the funding split after a person changed an input. */
    private CourierPayable recompute(CourierPayable p, String eventType, String actor, String note) {
        var before = snapshot(p);
        boolean ran = p.getFinalCost() != null;
        BigDecimal base = ran ? p.getFinalCost() : p.getCancellationFee();
        BigDecimal due = base.subtract(p.getProviderCredit());
        p.setAmountDue(due.signum() < 0 ? BigDecimal.ZERO : due);
        if (ran) {
            BigDecimal supplier = p.getSupplierContribution().min(p.getAmountDue());
            p.setSupplierContribution(supplier);
            p.setPlatformFunded(p.getAmountDue().subtract(p.getBuyerContribution()).subtract(supplier));
        } else {
            p.setPlatformFunded(p.getAmountDue());
        }
        if (ran && p.getSupplierContribution().compareTo(before.supplier()) < 0
                && CourierPayable.SUPPLIER_DELIVERY.equals(p.getSourceType())) {
            publisher.publishEvent(new com.costonomy.mp.delivery.domain.SupplierShareReduced(
                    p.getDeliveryId(), p.getSupplierOrderId(), p.getSupplierContribution()));
        }
        String cur = p.getState();
        if (p.getAmountDue().signum() > 0 && (CourierPayableState.VOID.equals(cur) || CourierPayableState.ACCRUED.equals(cur))) {
            p.setState(CourierPayableState.DUE);
            p.setDueAt(Instant.now());
            p.setDueReason(ran ? "MANUAL" : "CANCELLATION_FEE");
        }
        if (CourierPayableState.PAID.equals(cur) || p.getAmountPaid().signum() > 0) {
            if (p.getAmountDue().compareTo(p.getAmountPaid()) != 0) {
                flag(p, "Amount due is now " + p.getAmountDue().toPlainString() + " but "
                        + p.getAmountPaid().toPlainString() + " was paid");
            }
        } else if (p.getAmountDue().signum() == 0 && !CourierPayableState.VOID.equals(p.getState())
                && !CourierPayableState.ACCRUED.equals(p.getState())) {
            p.setState(CourierPayableState.VOID);
            p.setNextRetryAt(null);
        }
        payables.save(p);
        event(p, eventType, before.state(), p.getState(), p.getAmountDue(), actor, null, note);
        return p;
    }

    /** A person pays by bank transfer or against the provider's invoice and records it. */
    @Transactional
    public CourierPayable markPaid(Long payableId, BigDecimal amount, String reference, Instant paidAt, String actor,
                                   String note) {
        var p = lock(payableId);
        if (CourierPayableState.PROCESSING.equals(p.getState())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "A payment of this is being sent right now; wait for its outcome.");
        }
        if (CourierPayableState.VOID.equals(p.getState()) || CourierPayableState.ACCRUED.equals(p.getState())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "Nothing is owed on this yet.");
        }
        if (reference == null || reference.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give the bank or invoice reference of the payment.");
        }
        requirePositive(amount, "A payment");
        if (amount.compareTo(p.balance()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That is more than the %s still owed.".formatted(p.balance().toPlainString()));
        }
        var before = snapshot(p);
        p.setAmountPaid(p.getAmountPaid().add(money(amount)));
        p.setPaymentReference(reference);
        p.setPaymentRail("MANUAL");
        p.setClaimToken(null);
        p.setClaimExpiresAt(null);
        p.setNextRetryAt(null);
        if (p.getAmountPaid().compareTo(p.getAmountDue()) >= 0) {
            p.setState(CourierPayableState.PAID);
            p.setPaidAt(paidAt == null ? Instant.now() : paidAt);
        } else {
            p.setState(CourierPayableState.DUE);
        }
        payables.save(p);
        event(p, "MANUAL_PAYMENT_RECORDED", before.state(), p.getState(), amount, actor, reference, note);
        return p;
    }

    /** Put a stuck payable back to DUE so the job (or a person) tries again. */
    @Transactional
    public CourierPayable retry(Long payableId, String actor, String note) {
        var p = lock(payableId);
        String cur = p.getState();
        if (!(CourierPayableState.EXHAUSTED.equals(cur) || CourierPayableState.FAILED.equals(cur))) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Only a failed or exhausted payable can be retried; this one is " + cur + ".");
        }
        var before = snapshot(p);
        p.setState(CourierPayableState.DUE);
        p.setAttemptCount(0);
        p.setNextRetryAt(null);
        p.setLastError(null);
        payables.save(p);
        event(p, "RETRY_REQUESTED", before.state(), p.getState(), p.getAmountDue(), actor, null, note);
        return p;
    }

    /** Nothing is owed after all. Refused once any money has been paid. */
    @Transactional
    public CourierPayable voidPayable(Long payableId, String actor, String reason) {
        var p = lock(payableId);
        if (p.getAmountPaid().signum() > 0) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Money has been paid on this; record a provider credit instead of voiding it.");
        }
        if (CourierPayableState.PROCESSING.equals(p.getState()) || CourierPayableState.AMBIGUOUS.equals(p.getState())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "A payment may be in flight; settle its outcome first.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say why nothing is owed.");
        }
        var before = snapshot(p);
        p.setState(CourierPayableState.VOID);
        p.setAmountDue(BigDecimal.ZERO);
        p.setNextRetryAt(null);
        payables.save(p);
        event(p, "VOIDED", before.state(), p.getState(), BigDecimal.ZERO, actor, null, reason);
        return p;
    }

    /** A person decides an ambiguous payment: it did go (record it) or it did not (try again). */
    @Transactional
    public CourierPayable resolveAmbiguous(Long payableId, boolean paid, String reference, String actor, String note) {
        var p = lock(payableId);
        if (!CourierPayableState.AMBIGUOUS.equals(p.getState())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "This payment is not ambiguous.");
        }
        var before = snapshot(p);
        if (paid) {
            if (reference == null || reference.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give the reference the payment went under.");
            }
            p.setAmountPaid(p.getAmountDue());
            p.setPaymentReference(reference);
            p.setPaidAt(Instant.now());
            p.setState(CourierPayableState.PAID);
        } else {
            p.setState(CourierPayableState.DUE);
            p.setNextRetryAt(null);
        }
        p.setReconciliationState("RESOLVED");
        p.setReconciliationNote(note);
        p.setClaimToken(null);
        p.setClaimExpiresAt(null);
        payables.save(p);
        event(p, paid ? "AMBIGUOUS_RESOLVED_PAID" : "AMBIGUOUS_RESOLVED_NOT_PAID", before.state(), p.getState(),
                p.getAmountDue(), actor, reference, note);
        return p;
    }

    @Transactional
    public CourierPayable resolveReconciliation(Long payableId, String actor, String note) {
        var p = lock(payableId);
        if (!"REQUIRED".equals(p.getReconciliationState())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "Nothing to reconcile on this payable.");
        }
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say how it was reconciled.");
        }
        p.setReconciliationState("RESOLVED");
        p.setReconciliationNote(note);
        payables.save(p);
        event(p, "RECONCILED", p.getState(), p.getState(), p.getAmountDue(), actor, null, note);
        return p;
    }

    // ── reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CourierPayable get(Long id) {
        return payables.findById(id).orElseThrow(() -> new NotFoundException("CourierPayable", id));
    }

    @Transactional(readOnly = true)
    public List<CourierPayableEvent> history(Long payableId) {
        return events.findByPayableIdOrderByIdAsc(payableId);
    }

    @Transactional(readOnly = true)
    public List<CourierPayable> forDelivery(String sourceType, Long deliveryId) {
        return payables.findBySourceTypeAndDeliveryIdOrderByIdAsc(sourceType, deliveryId);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    long countEvents(Long payableId, String eventType) {
        return events.countByPayableIdAndEventType(payableId, eventType);
    }

    CourierPayable lock(Long id) {
        return payables.lockById(id).orElseThrow(() -> new NotFoundException("CourierPayable", id));
    }

    void flag(CourierPayable p, String note) {
        if (!"REQUIRED".equals(p.getReconciliationState())) {
            p.setReconciliationState("REQUIRED");
            p.setReconciliationNote(note);
        }
    }

    void event(CourierPayable p, String type, String from, String to, BigDecimal amount, String actor, String reference,
               String detail) {
        var e = new CourierPayableEvent();
        e.setPayableId(p.getId());
        e.setEventType(type);
        e.setFromState(from);
        e.setToState(to);
        e.setAmount(amount);
        e.setActor(actor == null ? SYSTEM : actor);
        e.setReference(reference);
        e.setDetail(detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail));
        events.save(e);
    }

    private record Snapshot(String state, BigDecimal due, BigDecimal paid, BigDecimal finalCost, BigDecimal buyer,
                            BigDecimal supplier, BigDecimal platform, String recon) {
    }

    private static Snapshot snapshot(CourierPayable p) {
        return new Snapshot(p.getState(), strip(p.getAmountDue()), strip(p.getAmountPaid()), strip(p.getFinalCost()),
                strip(p.getBuyerContribution()), strip(p.getSupplierContribution()), strip(p.getPlatformFunded()),
                p.getReconciliationState());
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void requirePositive(BigDecimal v, String what) {
        if (v == null || v.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, what + " must be more than zero.");
        }
    }
}
