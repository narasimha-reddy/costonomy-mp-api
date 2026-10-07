package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditOfferExpiry;
import com.costonomy.mp.credit.repository.CreditAgreementLockRepository;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * The ends of a credit line: a supplier closing it (D-165) and an unaccepted offer lapsing (D-166).
 *
 * <p>Both take the agreement row for update first, so the move and a concurrent order, acceptance or repayment each
 * see the other's result: an order reserves with {@code status = 'ACTIVE'} in the same statement, so it cannot slip in
 * after a close, and a close that finds credit already held refuses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditLifecycleService {

    private final CreditAgreementRepository agreements;
    private final CreditAgreementLockRepository locks;
    private final CreditAgreementService agreementService;
    private final CreditInvoiceService invoiceService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── Close ────────────────────────────────────────────────────────────

    /**
     * Close a line for good. {@code ACTIVE} or {@code SUSPENDED → CLOSED}, only when nothing is owed and nothing is
     * held for an order in flight: a supplier who is still owed money suspends the line instead and closes it once it
     * is paid. Closing again is a retry and answers the same.
     */
    @Transactional
    public CreditDtos.AgreementResponse close(Long actorId, Long agreementId, String reason) {
        // Locked before anything else reads the row; the access check follows and a refusal is a 404 like the rest.
        var agreement = locks.lockById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        accessControl.requireScoped(actorId, Permissions.CREDIT_MODIFY, ScopeType.SUPPLIER_STORE,
                agreement.getSupplierStoreId(), "CreditAgreement");

        if (agreement.getStatus() == CreditAgreementStatus.CLOSED) {
            return agreementService.toResponse(agreement);
        }
        if (!agreement.getStatus().canTransitionTo(CreditAgreementStatus.CLOSED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A line that is " + agreement.getStatus() + " can't be closed. "
                            + (agreement.getStatus() == CreditAgreementStatus.REQUESTED
                            || agreement.getStatus() == CreditAgreementStatus.APPROVED
                            ? "Decline the request or withdraw the offer instead." : ""));
        }

        BigDecimal owed = agreement.getUtilizedAmount().max(invoiceService.duesFor(agreementId).due());
        if (owed.signum() > 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "₹%s is still owed on this line. Suspend it to stop new orders, and close it once it is paid."
                            .formatted(Rupees.of(owed)),
                    Map.of("owed", owed, "reserved", agreement.getReservedAmount()));
        }
        if (agreement.getReservedAmount().signum() > 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "₹%s is on hold for orders that are still in flight. Close the line once they are settled."
                            .formatted(Rupees.of(agreement.getReservedAmount())),
                    Map.of("owed", owed, "reserved", agreement.getReservedAmount()));
        }

        var before = agreement.getStatus();
        agreement.setStatus(CreditAgreementStatus.CLOSED);
        agreement.setClosedAt(Instant.now());
        agreement.setSuspendedAt(null);
        agreement.setSuspensionReason(null);
        agreement.setSuspensionSource(null);
        agreement.setOverdueFloor(null);
        agreements.save(agreement);

        auditService.record(actorId, null, "CREDIT_CLOSED", "CREDIT_AGREEMENT", agreementId,
                before.name(), CreditAgreementStatus.CLOSED.name(), reason.trim(), "API");
        outbox.publish(CreditEvents.CLOSED, "CREDIT_AGREEMENT", agreementId,
                Map.of("creditAgreementId", agreementId,
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "supplierName", supplierNameOf(agreement.getSupplierStoreId()),
                        "reason", reason.trim()),
                actorId);
        return agreementService.toResponse(agreement);
    }

    // ── Offer expiry ─────────────────────────────────────────────────────

    /**
     * Lapse one offer the restaurant has not accepted for 14 India days (D-166), in its own transaction so one
     * failure leaves the rest of the sweep alone. The row is taken first and everything is re-checked on it: an
     * acceptance that got there first leaves an ACTIVE line, which this skips; one that comes second finds EXPIRED
     * and is refused. Exactly one of the two wins and the loser changes and announces nothing.
     *
     * @return true if this call expired the offer
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOffer(Long agreementId, LocalDate today) {
        var agreement = locks.lockById(agreementId).orElse(null);
        if (agreement == null || agreement.getStatus() != CreditAgreementStatus.APPROVED
                || !offerHasExpired(agreement, today)) {
            return false;
        }

        agreement.setStatus(CreditAgreementStatus.EXPIRED);
        agreement.setOfferMadeAt(null);
        agreements.save(agreement);

        auditService.record(null, null, "CREDIT_OFFER_EXPIRED", "CREDIT_AGREEMENT", agreementId,
                CreditAgreementStatus.APPROVED.name(), CreditAgreementStatus.EXPIRED.name(),
                "Not accepted within %d days".formatted(CreditOfferExpiry.DAYS), "SYSTEM");
        var outlet = directory.outlet(agreement.getOutletId());
        outbox.publish(CreditEvents.OFFER_EXPIRED, "CREDIT_AGREEMENT", agreementId,
                Map.of("creditAgreementId", agreementId,
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "supplierName", supplierNameOf(agreement.getSupplierStoreId()),
                        "restaurantName", outlet == null || outlet.restaurantName() == null ? "" : outlet.restaurantName()),
                null);
        log.info("Credit offer {} expired unaccepted", agreementId);
        return true;
    }

    /** Whether an APPROVED agreement's offer is past its 14 India days as of {@code today}. */
    public boolean offerHasExpired(CreditAgreement agreement, LocalDate today) {
        // An offer from before the timestamp existed is dated from its last update (V84 backfills the same).
        Instant offeredAt = agreement.getOfferMadeAt() != null ? agreement.getOfferMadeAt() : agreement.getUpdatedAt();
        return CreditOfferExpiry.expired(offeredAt, today, invoiceService.zone());
    }

    private String supplierNameOf(Long supplierStoreId) {
        var store = directory.store(supplierStoreId);
        return store == null || store.supplierName() == null ? "" : store.supplierName();
    }
}
