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
import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentSource;
import com.costonomy.mp.credit.domain.CreditRepaymentStatus;
import com.costonomy.mp.credit.domain.SuspensionSource;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRepository;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The supplier records money received for a whole credit line (B5, D-134): one receipt, split over the open invoices.
 *
 * <p>Like {@link CreditWalletRepaymentService} this is a plain bean around a {@link TransactionTemplate}, because the
 * work runs inside {@code IdempotencyService.execute} (D-016). The receipt is a {@code credit_repayment} row with
 * source SUPPLIER_RECORDED; each allocation goes through {@link CreditInvoiceService#applyPayment}, the one method
 * every payment goes through, so the status rule, the exposure arithmetic and the auto-reinstate cannot drift
 * from the wallet path. Lock order is the wallet path's with the wallet left out: the invoices {@code FOR UPDATE} in
 * ascending id order, then the agreement's exposure (inside {@code applyPayment}). A claim is never locked here: a
 * receipt that settles an invoice closes its other open claims with a skip-locked update, as any payment does (D-130).
 *
 * <p>The preview runs the same allocation on the unlocked rows and writes nothing.
 *
 * <p><b>Duplicate references.</b> The check runs before the idempotency key is claimed, so refusing a repeated UTR
 * leaves the key free for the "Record anyway" retry; a replay of a receipt already recorded under the key skips it,
 * because that receipt itself carries the reference. It is checked again inside the transaction once the invoices
 * are locked, which catches two people recording one UTR against the same invoices at once; against different
 * invoices of the same store, two simultaneous receipts with one UTR can still both pass. That is the "cash needs a
 * human" case of the plan (CC01): a supplier can always override with {@code allowDuplicateReference}, so the check
 * is a typo guard and not a ledger rule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditSupplierPaymentService {

    static final int DUPLICATE_WINDOW_DAYS = 90;

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID, CreditInvoiceStatus.WRITTEN_OFF);

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditRepaymentRepository repayments;
    private final CreditPaymentRepository payments;
    private final CreditInvoiceService invoiceService;
    private final CreditExposureStore exposure;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    /** One invoice and what a receipt gives it. */
    private record Allocation(CreditInvoice invoice, BigDecimal amount) {
    }

    // ── preview ──────────────────────────────────────────────────────────

    public CreditDtos.SupplierPaymentPreviewResponse preview(Long actorId, Long agreementId,
                                                             CreditDtos.SupplierPaymentPreviewRequest request) {
        var agreement = loadAndAuthorize(actorId, agreementId);
        BigDecimal amount = request.amount().setScale(2);
        List<Long> ids = sorted(request.invoiceIds());
        // One snapshot for the whole read, so the position after is worked from rows that agree with each other.
        return txTemplate.execute(status -> {
            var targets = ids == null ? openOf(agreementId) : openWithIds(agreementId, ids);
            var plan = allocate(targets, amount);
            var dues = invoiceService.duesFor(agreementId);
            var claims = invoiceService.openClaimsByInvoice(agreementId);

            var warnings = new ArrayList<CreditDtos.PendingClaimWarning>();
            BigDecimal overdueCleared = BigDecimal.ZERO;
            for (var step : plan) {
                BigDecimal claimed = claims.get(step.invoice().getId());
                if (claimed != null && claimed.signum() > 0) {
                    warnings.add(new CreditDtos.PendingClaimWarning(step.invoice().getId(),
                            step.invoice().getInvoiceNumber(), money(claimed)));
                }
                if (step.invoice().getStatus() == CreditInvoiceStatus.OVERDUE) {
                    overdueCleared = overdueCleared.add(step.amount());
                }
            }
            BigDecimal overdueAfter = dues.overdue().subtract(overdueCleared);
            return new CreditDtos.SupplierPaymentPreviewResponse(amount, previewAllocations(plan),
                    new CreditDtos.RepaymentAgreementState(money(dues.due().subtract(amount)), money(overdueAfter),
                            money(exposure.read(agreementId).available().add(amount)),
                            statusAfter(agreement, overdueAfter)),
                    warnings);
        });
    }

    /**
     * What the line's status will be once the receipt is in: a suspension the overdue sweep imposed lifts when what is
     * overdue is back within the tolerance (the rule of {@code CreditInvoiceService.reinstateIfOverdueCleared}); a
     * supplier's own suspension never does.
     */
    private static CreditAgreementStatus statusAfter(CreditAgreement agreement, BigDecimal overdueAfter) {
        if (agreement.getStatus() == CreditAgreementStatus.SUSPENDED
                && agreement.getSuspensionSource() == SuspensionSource.SYSTEM) {
            BigDecimal max = agreement.overdueTolerance();
            if (max == null || overdueAfter.compareTo(max) <= 0) {
                return CreditAgreementStatus.ACTIVE;
            }
        }
        return agreement.getStatus();
    }

    // ── record ───────────────────────────────────────────────────────────

    public CreditDtos.SupplierPaymentResponse record(Long actorId, Long agreementId,
                                                     CreditDtos.SupplierPaymentRequest request, String idempotencyKey) {
        var agreement = loadAndAuthorize(actorId, agreementId);
        BigDecimal amount = request.amount().setScale(2);
        List<Long> ids = sorted(request.invoiceIds());
        String reference = request.trimmedReference();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        Long storeId = agreement.getSupplierStoreId();
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);
        boolean allowDuplicate = request.duplicateAllowed();

        // A receipt already recorded under this key is a replay: its own reference must not read as a duplicate of itself.
        if (repayments.findByIdempotencyKey(storedKey).isEmpty()) {
            checkNotFuture(request.paidOn());
            var targets = ids == null ? openOf(agreementId) : openWithIds(agreementId, ids);
            checkNotBeforeIssue(request.paidOn(), targets);
            if (!allowDuplicate) {
                checkNoDuplicate(storeId, reference);
            }
        }

        try (var trace = TraceScope.of("credit-agreement", agreementId)) {
            return idempotency.execute(actorId, "credit.supplier-payment", idempotencyKey,
                    payload(agreementId, amount, request.method(), reference, request.paidOn(), note, ids, allowDuplicate),
                    CreditDtos.SupplierPaymentResponse.class,
                    () -> txTemplate.execute(status -> work(actorId, agreement, amount, request.method(), reference,
                            request.paidOn(), note, ids, allowDuplicate, storedKey)));
        }
    }

    /**
     * What identifies "the same request" for the idempotency hash. The amount is scaled to 2 places and written as
     * plain text, so 1e3, 1000, 1000.0 and "1000.00" are one request; the ids are sorted, because the order they
     * were chosen in does not change what happens.
     */
    static Map<String, Object> payload(Long agreementId, BigDecimal amount, String method, String reference,
                                       LocalDate paidOn, String note, List<Long> ids, boolean allowDuplicate) {
        var map = new LinkedHashMap<String, Object>();
        map.put("agreementId", agreementId);
        map.put("amount", amount.toPlainString());
        map.put("method", method);
        map.put("reference", reference == null ? "" : reference);
        map.put("paidOn", paidOn.toString());
        map.put("note", note == null ? "" : note);
        map.put("invoiceIds", ids == null ? List.of() : ids);
        map.put("allowDuplicateReference", allowDuplicate);
        return map;
    }

    private CreditDtos.SupplierPaymentResponse work(Long actorId, CreditAgreement agreement, BigDecimal amount,
                                                    String method, String reference, LocalDate paidOn, String note,
                                                    List<Long> ids, boolean allowDuplicate, String storedKey) {
        Long agreementId = agreement.getId();

        // a. The invoices, ascending id: the one order every taker uses. One that is not this agreement's, or not
        // open, is absent from the result, and is "not found" whoever's it is.
        List<CreditInvoice> locked = ids == null
                ? invoices.lockOpenOfAgreement(agreementId, SETTLED)
                : invoices.lockOpenOfAgreementWithIds(agreementId, SETTLED, ids);
        requireAll(locked, ids);

        // b. Checked against what is owed now, with the rows held: a wallet repayment or another receipt may have
        // committed while this one waited. Nothing has been written yet.
        checkNotBeforeIssue(paidOn, locked);
        if (!allowDuplicate) {
            checkNoDuplicate(agreement.getSupplierStoreId(), reference);
        }
        var plan = allocate(locked, amount);

        // c. The receipt row, flushed so its id exists for the payment rows.
        var receipt = new CreditRepayment();
        receipt.setCreditAgreementId(agreementId);
        receipt.setOutletId(agreement.getOutletId());
        receipt.setSupplierStoreId(agreement.getSupplierStoreId());
        receipt.setAmount(amount);
        receipt.setSource(CreditRepaymentSource.SUPPLIER_RECORDED);
        receipt.setStatus(CreditRepaymentStatus.COMPLETED);
        receipt.setIdempotencyKey(storedKey);
        receipt.setCreatedBy(actorId);
        receipt.setMethod(method);
        receipt.setReference(reference);
        receipt.setPaidOn(paidOn);
        receipt.setNote(note);
        repayments.saveAndFlush(receipt);
        Long receiptId = receipt.getId();

        // d. Each allocation through the one shared method, once per invoice.
        Instant paidAt = paidAt(paidOn);
        for (var step : plan) {
            invoiceService.applyPayment(step.invoice(), step.amount(), method, reference, note, paidAt, actorId,
                    storedKey + ":" + step.invoice().getId(), CreditPaymentSource.SUPPLIER_RECORDED, receiptId);
        }

        // e. Audit, and one event for the restaurant (not one per invoice).
        auditService.record(actorId, null, "CREDIT_RECEIPT_RECORDED", "CREDIT_REPAYMENT", receiptId,
                null, CreditRepaymentStatus.COMPLETED.name(),
                amount.toPlainString() + " via " + method + (reference == null ? "" : " " + reference), "API");
        var first = plan.get(0).invoice();
        String named = plan.size() == 1 ? first.getInvoiceNumber()
                : "%s and %d more".formatted(first.getInvoiceNumber(), plan.size() - 1);
        var store = directory.store(agreement.getSupplierStoreId());
        outbox.publish(CreditEvents.REPAYMENT_RECORDED, "CREDIT_INVOICE", first.getId(),
                Map.of("creditAgreementId", agreementId,
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "supplierName", store == null || store.supplierName() == null ? "" : store.supplierName(),
                        "invoiceNumber", named,
                        "amount", amount.toPlainString(),
                        "status", first.getStatus().name(),
                        "receiptId", receiptId),
                actorId);
        log.info("Credit receipt {} of {} recorded by user {} on agreement {} over {} invoice(s)",
                receiptId, Rupees.of(amount), actorId, agreementId, plan.size());

        // Read after the writes, from the row: the exposure moved by SQL, and the line may have been reinstated.
        var dues = invoiceService.duesFor(agreementId);
        var after = agreements.findById(agreementId).orElseThrow();
        return new CreditDtos.SupplierPaymentResponse(receiptId, amount, method, reference, paidOn,
                recordedAllocations(plan),
                new CreditDtos.RepaymentAgreementState(money(dues.due()), money(dues.overdue()),
                        money(exposure.read(agreementId).available()), after.getStatus()));
    }

    // ── shared pieces ────────────────────────────────────────────────────

    private CreditAgreement loadAndAuthorize(Long actorId, Long agreementId) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        // Another store's agreement, or a role without the permission, is "not found", like every credit endpoint.
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, agreement.getSupplierStoreId(),
                "CreditAgreement", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);
        return agreement;
    }

    private static List<Long> sorted(List<Long> ids) {
        return ids == null ? null : ids.stream().sorted().toList();
    }

    /** Open invoices of the agreement, unlocked: for the preview and the early checks. */
    private List<CreditInvoice> openOf(Long agreementId) {
        return invoices.findByCreditAgreementIdOrderByDueDateAsc(agreementId).stream()
                .filter(i -> !i.getStatus().isSettled()).toList();
    }

    private List<CreditInvoice> openWithIds(Long agreementId, List<Long> ids) {
        var found = openOf(agreementId).stream().filter(i -> ids.contains(i.getId())).toList();
        requireAll(found, ids);
        return found;
    }

    private static void requireAll(List<CreditInvoice> found, List<Long> ids) {
        if (ids != null && found.size() != ids.size()) {
            var present = found.stream().map(CreditInvoice::getId).toList();
            throw new NotFoundException("CreditInvoice", ids.stream().filter(id -> !present.contains(id)).findFirst().orElse(null));
        }
    }

    /**
     * Oldest due date first, ties by id (D-134). Each invoice takes min(outstanding, what is left), in whole paise, so a
     * receipt that fits is always fully placed and one that does not is refused as CREDIT_OVERPAYMENT before anything
     * is written.
     */
    private static List<Allocation> allocate(List<CreditInvoice> targets, BigDecimal amount) {
        BigDecimal owed = targets.stream().map(CreditInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(owed) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_OVERPAYMENT,
                    "That's more than the ₹%s owed.".formatted(Rupees.of(owed)),
                    Map.of("outstanding", money(owed)));
        }
        var ordered = new ArrayList<>(targets);
        ordered.sort(Comparator.comparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId));
        var plan = new ArrayList<Allocation>();
        BigDecimal remaining = amount;
        for (CreditInvoice invoice : ordered) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal part = invoice.outstanding().min(remaining);
            plan.add(new Allocation(invoice, part));
            remaining = remaining.subtract(part);
        }
        return plan;
    }

    /** The allocations of a preview: each invoice's status is worked out the way {@code applyPayment} will leave it. */
    private static List<CreditDtos.WalletRepaymentAllocation> previewAllocations(List<Allocation> plan) {
        var out = new ArrayList<CreditDtos.WalletRepaymentAllocation>();
        for (var step : plan) {
            var invoice = step.invoice();
            CreditInvoiceStatus after = invoice.outstanding().compareTo(step.amount()) == 0 ? CreditInvoiceStatus.PAID
                    : invoice.getStatus() == CreditInvoiceStatus.OVERDUE ? CreditInvoiceStatus.OVERDUE
                    : CreditInvoiceStatus.PARTIALLY_PAID;
            out.add(new CreditDtos.WalletRepaymentAllocation(invoice.getId(), invoice.getInvoiceNumber(),
                    money(step.amount()), after));
        }
        return out;
    }

    /** The allocations of a record: {@code applyPayment} has already changed each invoice, so its status is the answer. */
    private static List<CreditDtos.WalletRepaymentAllocation> recordedAllocations(List<Allocation> plan) {
        var out = new ArrayList<CreditDtos.WalletRepaymentAllocation>();
        for (var step : plan) {
            var invoice = step.invoice();
            out.add(new CreditDtos.WalletRepaymentAllocation(invoice.getId(), invoice.getInvoiceNumber(),
                    money(step.amount()), invoice.getStatus()));
        }
        return out;
    }

    private void checkNotFuture(LocalDate paidOn) {
        if (paidOn.isAfter(invoiceService.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The payment date can't be in the future.");
        }
    }

    /** Not before the oldest targeted invoice was issued (an India calendar day). */
    private void checkNotBeforeIssue(LocalDate paidOn, List<CreditInvoice> targets) {
        checkNotFuture(paidOn);
        targets.stream().map(i -> LocalDate.ofInstant(i.getIssuedAt(), invoiceService.zone()))
                .min(Comparator.naturalOrder()).ifPresent(oldest -> {
                    if (paidOn.isBefore(oldest)) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "The payment date can't be before the invoice was issued on %s.".formatted(oldest));
                    }
                });
    }

    private void checkNoDuplicate(Long storeId, String reference) {
        if (reference == null) {
            return;
        }
        var since = invoiceService.today().minusDays(DUPLICATE_WINDOW_DAYS).atStartOfDay(invoiceService.zone()).toInstant();
        var found = payments.findRecentWithReference(storeId, reference, since, PageRequest.of(0, 1));
        if (found.isEmpty()) {
            return;
        }
        var earlier = found.get(0);
        var receipt = earlier.getCreditRepaymentId() == null ? null
                : repayments.findById(earlier.getCreditRepaymentId()).orElse(null);
        // A payment a receipt wrote is described by the receipt: the whole amount the supplier recorded, not its slice.
        BigDecimal amount = receipt == null ? earlier.getAmount() : receipt.getAmount();
        LocalDate paidOn = receipt != null && receipt.getPaidOn() != null ? receipt.getPaidOn()
                : LocalDate.ofInstant(earlier.getPaidAt(), invoiceService.zone());
        var details = new HashMap<String, Object>();
        if (receipt != null) {
            details.put("receiptId", receipt.getId());
        }
        details.put("paymentId", earlier.getId());
        details.put("paidOn", paidOn.toString());
        details.put("amount", money(amount));
        throw new BusinessException(ErrorCode.CREDIT_DUPLICATE_REFERENCE,
                "That reference was already recorded on %s for ₹%s.".formatted(paidOn, Rupees.of(amount)), details);
    }

    /** Today is "now"; an earlier day is midday India time, so it sits inside that day whatever the zone arithmetic. */
    private Instant paidAt(LocalDate paidOn) {
        return paidOn.equals(invoiceService.today()) ? Instant.now()
                : paidOn.atTime(12, 0).atZone(invoiceService.zone()).toInstant();
    }

    /** A figure for the app: two places, never rounded up above what it is. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN);
    }
}
