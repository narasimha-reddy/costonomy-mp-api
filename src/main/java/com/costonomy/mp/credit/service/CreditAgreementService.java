package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.*;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The credit negotiation, and the agreement it produces. Doc 01 §18, doc 03 §8,
 * doc 04 §13.
 *
 * <p><b>Credit is supplier-funded and supplier-controlled.</b> Every decision here
 * belongs to the supplier: they set the limit, the period, the per-order cap and
 * whether to suspend. Mandi runs the workflow and the ledger and does not fund,
 * guarantee or own any of it. Nothing in this class should ever grant credit on a
 * supplier's behalf, including by defaulting a term they did not set.
 *
 * <p>The request and the agreement are created together, which needs saying
 * because the API has endpoints for both. Doc 03 §8 starts the agreement's
 * lifecycle at {@code REQUESTED}, so the agreement <em>is</em> the request's
 * subject — {@code credit_request} records what was asked and what was said back,
 * over what may be several rounds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditAgreementService {

    private final CreditAgreementRepository agreements;
    private final CreditRequestRepository requests;
    private final CreditLimitHistoryRepository limitHistory;
    private final CreditTransactionRepository transactions;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceService invoiceService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    // ── The restaurant's side ────────────────────────────────────────────

    /** Ask a supplier store for credit at an outlet. Doc 04 §13. */
    @Transactional
    public CreditDtos.AgreementResponse request(Long actorId, CreditDtos.CreateRequest body) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_REQUEST,
                ScopeType.OUTLET, body.outletId(), "Outlet");

        var store = directory.store(body.supplierStoreId());
        if (store == null) {
            throw new NotFoundException("SupplierStore", body.supplierStoreId());
        }
        var outlet = directory.outlet(body.outletId());

        var policy = directory.creditPolicy(body.supplierStoreId());
        if (!policy.creditEnabled()) {
            // Refused at the door rather than left to be rejected later. A supplier
            // who does not offer credit should not have to decline every request,
            // and the restaurant should not wait days to learn it was never on offer.
            throw new BusinessException(ErrorCode.CREDIT_AGREEMENT_NOT_ACTIVE,
                    "This supplier doesn't offer credit terms.");
        }

        var agreement = agreements
                .findByOutletIdAndSupplierStoreId(body.outletId(), body.supplierStoreId())
                .orElseGet(() -> {
                    var fresh = new CreditAgreement();
                    fresh.setOutletId(body.outletId());
                    fresh.setRestaurantId(outlet.restaurantId());
                    fresh.setSupplierStoreId(body.supplierStoreId());
                    fresh.setSupplierOrganizationId(store.supplierOrganizationId());
                    return fresh;
                });

        if (agreement.getStatus().canFund()) {
            // Asking again while credit is live would be a request to *change* the
            // limit, which is the supplier's call to make through modify().
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "You already have credit with this supplier. Ask them to review the limit.");
        }
        if (agreement.getId() != null && agreement.getStatus() == CreditAgreementStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A request to this supplier is already waiting for a response.");
        }

        agreement.setStatus(CreditAgreementStatus.REQUESTED);
        agreement.setSuspensionReason(null);
        agreements.save(agreement);

        var request = new CreditRequest();
        request.setCreditAgreementId(agreement.getId());
        request.setOutletId(body.outletId());
        request.setSupplierStoreId(body.supplierStoreId());
        request.setRequestedLimit(body.requestedLimit());
        request.setRequestedPeriodDays(body.requestedDays());
        request.setPurpose(body.purpose());
        request.setNote(body.note());
        request.setRequestedBy(actorId);
        requests.save(request);

        auditService.record(actorId, null, "CREDIT_REQUESTED", "CREDIT_AGREEMENT",
                agreement.getId(), null, CreditAgreementStatus.REQUESTED.name(),
                "%s for %d days".formatted(body.requestedLimit(), body.requestedDays()), "API");

        outbox.publish("CreditRequested", "CREDIT_AGREEMENT", agreement.getId(),
                Map.of("supplierStoreId", body.supplierStoreId(),
                        "outletId", body.outletId(),
                        "requestedLimit", body.requestedLimit().toPlainString()),
                actorId);

        return toResponse(agreement, request);
    }

    /**
     * The restaurant accepts terms the supplier changed. {@code APPROVED → ACTIVE}.
     *
     * <p>This step exists because doc 03 §8 has {@code APPROVED → ACTIVE} as its own
     * transition and doc 05 §20 lists "approved with modified terms" as its own
     * status. Credit on terms nobody agreed to is not credit — if a supplier halves
     * the limit and doubles the period, the restaurant may no longer want it, and
     * activating it for them would place orders against an arrangement they never
     * accepted.
     */
    @Transactional
    public CreditDtos.AgreementResponse accept(Long actorId, Long agreementId) {
        var agreement = loadForRestaurant(actorId, agreementId, Permissions.CREDIT_REQUEST);

        if (agreement.getStatus().canFund()) {
            return toResponse(agreement, latestRequest(agreementId));
        }
        if (agreement.getStatus() != CreditAgreementStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "There are no approved terms to accept.");
        }

        activate(agreement, actorId, "Terms accepted by the restaurant");
        return toResponse(agreement, latestRequest(agreementId));
    }

    // ── The supplier's side ──────────────────────────────────────────────

    /**
     * Approve, on the asked-for terms or on different ones.
     *
     * <p>Approving unchanged activates the agreement immediately — there is nothing
     * for the restaurant to consider. Changing any term leaves it {@code APPROVED}
     * until they accept, which is what makes a modification explicit rather than
     * something a restaurant discovers at a checkout.
     */
    @Transactional
    public CreditDtos.AgreementResponse approve(Long actorId, Long agreementId,
                                                CreditDtos.ApproveRequest body) {

        var agreement = loadForSupplier(actorId, agreementId, Permissions.CREDIT_APPROVE);
        var request = latestRequest(agreementId);

        if (agreement.getStatus().canFund()) {
            return toResponse(agreement, request);
        }
        if (agreement.getStatus() != CreditAgreementStatus.REQUESTED
                && agreement.getStatus() != CreditAgreementStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This request can't be approved from " + agreement.getStatus() + ".");
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "There is no credit request to approve.");
        }

        var policy = directory.creditPolicy(agreement.getSupplierStoreId());

        BigDecimal limit = body.approvedLimit() == null
                ? request.getRequestedLimit() : body.approvedLimit();
        int periodDays = body.creditPeriodDays() == null
                ? request.getRequestedPeriodDays() : body.creditPeriodDays();
        int graceDays = body.gracePeriodDays() == null
                ? policy.defaultGraceDays() : body.gracePeriodDays();

        boolean modified = limit.compareTo(request.getRequestedLimit()) != 0
                || periodDays != request.getRequestedPeriodDays();

        BigDecimal previousLimit = agreement.getApprovedLimit();
        Integer previousPeriod = agreement.getCreditPeriodDays();

        agreement.setApprovedLimit(limit);
        agreement.setCreditPeriodDays(periodDays);
        agreement.setGracePeriodDays(graceDays);
        agreement.setMaxSingleOrderCredit(body.maxSingleOrderCredit() != null
                ? body.maxSingleOrderCredit() : policy.maxSingleOrderCredit());
        agreement.setMaxOverdueAmount(body.maxOverdueAmount() != null
                ? body.maxOverdueAmount() : policy.maxOverdueAmount());
        agreement.setAutoSuspendEnabled(policy.autoSuspendEnabled());
        agreement.setEffectiveFrom(body.effectiveFrom());
        agreement.setReviewDate(body.reviewDate());

        request.setStatus(modified ? CreditRequestStatus.MODIFIED : CreditRequestStatus.APPROVED);
        request.setResponseNote(body.note());
        request.setRespondedBy(actorId);
        request.setRespondedAt(Instant.now());
        requests.save(request);

        recordTerms(agreement, previousLimit, previousPeriod, actorId,
                modified ? "MODIFICATION" : "APPROVAL",
                modified ? "Approved on modified terms" : "Approved as requested");

        if (modified) {
            agreement.setStatus(CreditAgreementStatus.APPROVED);
            agreements.save(agreement);
            outbox.publish("CreditModified", "CREDIT_AGREEMENT", agreement.getId(),
                    Map.of("outletId", agreement.getOutletId(),
                            "approvedLimit", limit.toPlainString(),
                            "creditPeriodDays", periodDays),
                    actorId);
        } else {
            activate(agreement, actorId, "Approved as requested");
        }

        auditService.record(actorId, null, "CREDIT_APPROVED", "CREDIT_AGREEMENT",
                agreement.getId(), CreditAgreementStatus.REQUESTED.name(),
                agreement.getStatus().name(),
                "%s for %d days".formatted(limit, periodDays), "API");

        return toResponse(agreement, request);
    }

    @Transactional
    public CreditDtos.AgreementResponse reject(Long actorId, Long agreementId,
                                               CreditDtos.RejectRequest body) {

        var agreement = loadForSupplier(actorId, agreementId, Permissions.CREDIT_REJECT);
        var request = latestRequest(agreementId);

        if (agreement.getStatus() == CreditAgreementStatus.REJECTED) {
            return toResponse(agreement, request);
        }
        if (!agreement.getStatus().canTransitionTo(CreditAgreementStatus.REJECTED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This request can't be rejected from " + agreement.getStatus() + ".");
        }

        agreement.setStatus(CreditAgreementStatus.REJECTED);
        agreements.save(agreement);

        if (request != null) {
            request.setStatus(CreditRequestStatus.REJECTED);
            // Passed through verbatim. "Rejected" with no reason is a dead end for
            // a restaurant who would happily have supplied whatever was missing.
            request.setResponseNote(body.reason());
            request.setRespondedBy(actorId);
            request.setRespondedAt(Instant.now());
            requests.save(request);
        }

        auditService.record(actorId, null, "CREDIT_REJECTED", "CREDIT_AGREEMENT",
                agreementId, CreditAgreementStatus.REQUESTED.name(),
                CreditAgreementStatus.REJECTED.name(), body.reason(), "API");

        outbox.publish("CreditRejected", "CREDIT_AGREEMENT", agreementId,
                Map.of("outletId", agreement.getOutletId(), "reason", body.reason()),
                actorId);

        return toResponse(agreement, request);
    }

    /**
     * Change the terms of a live agreement. Doc 01 §18.
     *
     * <p><b>A limit cannot be cut below what is already committed.</b> Reservations
     * and utilization are credit the supplier has already extended, and the
     * arithmetic doc 10 §3 requires — {@code approved = reserved + utilized +
     * available} — has no room for a limit smaller than their sum. Allowing it
     * would make {@code available} negative, and clamping that to zero would mean
     * the four numbers on a restaurant's screen no longer add up.
     *
     * <p>So the floor is the current exposure, and the error names it. A supplier
     * who wants to stop lending right now wants {@link #suspend} instead: it stops
     * new orders immediately while leaving the commitments already made intact,
     * which is the thing a limit cut cannot do without cancelling orders they have
     * already asked a restaurant to expect.
     */
    @Transactional
    public CreditDtos.AgreementResponse modify(Long actorId, Long agreementId,
                                               CreditDtos.ModifyRequest body) {

        var agreement = loadForSupplier(actorId, agreementId, Permissions.CREDIT_MODIFY);

        if (agreement.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This agreement is " + agreement.getStatus() + " and can't be changed.");
        }

        BigDecimal committed = agreement.getReservedAmount().add(agreement.getUtilizedAmount());
        if (body.approvedLimit().compareTo(committed) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    ("You've already extended %s on this account, so the limit can't go below "
                            + "that. Suspend the credit line to stop new orders.")
                            .formatted(committed.toPlainString()));
        }

        BigDecimal previousLimit = agreement.getApprovedLimit();
        Integer previousPeriod = agreement.getCreditPeriodDays();

        agreement.setApprovedLimit(body.approvedLimit());
        agreement.setCreditPeriodDays(body.creditPeriodDays());
        if (body.gracePeriodDays() != null) {
            agreement.setGracePeriodDays(body.gracePeriodDays());
        }
        agreement.setMaxSingleOrderCredit(body.maxSingleOrderCredit());
        agreement.setMaxOverdueAmount(body.maxOverdueAmount());
        agreements.save(agreement);

        recordTerms(agreement, previousLimit, previousPeriod, actorId,
                "MANUAL_ADJUSTMENT", body.reason());

        auditService.record(actorId, null, "CREDIT_MODIFIED", "CREDIT_AGREEMENT",
                agreementId, previousLimit.toPlainString(),
                body.approvedLimit().toPlainString(), body.reason(), "API");

        outbox.publish("CreditModified", "CREDIT_AGREEMENT", agreementId,
                Map.of("outletId", agreement.getOutletId(),
                        "approvedLimit", body.approvedLimit().toPlainString(),
                        "reason", body.reason()),
                actorId);

        return toResponse(agreement, latestRequest(agreementId));
    }

    /**
     * Suspend a live agreement.
     *
     * <p>Stops new orders; existing debt and reservations are untouched. Suspension
     * is a statement about new risk, not a cancellation of commitments already made
     * — a supplier who is owed money still wants the orders they accepted to be
     * delivered and paid for.
     */
    @Transactional
    public CreditDtos.AgreementResponse suspend(Long actorId, Long agreementId,
                                                CreditDtos.SuspendRequest body) {

        var agreement = loadForSupplier(actorId, agreementId, Permissions.CREDIT_MODIFY);
        suspendInternal(agreement, body.reason(), actorId);
        return toResponse(agreement, latestRequest(agreementId));
    }

    /** Lift a suspension. {@code SUSPENDED → ACTIVE}. */
    @Transactional
    public CreditDtos.AgreementResponse reinstate(Long actorId, Long agreementId) {
        var agreement = loadForSupplier(actorId, agreementId, Permissions.CREDIT_MODIFY);

        if (agreement.getStatus() == CreditAgreementStatus.ACTIVE) {
            return toResponse(agreement, latestRequest(agreementId));
        }
        if (!agreement.getStatus().canTransitionTo(CreditAgreementStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This agreement can't be reinstated from " + agreement.getStatus() + ".");
        }

        agreement.setStatus(CreditAgreementStatus.ACTIVE);
        agreement.setSuspendedAt(null);
        agreement.setSuspensionReason(null);
        agreements.save(agreement);

        auditService.recordTransition(actorId, "CREDIT_REINSTATED", "CREDIT_AGREEMENT",
                agreementId, CreditAgreementStatus.SUSPENDED.name(),
                CreditAgreementStatus.ACTIVE.name());

        return toResponse(agreement, latestRequest(agreementId));
    }

    /** Used by the overdue sweep, which has no actor. */
    @Transactional
    public void suspendInternal(CreditAgreement agreement, String reason, Long actorId) {
        if (agreement.getStatus() == CreditAgreementStatus.SUSPENDED) {
            return;
        }
        if (!agreement.getStatus().canTransitionTo(CreditAgreementStatus.SUSPENDED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This agreement can't be suspended from " + agreement.getStatus() + ".");
        }

        agreement.setStatus(CreditAgreementStatus.SUSPENDED);
        agreement.setSuspendedAt(Instant.now());
        agreement.setSuspensionReason(reason);
        agreements.save(agreement);

        auditService.record(actorId, null, "CREDIT_SUSPENDED", "CREDIT_AGREEMENT",
                agreement.getId(), CreditAgreementStatus.ACTIVE.name(),
                CreditAgreementStatus.SUSPENDED.name(), reason,
                actorId == null ? "SYSTEM" : "API");

        outbox.publish("CreditSuspended", "CREDIT_AGREEMENT", agreement.getId(),
                Map.of("outletId", agreement.getOutletId(), "reason", reason), actorId);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditDtos.AgreementResponse get(Long actorId, Long agreementId) {
        var agreement = loadForEitherSide(actorId, agreementId);
        return toResponse(agreement, latestRequest(agreementId));
    }

    @Transactional(readOnly = true)
    public List<CreditDtos.AgreementResponse> forOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");
        return agreements.findByOutletIdOrderByCreatedAtDesc(outletId).stream()
                .map(agreement -> toResponse(agreement, latestRequest(agreement.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CreditDtos.AgreementResponse> forStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_REQUEST_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");
        return agreements.findBySupplierStoreIdOrderByCreatedAtDesc(storeId).stream()
                .map(agreement -> toResponse(agreement, latestRequest(agreement.getId())))
                .toList();
    }

    /** The outlet's whole position. §23A.24 and doc 05 §19's Credit Overview. */
    @Transactional(readOnly = true)
    public CreditDtos.SummaryResponse summary(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        var responses = new ArrayList<CreditDtos.AgreementResponse>();
        var total = CreditExposure.NONE;

        for (CreditAgreement agreement : agreements.findByOutletIdOrderByCreatedAtDesc(outletId)) {
            var response = toResponse(agreement, latestRequest(agreement.getId()));
            responses.add(response);

            // Only live agreements count towards the headline. A rejected request
            // has no limit to include, and a closed one is history — adding either
            // would show a restaurant credit they cannot spend.
            if (agreement.getStatus().canFund()
                    || agreement.getStatus() == CreditAgreementStatus.SUSPENDED) {
                total = total.plus(new CreditExposure(
                        agreement.getApprovedLimit(), agreement.getReservedAmount(),
                        agreement.getUtilizedAmount(), response.due(), response.overdue()));
            }
        }

        return new CreditDtos.SummaryResponse(outletId,
                total.approvedLimit(), total.reserved(), total.utilized(), total.available(),
                total.due(), total.overdue(), responses);
    }

    @Transactional(readOnly = true)
    public List<CreditDtos.LedgerEntryResponse> ledger(Long actorId, Long agreementId) {
        loadForEitherSide(actorId, agreementId);
        return transactions.findByCreditAgreementIdOrderByCreatedAtDescIdDesc(agreementId).stream()
                .map(entry -> new CreditDtos.LedgerEntryResponse(
                        entry.getId(), entry.getTransactionType(), entry.getAmount(),
                        entry.getBalanceReservedAfter(), entry.getBalanceUtilizedAfter(),
                        entry.getBalanceAvailableAfter(), entry.getSupplierOrderId(),
                        entry.getCreditInvoiceId(), entry.getDescription(), entry.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CreditDtos.InvoiceResponse> invoicesFor(Long actorId, Long agreementId) {
        loadForEitherSide(actorId, agreementId);
        return invoices.findByCreditAgreementIdOrderByDueDateAsc(agreementId).stream()
                .map(invoice -> new CreditDtos.InvoiceResponse(
                        invoice.getId(), invoice.getInvoiceNumber(),
                        invoice.getCreditAgreementId(), invoice.getSupplierOrderId(),
                        invoice.getStatus(), invoice.getAmount(), invoice.getPaidAmount(),
                        invoice.outstanding(), invoice.getDueDate(), invoice.getOverdueAfter(),
                        invoice.getIssuedAt(), invoice.getSettledAt()))
                .toList();
    }

    /** The agreement an order may draw on, or null. Used by the funding adapter. */
    @Transactional(readOnly = true)
    public CreditAgreement fundingAgreement(Long outletId, Long supplierStoreId) {
        return agreements.findByOutletIdAndSupplierStoreId(outletId, supplierStoreId)
                .orElse(null);
    }

    // ── internals ────────────────────────────────────────────────────────

    private void activate(CreditAgreement agreement, Long actorId, String reason) {
        agreement.setStatus(CreditAgreementStatus.ACTIVE);
        agreement.setActivatedAt(Instant.now());
        agreements.save(agreement);

        auditService.record(actorId, null, "CREDIT_ACTIVATED", "CREDIT_AGREEMENT",
                agreement.getId(), CreditAgreementStatus.APPROVED.name(),
                CreditAgreementStatus.ACTIVE.name(), reason, "API");

        outbox.publish("CreditApproved", "CREDIT_AGREEMENT", agreement.getId(),
                Map.of("outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "approvedLimit", agreement.getApprovedLimit().toPlainString(),
                        "creditPeriodDays", agreement.getCreditPeriodDays()),
                actorId);
    }

    /** Append to credit_limit_history and bump the terms version. Doc 04 §13. */
    private void recordTerms(CreditAgreement agreement, BigDecimal previousLimit,
                             Integer previousPeriod, Long actorId,
                             String changeType, String reason) {

        agreement.setTermsVersion(agreement.getTermsVersion() + 1);

        var history = new CreditLimitHistory();
        history.setCreditAgreementId(agreement.getId());
        history.setTermsVersion(agreement.getTermsVersion());
        history.setPreviousLimit(previousLimit);
        history.setNewLimit(agreement.getApprovedLimit());
        history.setPreviousPeriodDays(previousPeriod);
        history.setNewPeriodDays(agreement.getCreditPeriodDays());
        history.setChangeType(changeType);
        history.setReason(reason);
        history.setChangedBy(actorId);
        limitHistory.save(history);
    }

    private CreditRequest latestRequest(Long agreementId) {
        return requests.findFirstByCreditAgreementIdOrderByCreatedAtDesc(agreementId).orElse(null);
    }

    private CreditAgreement loadForRestaurant(Long actorId, Long agreementId, String permission) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        accessControl.requireScoped(actorId, permission, ScopeType.OUTLET,
                agreement.getOutletId(), "CreditAgreement");
        return agreement;
    }

    private CreditAgreement loadForSupplier(Long actorId, Long agreementId, String permission) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        accessControl.requireScoped(actorId, permission, ScopeType.SUPPLIER_STORE,
                agreement.getSupplierStoreId(), "CreditAgreement");
        return agreement;
    }

    /**
     * Either party may read an agreement; nobody else may learn it exists.
     *
     * <p>Both failures report 404 (doc 09 §3) — a 403 for "exists, not yours" would
     * let a caller walk ids and map which restaurants have credit with which
     * suppliers, which is commercially sensitive in a way order ids are not.
     */
    private CreditAgreement loadForEitherSide(Long actorId, Long agreementId) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));

        if (accessControl.has(actorId, Permissions.CREDIT_VIEW,
                ScopeType.OUTLET, agreement.getOutletId())
                || accessControl.has(actorId, Permissions.CREDIT_VIEW,
                ScopeType.SUPPLIER_STORE, agreement.getSupplierStoreId())) {
            return agreement;
        }
        log.warn("Scope violation: user={} CreditAgreement={} — reported as not found",
                actorId, agreementId);
        throw new NotFoundException("CreditAgreement", agreementId);
    }

    private CreditDtos.AgreementResponse toResponse(CreditAgreement agreement, CreditRequest request) {
        var store = directory.store(agreement.getSupplierStoreId());
        var outlet = directory.outlet(agreement.getOutletId());
        var dues = agreement.getId() == null
                ? new CreditInvoiceService.Dues(BigDecimal.ZERO, BigDecimal.ZERO)
                : invoiceService.duesFor(agreement.getId());

        return new CreditDtos.AgreementResponse(
                agreement.getId(), agreement.getOutletId(),
                outlet == null ? null : outlet.outletName(),
                agreement.getSupplierStoreId(),
                store == null ? null : store.storeName(),
                store == null ? null : store.supplierName(),
                agreement.getStatus(),
                agreement.getApprovedLimit(), agreement.getReservedAmount(),
                agreement.getUtilizedAmount(), agreement.available(),
                dues.due(), dues.overdue(),
                agreement.getCreditPeriodDays(), agreement.getGracePeriodDays(),
                agreement.getMaxSingleOrderCredit(), agreement.getTermsVersion(),
                agreement.getEffectiveFrom(), agreement.getReviewDate(),
                agreement.getSuspensionReason(),
                // Stated, not implied. §23A.24: the app must not decide from the
                // status whether an order can draw on this.
                agreement.getStatus().canFund(),
                agreement.getActivatedAt(),
                request == null ? null : new CreditDtos.RequestResponse(
                        request.getId(), request.getCreditAgreementId(), request.getOutletId(),
                        request.getSupplierStoreId(), request.getRequestedLimit(),
                        request.getRequestedPeriodDays(), request.getPurpose(), request.getNote(),
                        request.getStatus(), request.getResponseNote(),
                        request.getRespondedAt(), request.getCreatedAt()));
    }
}
