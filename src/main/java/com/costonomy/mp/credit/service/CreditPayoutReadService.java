package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentPayout;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPayoutReadRepository;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.web.dto.CreditPayoutDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a supplier reads about the wallet repayments Mandi collected for it (S8a, D-156): each payout with the
 * commission as it was snapshotted, the invoices it settled and, once applied, the settlement that carries it.
 * Read-only. Net, the summary and the paging are worked out here; nothing is recomputed from the current
 * commission configuration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditPayoutReadService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    public enum StatusFilter { PENDING, APPLIED, ALL }

    private final CreditPayoutReadRepository payouts;
    private final CreditRepaymentRepository repayments;
    private final CreditInvoiceRepository invoices;
    private final CreditDirectory directory;
    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    @Qualifier("creditClock")
    private final Clock clock;

    @Transactional(readOnly = true)
    public CreditPayoutDtos.PayoutListResponse list(Long actorId, Long storeId, StatusFilter status,
                                                    LocalDate from, LocalDate to, Integer page, Integer size) {
        requireStoreAccess(actorId, storeId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The start date is after the end date.");
        }
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        var zone = clock.getZone();
        Instant fromAt = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        String statusValue = status == null || status == StatusFilter.ALL ? null : status.name();

        var found = payouts.page(storeId, statusValue, fromAt, toAt, PageRequest.of(pageNumber, pageSize));
        var items = respond(found.getContent());

        LocalDate today = LocalDate.now(clock);
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
        var summary = new CreditPayoutDtos.PayoutSummary(
                money(payouts.pendingNet(storeId)), money(payouts.appliedNet(storeId, monthStart, nextMonth)));

        return new CreditPayoutDtos.PayoutListResponse(summary, items, pageNumber, pageSize,
                found.getTotalElements(), found.getTotalPages(), found.hasNext());
    }

    @Transactional(readOnly = true)
    public CreditPayoutDtos.PayoutResponse get(Long actorId, Long storeId, Long payoutId) {
        requireStoreAccess(actorId, storeId);
        var payout = payouts.findByIdAndSupplierStoreId(payoutId, storeId)
                .orElseThrow(() -> new NotFoundException("CreditRepaymentPayout", payoutId));
        return respond(List.of(payout)).get(0);
    }

    /** Same rule as the store's claims inbox: another store's user, or a restaurant, gets a 404. */
    private void requireStoreAccess(Long actorId, Long storeId) {
        if (!accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE, storeId)
                && !accessControl.has(actorId, Permissions.SETTLEMENT_VIEW, ScopeType.SUPPLIER_STORE, storeId)) {
            log.warn("Scope violation: user={} SupplierStore={} — reported as not found", actorId, storeId);
            throw new NotFoundException("SupplierStore", storeId);
        }
    }

    private List<CreditPayoutDtos.PayoutResponse> respond(List<CreditRepaymentPayout> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var repaymentIds = rows.stream().map(CreditRepaymentPayout::getCreditRepaymentId).toList();
        Map<Long, CreditRepayment> repaymentById = repayments.findAllById(repaymentIds).stream()
                .collect(Collectors.toMap(CreditRepayment::getId, r -> r));

        List<CreditPayment> payments = payouts.paymentsOf(repaymentIds);
        Map<Long, CreditInvoice> invoiceById = invoices.findAllById(
                        payments.stream().map(CreditPayment::getCreditInvoiceId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreditInvoice::getId, i -> i));
        Map<Long, List<CreditPayoutDtos.PayoutInvoice>> invoicesByRepayment = new HashMap<>();
        for (CreditPayment payment : payments) {
            var invoice = invoiceById.get(payment.getCreditInvoiceId());
            invoicesByRepayment.computeIfAbsent(payment.getCreditRepaymentId(), k -> new ArrayList<>())
                    .add(new CreditPayoutDtos.PayoutInvoice(payment.getCreditInvoiceId(),
                            invoice == null ? null : invoice.getInvoiceNumber(), money(payment.getAmount())));
        }

        Map<Long, SettlementRef> settlements = settlements(rows.stream()
                .map(CreditRepaymentPayout::getSettlementId).filter(java.util.Objects::nonNull).distinct().toList());
        Map<Long, CreditDirectory.OutletInfo> outlets = new HashMap<>();

        var result = new ArrayList<CreditPayoutDtos.PayoutResponse>();
        for (CreditRepaymentPayout payout : rows) {
            var repayment = repaymentById.get(payout.getCreditRepaymentId());
            var outlet = repayment == null ? null
                    : outlets.computeIfAbsent(repayment.getOutletId(), directory::outlet);
            var settlement = payout.getSettlementId() == null ? null : settlements.get(payout.getSettlementId());
            result.add(new CreditPayoutDtos.PayoutResponse(
                    payout.getId(),
                    payout.getCreditRepaymentId(),
                    repayment == null ? null : repayment.getCreditAgreementId(),
                    repayment == null ? null : repayment.getOutletId(),
                    outlet == null ? null : outlet.outletName(),
                    outlet == null ? null : outlet.restaurantName(),
                    money(payout.getAmount()),
                    payout.getCommissionRatePercent(),
                    money(payout.getCommissionAmount()),
                    // The stored commission, never the current rate (D-156).
                    money(payout.getAmount().subtract(payout.getCommissionAmount())),
                    payout.getStatus(),
                    payout.getSettlementId(),
                    settlement == null ? null : settlement.number(),
                    settlement == null ? null : settlement.date(),
                    payout.getAppliedAt(),
                    payout.getCreatedAt(),
                    invoicesByRepayment.getOrDefault(payout.getCreditRepaymentId(), List.of())));
        }
        return result;
    }

    private record SettlementRef(String number, LocalDate date) {
    }

    /** The settlement's number and date, read across the module edge like the other credit directories do. */
    private Map<Long, SettlementRef> settlements(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String marks = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        Map<Long, SettlementRef> byId = new HashMap<>();
        jdbc.query("select id, settlement_number, settlement_date from settlement where id in (" + marks + ")",
                rs -> {
                    byId.put(rs.getLong(1), new SettlementRef(rs.getString(2), rs.getObject(3, LocalDate.class)));
                }, ids.toArray());
        return byId;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
