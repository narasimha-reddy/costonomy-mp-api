package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Reserve, utilize and release. Doc 01 §19, doc 03 §9.
 *
 * <p>The lifecycle mirrors the order's, because the order is the thing whose fate
 * decides the credit's: placed → reserved, accepted → utilized, rejected or timed
 * out → released, partially accepted → the accepted value utilized and the
 * remainder released.
 *
 * <p><b>Every exposure change goes through {@link CreditExposureStore} and is
 * written to {@link CreditLedger} in the same transaction.</b> A balance that
 * moved without a ledger row is a number nobody can explain later, which is the
 * state doc 09 §11 exists to prevent — so the two are never separated, and
 * neither is ever done by hand elsewhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditLedgerService {

    private final CreditAgreementRepository agreements;
    private final CreditReservationRepository reservations;
    private final CreditExposureStore exposure;
    private final CreditLedger ledger;
    private final CreditInvoiceService invoices;
    private final OutboxService outbox;

    /**
     * Hold the order's value against the agreement.
     *
     * <p>Called during submission, before the supplier can see the order: credit
     * is this payment method's equivalent of an authorisation, and guardrail 16
     * applies to it identically.
     *
     * @return the reservation, whose status says whether the hold succeeded
     */
    @Transactional
    public CreditReservation reserve(Long agreementId, Long supplierOrderId,
                                     Long procurementId, BigDecimal amount) {

        // A retry of a submission that already held credit. Returning the existing
        // reservation is the true answer; holding a second time would double the
        // exposure for one order.
        var existing = reservations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            return existing;
        }

        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT_AGREEMENT_NOT_ACTIVE));

        var reservation = new CreditReservation();
        reservation.setCreditAgreementId(agreementId);
        reservation.setSupplierOrderId(supplierOrderId);
        reservation.setProcurementId(procurementId);
        reservation.setReservedAmount(amount);

        // Checked before the limit, and separately from it: a supplier who caps
        // single orders at ₹50,000 is saying something different from one whose
        // limit happens to be low today, and the restaurant needs to be told which
        // wall they hit (doc 01 §18).
        if (agreement.getMaxSingleOrderCredit() != null
                && amount.compareTo(agreement.getMaxSingleOrderCredit()) > 0) {
            return fail(reservation, ErrorCode.CREDIT_SINGLE_ORDER_CAP_EXCEEDED.name(),
                    "This order is above the per-order credit limit for this supplier.");
        }

        if (agreement.getStatus() == CreditAgreementStatus.SUSPENDED) {
            return fail(reservation, ErrorCode.CREDIT_SUSPENDED.name(),
                    "Credit with this supplier is suspended.");
        }
        if (!agreement.getStatus().canFund()) {
            return fail(reservation, ErrorCode.CREDIT_AGREEMENT_NOT_ACTIVE.name(),
                    "Credit with this supplier isn't active.");
        }

        // The conditional UPDATE decides. Not the read above — between that read
        // and this statement the limit may have been consumed by a concurrent
        // order or withdrawn by the supplier, and only the database can settle it.
        if (!exposure.reserve(agreementId, amount)) {
            return fail(reservation, ErrorCode.CREDIT_LIMIT_EXCEEDED.name(),
                    "Not enough credit available with this supplier.");
        }

        reservation.setStatus(CreditReservationStatus.RESERVED);
        reservation.setReservedAt(Instant.now());
        reservations.save(reservation);

        ledger.record(agreementId, CreditTransactionType.RESERVE, amount, reservation.getId(),
                supplierOrderId, null, "Reserved for order " + supplierOrderId, null);

        outbox.publish("CreditReserved", "CREDIT_AGREEMENT", agreementId,
                Map.of("supplierOrderId", supplierOrderId,
                        "amount", amount.toPlainString()),
                null);

        return reservation;
    }

    /**
     * The supplier committed to {@code acceptedAmount}. Draw that, give back the rest.
     *
     * <p>Doc 01 §19's partial-acceptance rule, and the reason a reservation holds
     * the whole order value up front: what the supplier will accept is not known
     * when the order is placed, so the full amount is held and the difference
     * returned once it is.
     *
     * <p>Raises the invoice too, since utilization is exactly the moment something
     * becomes owed — an order that was never accepted owes nothing.
     */
    @Transactional
    public void utilize(Long supplierOrderId, BigDecimal acceptedAmount) {
        var reservation = reservations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (reservation == null || !reservation.getStatus().holdsExposure()) {
            // Not a credit order, or already resolved. Both are normal: this is
            // called from the acceptance path for every order, whatever funded it.
            return;
        }

        BigDecimal held = reservation.getReservedAmount();
        BigDecimal drawn = acceptedAmount.min(held);
        BigDecimal returned = held.subtract(drawn);

        if (!exposure.utilize(reservation.getCreditAgreementId(), held, drawn)) {
            // The agreement's reserved balance no longer covers this hold, which
            // means the ledger and the reservation disagree. Refusing loudly is
            // right: silently continuing would make the exposure wrong in a way
            // that compounds with every later order.
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Credit balances changed while this order was being confirmed.");
        }

        reservation.setStatus(CreditReservationStatus.UTILIZED);
        reservation.setUtilizedAmount(drawn);
        reservation.setReleasedAmount(returned);
        reservation.setUtilizedAt(Instant.now());
        reservations.save(reservation);

        ledger.record(reservation.getCreditAgreementId(), CreditTransactionType.UTILIZE, drawn,
                reservation.getId(), supplierOrderId, null,
                "Drawn on acceptance of order " + supplierOrderId, null);

        if (returned.signum() > 0) {
            // Logged as its own movement rather than netted into the UTILIZE row.
            // A restaurant looking at why ₹4,000 became ₹2,400 needs to see the
            // ₹1,600 going back, not infer it from a smaller number.
            ledger.record(reservation.getCreditAgreementId(), CreditTransactionType.RELEASE, returned,
                    reservation.getId(), supplierOrderId, null,
                    "Unaccepted balance returned", null);
        }

        if (drawn.signum() > 0) {
            invoices.issueFor(reservation, drawn);
        }

        outbox.publish("CreditUtilized", "CREDIT_AGREEMENT", reservation.getCreditAgreementId(),
                Map.of("supplierOrderId", supplierOrderId,
                        "amount", drawn.toPlainString(),
                        "released", returned.toPlainString()),
                null);
    }

    /** The order will never be supplied. Give the whole hold back. */
    @Transactional
    public void release(Long supplierOrderId, String reason) {
        var reservation = reservations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (reservation == null || !reservation.getStatus().holdsExposure()) {
            return;
        }

        BigDecimal held = reservation.getReservedAmount();
        if (!exposure.release(reservation.getCreditAgreementId(), held)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Credit balances changed while this order was being released.");
        }

        reservation.setStatus(CreditReservationStatus.RELEASED);
        reservation.setReleasedAmount(held);
        reservation.setReleasedAt(Instant.now());
        reservation.setFailureReason(reason);
        reservations.save(reservation);

        ledger.record(reservation.getCreditAgreementId(), CreditTransactionType.RELEASE, held,
                reservation.getId(), supplierOrderId, null, reason, null);

        outbox.publish("CreditReleased", "CREDIT_AGREEMENT", reservation.getCreditAgreementId(),
                Map.of("supplierOrderId", supplierOrderId,
                        "amount", held.toPlainString(), "reason", reason),
                null);
    }

    private CreditReservation fail(CreditReservation reservation, String code, String reason) {
        reservation.setStatus(CreditReservationStatus.FAILED);
        reservation.setFailureCode(code);
        reservation.setFailureReason(reason);
        log.info("Credit reservation failed for order {}: {}",
                reservation.getSupplierOrderId(), code);
        return reservations.save(reservation);
    }
}
