package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditPaymentClaim;
import com.costonomy.mp.credit.domain.CreditPaymentMethod;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentClaimRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "I paid" claims (D-125): a restaurant reports a payment it made straight to the supplier, and the supplier confirms
 * or rejects it.
 *
 * <p><b>A claim changes nothing.</b> Not the invoice, not the exposure, not the status, not the overdue sweep and not
 * auto-suspend. Credit is the supplier's and Mandi never lets a restaurant clear its own debt on its say-so, so the
 * only thing that reduces a debt is the supplier's confirmation, which goes through the same
 * {@link CreditInvoiceService#applyPayment} as a payment the supplier records.
 *
 * <p>The write paths are plain beans around a {@link TransactionTemplate}, run inside
 * {@code IdempotencyService.execute} (D-016). Lock order, always: the claim first, then the invoice. Creating a claim
 * takes the invoice only, so no two takers can wait on each other.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditClaimService {

    private final CreditPaymentClaimRepository claims;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceService invoiceService;
    private final CreditAgreementService agreements;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    // ── Restaurant: claim and withdraw ───────────────────────────────────

    public CreditDtos.ClaimResponse submit(Long actorId, Long invoiceId, CreditDtos.ClaimRequest request,
                                           String idempotencyKey) {
        var invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
        // Another outlet's invoice, and a supplier user, are "not found" like every restaurant-side credit endpoint.
        accessControl.requireScoped(actorId, Permissions.CREDIT_REPAY, ScopeType.OUTLET,
                invoice.getOutletId(), "CreditInvoice");

        BigDecimal amount = request.amount().setScale(2);
        CreditPaymentMethod method = CreditPaymentMethod.valueOf(request.method());
        String reference = request.reference() == null || request.reference().isBlank()
                ? null : request.reference().trim();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();

        // Refused before the idempotency key is claimed, so a refused claim leaves no trace at all.
        LocalDate today = invoiceService.today();
        if (request.paidOn().isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The payment date can't be in the future.");
        }
        LocalDate issuedOn = LocalDate.ofInstant(invoice.getIssuedAt(), invoiceService.zone());
        if (request.paidOn().isBefore(issuedOn)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The payment date can't be before the invoice was issued on %s.".formatted(issuedOn));
        }

        var payload = payload(invoiceId, amount, method, reference, request.paidOn(), note);

        try (var trace = TraceScope.of("credit-invoice", invoiceId)) {
            return idempotency.execute(actorId, "credit.claim", idempotencyKey, payload,
                    CreditDtos.ClaimResponse.class,
                    () -> txTemplate.execute(status -> doSubmit(actorId, invoiceId, amount, method, reference,
                            request.paidOn(), note, idempotencyKey)));
        }
    }

    /** What identifies "the same claim" for the idempotency hash; the amount is scaled to 2 places, as plain text. */
    public static Map<String, Object> payload(Long invoiceId, BigDecimal amount, CreditPaymentMethod method,
                                              String reference, LocalDate paidOn, String note) {
        var payload = new HashMap<String, Object>();
        payload.put("invoiceId", invoiceId);
        payload.put("amount", amount.toPlainString());
        payload.put("method", method.name());
        payload.put("reference", reference == null ? "" : reference);
        payload.put("paidOn", paidOn.toString());
        payload.put("note", note == null ? "" : note);
        return payload;
    }

    private CreditDtos.ClaimResponse doSubmit(Long actorId, Long invoiceId, BigDecimal amount,
                                              CreditPaymentMethod method, String reference, LocalDate paidOn,
                                              String note, String idempotencyKey) {
        // The invoice is held, so claims racing each other, or a payment, are each counted against the other.
        var invoice = invoices.lockById(invoiceId)
                .orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));

        BigDecimal claimable = invoice.reportable(claims.sumByInvoiceAndStatus(invoiceId, CreditClaimStatus.SUBMITTED));
        if (amount.compareTo(claimable) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_OVERPAYMENT,
                    claimable.signum() == 0
                            ? "Nothing more can be claimed on this invoice."
                            : "That's more than the ₹%s that can still be claimed on this invoice."
                            .formatted(Rupees.of(claimable)),
                    Map.of("outstanding", claimable));
        }

        // Below ₹1 only when it is exactly what can still be claimed on the invoice (D-130).
        if (amount.compareTo(CreditWalletRepaymentService.MIN_AMOUNT) < 0 && amount.compareTo(claimable) != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, CreditWalletRepaymentService.MIN_AMOUNT_MESSAGE);
        }

        var claim = new CreditPaymentClaim();
        claim.setCreditInvoiceId(invoiceId);
        claim.setCreditAgreementId(invoice.getCreditAgreementId());
        claim.setOutletId(invoice.getOutletId());
        claim.setSupplierStoreId(invoice.getSupplierStoreId());
        claim.setAmount(amount);
        claim.setMethod(method);
        claim.setReference(reference);
        claim.setPaidOn(paidOn);
        claim.setNote(note);
        claim.setStatus(CreditClaimStatus.SUBMITTED);
        claim.setClaimedBy(actorId);
        claim.setIdempotencyKey("claim:" + actorId + ":" + idempotencyKey);
        claims.save(claim);

        auditService.record(actorId, null, "CREDIT_CLAIM_SUBMITTED", "CREDIT_PAYMENT_CLAIM", claim.getId(),
                null, CreditClaimStatus.SUBMITTED.name(),
                amount.toPlainString() + " via " + method + " against " + invoice.getInvoiceNumber(), "API");

        var outlet = directory.outlet(invoice.getOutletId());
        outbox.publish(CreditEvents.CLAIM_SUBMITTED, "CREDIT_INVOICE", invoiceId,
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "supplierStoreId", invoice.getSupplierStoreId(),
                        "restaurantName", outlet == null || outlet.restaurantName() == null ? "" : outlet.restaurantName(),
                        "invoiceNumber", invoice.getInvoiceNumber(),
                        "amount", amount.toPlainString(),
                        "claimId", claim.getId()),
                actorId);
        log.info("Credit claim {} of {} on invoice {} (outlet {})", claim.getId(), Rupees.of(amount),
                invoiceId, invoice.getOutletId());
        return toResponse(claim, invoice.getInvoiceNumber(), outlet);
    }

    public CreditDtos.ClaimResponse withdraw(Long actorId, Long claimId) {
        var seen = claims.findById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
        accessControl.requireScoped(actorId, Permissions.CREDIT_REPAY, ScopeType.OUTLET,
                seen.getOutletId(), "CreditPaymentClaim");

        return txTemplate.execute(status -> {
            var claim = claims.lockById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
            if (claim.getStatus() == CreditClaimStatus.WITHDRAWN) {
                // A retry after a lost response: already in the state asked for, so answer, change and publish nothing (D-129).
                return respond(claim);
            }
            requireSubmitted(claim);
            claim.setStatus(CreditClaimStatus.WITHDRAWN);
            claim.setDecidedAt(Instant.now());
            claims.save(claim);
            auditService.record(actorId, null, "CREDIT_CLAIM_WITHDRAWN", "CREDIT_PAYMENT_CLAIM", claimId,
                    CreditClaimStatus.SUBMITTED.name(), CreditClaimStatus.WITHDRAWN.name(), null, "API");
            return respond(claim);
        });
    }

    // ── Supplier: confirm and reject ─────────────────────────────────────

    public CreditDtos.ClaimResponse confirm(Long actorId, Long claimId, BigDecimal requestedAmount,
                                            String idempotencyKey) {
        var seen = claims.findById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
        // Only the supplier the money reached can say it arrived. A restaurant user gets "not found".
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, seen.getSupplierStoreId(),
                "CreditPaymentClaim", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);

        BigDecimal requested = requestedAmount == null ? null : requestedAmount.setScale(2);
        var payload = new HashMap<String, Object>();
        payload.put("claimId", claimId);
        payload.put("amount", requested == null ? "" : requested.toPlainString());

        try (var trace = TraceScope.of("credit-claim", claimId)) {
            return idempotency.execute(actorId, "credit.claim-confirm", idempotencyKey, payload,
                    CreditDtos.ClaimResponse.class,
                    () -> txTemplate.execute(status -> doConfirm(actorId, claimId, requested)));
        }
    }

    private CreditDtos.ClaimResponse doConfirm(Long actorId, Long claimId, BigDecimal requested) {
        // Claim first, then invoice: the one order every taker uses.
        var claim = claims.lockById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
        requireSubmitted(claim);
        var invoice = invoices.lockById(claim.getCreditInvoiceId())
                .orElseThrow(() -> new NotFoundException("CreditInvoice", claim.getCreditInvoiceId()));

        BigDecimal outstanding = invoice.outstanding();
        if (invoice.getStatus().isSettled() || outstanding.signum() <= 0) {
            throw new BusinessException(ErrorCode.CREDIT_CLAIM_STATE,
                    "Invoice %s is already settled, so there is nothing left to confirm this payment against."
                            .formatted(invoice.getInvoiceNumber()));
        }

        // What it confirms is never more than was claimed, and never more than is owed now: another payment may have
        // reduced the invoice since the claim was made, and confirming past zero would hand out credit nobody repaid.
        BigDecimal amount;
        if (requested == null) {
            amount = claim.getAmount().min(outstanding);
        } else {
            if (requested.compareTo(claim.getAmount()) > 0 || requested.compareTo(outstanding) > 0) {
                BigDecimal most = claim.getAmount().min(outstanding);
                throw new BusinessException(ErrorCode.CREDIT_OVERPAYMENT,
                        "You can confirm at most ₹%s: the claim is for ₹%s and ₹%s is outstanding."
                                .formatted(Rupees.of(most), Rupees.of(claim.getAmount()), Rupees.of(outstanding)),
                        Map.of("outstanding", outstanding, "claimed", claim.getAmount()));
            }
            amount = requested;
        }

        Instant paidAt = claim.getPaidOn().atStartOfDay(invoiceService.zone()).toInstant();
        var payment = invoiceService.applyPayment(invoice, amount, claim.getMethod().name(), claim.getReference(),
                claim.getNote(), paidAt, actorId, "claim:" + claimId, CreditPaymentSource.CLAIM_CONFIRMED,
                null, claimId);

        claim.setStatus(CreditClaimStatus.CONFIRMED);
        claim.setConfirmedAmount(amount);
        claim.setCreditPaymentId(payment.getId());
        claim.setDecidedBy(actorId);
        claim.setDecidedAt(Instant.now());
        claims.save(claim);

        auditService.record(actorId, null, "CREDIT_CLAIM_CONFIRMED", "CREDIT_PAYMENT_CLAIM", claimId,
                CreditClaimStatus.SUBMITTED.name(), CreditClaimStatus.CONFIRMED.name(),
                amount.toPlainString() + " of " + claim.getAmount().toPlainString() + " on "
                        + invoice.getInvoiceNumber(), "API");

        // CreditClaimConfirmed and not CreditRepaymentRecorded: that rule would tell the restaurant the same thing twice.
        outbox.publish(CreditEvents.CLAIM_CONFIRMED, "CREDIT_INVOICE", invoice.getId(),
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "supplierStoreId", invoice.getSupplierStoreId(),
                        "supplierName", supplierNameOf(invoice.getSupplierStoreId()),
                        "invoiceNumber", invoice.getInvoiceNumber(),
                        "amount", amount.toPlainString(),
                        "claimId", claimId),
                actorId);
        log.info("Credit claim {} confirmed for {} on invoice {}", claimId, Rupees.of(amount), invoice.getId());
        return toResponse(claim, invoice.getInvoiceNumber(), directory.outlet(invoice.getOutletId()));
    }

    public CreditDtos.ClaimResponse reject(Long actorId, Long claimId, String reason) {
        var seen = claims.findById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, seen.getSupplierStoreId(),
                "CreditPaymentClaim", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);

        return txTemplate.execute(status -> {
            var claim = claims.lockById(claimId).orElseThrow(() -> new NotFoundException("CreditPaymentClaim", claimId));
            if (claim.getStatus() == CreditClaimStatus.REJECTED && reason.trim().equals(claim.getDecisionNote())) {
                // The same rejection again (a retry after a lost response): answer it, change and publish nothing.
                // Another reason is a different decision, and a claim in any other state is still refused (D-129).
                return respond(claim);
            }
            requireSubmitted(claim);
            claim.setStatus(CreditClaimStatus.REJECTED);
            claim.setDecisionNote(reason.trim());
            claim.setDecidedBy(actorId);
            claim.setDecidedAt(Instant.now());
            claims.save(claim);

            auditService.record(actorId, null, "CREDIT_CLAIM_REJECTED", "CREDIT_PAYMENT_CLAIM", claimId,
                    CreditClaimStatus.SUBMITTED.name(), CreditClaimStatus.REJECTED.name(), reason.trim(), "API");
            var invoice = invoices.findById(claim.getCreditInvoiceId()).orElseThrow();
            outbox.publish(CreditEvents.CLAIM_REJECTED, "CREDIT_INVOICE", invoice.getId(),
                    Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                            "outletId", invoice.getOutletId(),
                            "supplierStoreId", invoice.getSupplierStoreId(),
                            "supplierName", supplierNameOf(invoice.getSupplierStoreId()),
                            "invoiceNumber", invoice.getInvoiceNumber(),
                            "amount", claim.getAmount().toPlainString(),
                            "reason", reason.trim(),
                            "claimId", claimId),
                    actorId);
            return toResponse(claim, invoice.getInvoiceNumber(), directory.outlet(invoice.getOutletId()));
        });
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /** An agreement's claims, newest first. Same access as its ledger; anyone else gets a 404. */
    @Transactional(readOnly = true)
    public List<CreditDtos.ClaimResponse> forAgreement(Long actorId, Long agreementId, CreditClaimStatus status) {
        agreements.loadForEitherSide(actorId, agreementId);
        return respond(status == null
                ? claims.findByCreditAgreementIdOrderByIdDesc(agreementId)
                : claims.findByCreditAgreementIdAndStatusOrderByIdDesc(agreementId, status));
    }

    /** A store's inbox of claims, newest first, optionally of one status. Another store's user gets a 404. */
    @Transactional(readOnly = true)
    public List<CreditDtos.ClaimResponse> forStore(Long actorId, Long storeId, CreditClaimStatus status) {
        if (!accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE, storeId)
                && !accessControl.has(actorId, Permissions.CREDIT_REQUEST_VIEW, ScopeType.SUPPLIER_STORE, storeId)) {
            log.warn("Scope violation: user={} SupplierStore={} — reported as not found", actorId, storeId);
            throw new NotFoundException("SupplierStore", storeId);
        }
        return respond(status == null
                ? claims.findBySupplierStoreIdOrderByIdDesc(storeId)
                : claims.findBySupplierStoreIdAndStatusOrderByIdDesc(storeId, status));
    }

    /** The claims of one invoice, newest first. The caller has already checked who may read the invoice. */
    @Transactional(readOnly = true)
    public List<CreditDtos.ClaimResponse> forInvoice(CreditInvoice invoice) {
        var outlet = directory.outlet(invoice.getOutletId());
        return claims.findByCreditInvoiceIdOrderByIdDesc(invoice.getId()).stream()
                .map(claim -> toResponse(claim, invoice.getInvoiceNumber(), outlet))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void requireSubmitted(CreditPaymentClaim claim) {
        if (claim.getStatus() != CreditClaimStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.CREDIT_CLAIM_STATE,
                    "This payment claim was already %s.".formatted(claim.getStatus().name().toLowerCase()));
        }
    }

    private String supplierNameOf(Long supplierStoreId) {
        var store = directory.store(supplierStoreId);
        return store == null || store.supplierName() == null ? "" : store.supplierName();
    }

    private CreditDtos.ClaimResponse respond(CreditPaymentClaim claim) {
        return respond(List.of(claim)).get(0);
    }

    private List<CreditDtos.ClaimResponse> respond(List<CreditPaymentClaim> rows) {
        var numbers = invoices.findAllById(rows.stream().map(CreditPaymentClaim::getCreditInvoiceId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(CreditInvoice::getId, CreditInvoice::getInvoiceNumber));
        var outlets = new LinkedHashMap<Long, CreditDirectory.OutletInfo>();
        for (CreditPaymentClaim row : rows) {
            outlets.computeIfAbsent(row.getOutletId(), directory::outlet);
        }
        return rows.stream()
                .map(row -> toResponse(row, numbers.get(row.getCreditInvoiceId()), outlets.get(row.getOutletId())))
                .toList();
    }

    private CreditDtos.ClaimResponse toResponse(CreditPaymentClaim claim, String invoiceNumber,
                                                CreditDirectory.OutletInfo outlet) {
        return new CreditDtos.ClaimResponse(
                claim.getId(), claim.getCreditInvoiceId(), invoiceNumber, claim.getCreditAgreementId(),
                claim.getOutletId(), outlet == null ? null : outlet.outletName(),
                outlet == null ? null : outlet.restaurantName(),
                claim.getAmount(), claim.getMethod(), claim.getReference(), claim.getPaidOn(), claim.getNote(),
                claim.getStatus(), claim.getDecisionNote(), claim.getConfirmedAmount(), claim.getCreditPaymentId(),
                claim.getCreatedAt(), claim.getDecidedAt());
    }
}
