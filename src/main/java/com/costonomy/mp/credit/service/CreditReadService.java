package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditTransaction;
import com.costonomy.mp.credit.domain.CreditTransactionType;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRepository;
import com.costonomy.mp.credit.repository.CreditPaymentReversalRepository;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.repository.CreditTransactionRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the restaurant's Credit screens read that the agreement endpoints do not give (D-124): the Home attention
 * dot, one invoice with its payments, and a statement. Read-only. Every date is an India calendar day, from the
 * credit clock, and every figure is computed here so the app does no date or money arithmetic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditReadService {

    /** A statement longer than this is refused; with the default window it keeps the page a screenful. */
    static final int MAX_STATEMENT_DAYS = 366;
    static final int DEFAULT_STATEMENT_DAYS = 90;

    private static final List<CreditInvoiceStatus> OPEN_NOT_OVERDUE =
            List.of(CreditInvoiceStatus.ISSUED, CreditInvoiceStatus.PARTIALLY_PAID);

    private final CreditInvoiceRepository invoices;
    private final CreditPaymentRepository payments;
    private final CreditPaymentReversalRepository reversals;
    private final CreditRepaymentRepository repayments;
    private final CreditTransactionRepository transactions;
    private final CreditAgreementService agreements;
    private final CreditInvoiceService invoiceService;
    private final CreditClaimService claimService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;

    // ── Home attention ───────────────────────────────────────────────────

    /** Two existence checks, no amounts: Home shows a dot, not a balance. */
    @Transactional(readOnly = true)
    public CreditDtos.AttentionResponse attention(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        LocalDate today = invoiceService.today();
        LocalDate soonest = today.plusDays(com.costonomy.mp.credit.domain.CreditDueState.SOON_DAYS);
        // The same rule as an invoice's dueState (D-130): marked OVERDUE, or open and past its grace period already,
        // whether or not the hourly sweep has marked it.
        boolean overdue = invoices.existsOverdueByRule(outletId, OPEN_NOT_OVERDUE, today);
        // Only open ones that are not overdue by that rule: an overdue invoice is reported as overdue, not as due soon.
        boolean dueSoon = invoices.existsDueSoonByRule(outletId, OPEN_NOT_OVERDUE, soonest, today);
        return new CreditDtos.AttentionResponse(overdue, dueSoon);
    }

    // ── One invoice ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditDtos.InvoiceDetailResponse invoice(Long actorId, Long invoiceId) {
        var invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
        requireEitherSide(actorId, invoice);

        var base = invoiceService.toInvoiceResponse(invoice);
        var order = directory.order(invoice.getSupplierOrderId());
        var store = directory.store(invoice.getSupplierStoreId());

        var rows = payments.findByCreditInvoiceIdOrderByPaidAtDescIdDesc(invoiceId);
        var walletEntries = walletEntryIds(rows);
        var reversedAt = reversals.findByCreditPaymentIdIn(rows.stream().map(CreditPayment::getId).toList()).stream()
                .collect(Collectors.toMap(r -> r.getCreditPaymentId(), r -> r.getReversedAt()));
        var paymentResponses = rows.stream()
                .map(p -> new CreditDtos.InvoicePaymentResponse(p.getId(), p.getAmount(), p.getSource(),
                        p.getMethod(), p.getReference(), p.getPaidAt(),
                        p.getCreditRepaymentId() == null ? null : walletEntries.get(p.getCreditRepaymentId()),
                        reversedAt.get(p.getId())))
                .toList();

        return new CreditDtos.InvoiceDetailResponse(
                base.id(), base.invoiceNumber(), base.creditAgreementId(), base.supplierOrderId(), base.status(),
                base.amount(), base.paidAmount(), base.outstanding(), base.dueDate(), base.overdueAfter(),
                base.issuedAt(), base.settledAt(), base.dueState(), base.daysToDue(),
                order == null ? null : order.orderNumber(),
                store == null ? null : store.supplierName(),
                store == null ? null : store.storeName(),
                paymentResponses,
                claimService.forInvoice(invoice),
                base.reportableAmount());
    }

    /** Either party may read it; nobody else may learn it exists (404, like the agreement endpoints). */
    private void requireEitherSide(Long actorId, CreditInvoice invoice) {
        if (accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.OUTLET, invoice.getOutletId())
                || accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE,
                invoice.getSupplierStoreId())
                || accessControl.has(actorId, Permissions.CREDIT_REQUEST_VIEW, ScopeType.SUPPLIER_STORE,
                invoice.getSupplierStoreId())) {
            return;
        }
        log.warn("Scope violation: user={} CreditInvoice={} — reported as not found", actorId, invoice.getId());
        throw new NotFoundException("CreditInvoice", invoice.getId());
    }

    /** credit_repayment id to the wallet ledger entry that funded it (only repayments that have one). */
    private Map<Long, Long> walletEntryIds(List<CreditPayment> rows) {
        var ids = rows.stream().map(CreditPayment::getCreditRepaymentId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        var map = new HashMap<Long, Long>();
        for (CreditRepayment repayment : repayments.findAllById(ids)) {
            if (repayment.getWalletTransactionId() != null) {
                map.put(repayment.getId(), repayment.getWalletTransactionId());
            }
        }
        return map;
    }

    // ── Statement ────────────────────────────────────────────────────────

    /**
     * What happened to what the restaurant owes on this line between two India calendar days, both inclusive.
     *
     * <p>Built from the credit ledger. Each row's effect is the change in {@code balance_utilized_after} from the
     * row before it, and only rows that actually moved what is owed are listed: RESERVE (a hold, nothing owed
     * yet), a RELEASE of a hold, and a LIMIT_CHANGE all net to nothing, so they would be noise on a statement.
     * That keeps {@code openingOwed + sum(amount) == closingOwed} true by construction.
     */
    @Transactional(readOnly = true)
    public CreditDtos.StatementResponse statement(Long actorId, Long agreementId, LocalDate from, LocalDate to) {
        var agreement = agreements.loadForEitherSide(actorId, agreementId);

        LocalDate today = invoiceService.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_STATEMENT_DAYS - 1L) : from;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The end date can't be before the start date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_STATEMENT_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A statement can cover at most %d days.".formatted(MAX_STATEMENT_DAYS));
        }

        // The India day, not the UTC one: a row at 23:30 IST belongs to that day, one at 00:00 IST to the next.
        var zone = invoiceService.zone();
        Instant windowStart = start.atStartOfDay(zone).toInstant();
        Instant windowEnd = end.plusDays(1).atStartOfDay(zone).toInstant();

        BigDecimal previous = BigDecimal.ZERO;
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.ZERO;
        var visible = new ArrayList<Visible>();

        for (CreditTransaction row : transactions.findByCreditAgreementIdOrderByIdAsc(agreement.getId())) {
            BigDecimal owed = row.getBalanceUtilizedAfter();
            BigDecimal change = owed.subtract(previous);
            previous = owed;

            if (row.getCreatedAt().isBefore(windowStart)) {
                opening = owed;
                closing = owed;
            } else if (row.getCreatedAt().isBefore(windowEnd)) {
                closing = owed;
                if (change.signum() != 0) {
                    visible.add(new Visible(row, change));
                }
            }
        }

        var lines = describe(visible);
        java.util.Collections.reverse(lines);
        return new CreditDtos.StatementResponse(agreement.getId(), start, end, opening, closing, lines);
    }

    private record Visible(CreditTransaction row, BigDecimal change) {
    }

    /** Lines oldest first, with the order, invoice and payment details each one needs. */
    private List<CreditDtos.StatementLine> describe(List<Visible> visible) {
        // Invoices named by the rows, and the one raised for each order (an order's UTILIZE row has no invoice id).
        var orderIds = visible.stream().map(v -> v.row().getSupplierOrderId()).filter(java.util.Objects::nonNull)
                .distinct().toList();
        var invoiceByOrder = new HashMap<Long, CreditInvoice>();
        for (Long orderId : orderIds) {
            invoices.findBySupplierOrderId(orderId).ifPresent(i -> invoiceByOrder.put(orderId, i));
        }
        var invoiceIds = visible.stream().map(v -> v.row().getCreditInvoiceId()).filter(java.util.Objects::nonNull)
                .distinct().toList();
        var invoiceById = invoices.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(CreditInvoice::getId, i -> i));

        // A repayment row is matched to its payment by position: the n-th REPAYMENT row of an invoice is the
        // n-th payment of that invoice, since both are written together, in order, in one transaction.
        var paymentsByInvoice = new HashMap<Long, List<CreditPayment>>();
        var walletEntries = new HashMap<Long, Long>();
        for (Long invoiceId : invoiceIds) {
            var list = payments.findByCreditInvoiceIdOrderByIdAsc(invoiceId);
            paymentsByInvoice.put(invoiceId, list);
            walletEntries.putAll(walletEntryIds(list));
        }
        // The same for a PAYMENT_REVERSED row: the n-th one of an invoice belongs to the n-th reversal of it (D-140).
        var reversedPayment = new HashMap<Long, List<CreditPayment>>();
        for (Long invoiceId : invoiceIds) {
            var byId = paymentsByInvoice.get(invoiceId).stream().collect(Collectors.toMap(CreditPayment::getId, p -> p));
            reversedPayment.put(invoiceId, reversals.findByCreditInvoiceIdOrderByIdAsc(invoiceId).stream()
                    .map(r -> byId.get(r.getCreditPaymentId())).toList());
        }
        var repaymentRank = new HashMap<Long, Integer>();
        var allRepaymentRows = new HashMap<Long, List<Long>>();
        var reversalRank = new HashMap<Long, Integer>();
        var allReversalRows = new HashMap<Long, List<Long>>();
        for (Long invoiceId : invoiceIds) {
            allRepaymentRows.put(invoiceId, new ArrayList<>());
            allReversalRows.put(invoiceId, new ArrayList<>());
        }
        for (CreditTransaction row : transactions.findByCreditAgreementIdOrderByIdAsc(
                visible.isEmpty() ? -1L : visible.get(0).row().getCreditAgreementId())) {
            if (row.getCreditInvoiceId() == null) {
                continue;
            }
            if (row.getTransactionType() == CreditTransactionType.REPAYMENT
                    && allRepaymentRows.containsKey(row.getCreditInvoiceId())) {
                allRepaymentRows.get(row.getCreditInvoiceId()).add(row.getId());
            } else if (row.getTransactionType() == CreditTransactionType.PAYMENT_REVERSED
                    && allReversalRows.containsKey(row.getCreditInvoiceId())) {
                allReversalRows.get(row.getCreditInvoiceId()).add(row.getId());
            }
        }
        allRepaymentRows.forEach((invoiceId, ids) -> {
            for (int i = 0; i < ids.size(); i++) {
                repaymentRank.put(ids.get(i), i);
            }
        });
        allReversalRows.forEach((invoiceId, ids) -> {
            for (int i = 0; i < ids.size(); i++) {
                reversalRank.put(ids.get(i), i);
            }
        });

        var lines = new ArrayList<CreditDtos.StatementLine>();
        for (Visible v : visible) {
            CreditTransaction row = v.row();
            Long orderId = row.getSupplierOrderId();
            var order = orderId == null ? null : directory.order(orderId);
            CreditInvoice invoice = row.getCreditInvoiceId() != null
                    ? invoiceById.get(row.getCreditInvoiceId()) : invoiceByOrder.get(orderId);
            if (orderId == null && invoice != null) {
                orderId = invoice.getSupplierOrderId();
                order = directory.order(orderId);
            }

            CreditPayment payment = null;
            if (row.getTransactionType() == CreditTransactionType.REPAYMENT && row.getCreditInvoiceId() != null) {
                var list = paymentsByInvoice.get(row.getCreditInvoiceId());
                Integer rank = repaymentRank.get(row.getId());
                if (list != null && rank != null && rank < list.size()) {
                    payment = list.get(rank);
                }
            } else if (row.getTransactionType() == CreditTransactionType.PAYMENT_REVERSED && row.getCreditInvoiceId() != null) {
                var list = reversedPayment.get(row.getCreditInvoiceId());
                Integer rank = reversalRank.get(row.getId());
                if (list != null && rank != null && rank < list.size()) {
                    payment = list.get(rank);
                }
            }

            lines.add(new CreditDtos.StatementLine(
                    row.getCreatedAt(), row.getTransactionType(), label(row.getTransactionType()),
                    v.change(), row.getBalanceUtilizedAfter(),
                    orderId, order == null ? null : order.orderNumber(),
                    invoice == null ? null : invoice.getId(), invoice == null ? null : invoice.getInvoiceNumber(),
                    payment == null ? null : payment.getSource(),
                    payment == null ? null : payment.getMethod(),
                    payment == null ? null : payment.getReference(),
                    payment == null || payment.getCreditRepaymentId() == null
                            ? null : walletEntries.get(payment.getCreditRepaymentId())));
        }
        return lines;
    }

    private static String label(CreditTransactionType type) {
        return switch (type) {
            case UTILIZE -> "Order on credit";
            case REPAYMENT -> "Repayment";
            case RELEASE -> "Released";
            case ADJUSTMENT -> "Adjustment";
            case LIMIT_CHANGE -> "Limit change";
            case RESERVE -> "Reserved";
            case PAYMENT_REVERSED -> "Payment reversed";
        };
    }
}
