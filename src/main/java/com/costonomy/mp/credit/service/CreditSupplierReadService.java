package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditReversalRules;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentClaimRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRepository;
import com.costonomy.mp.credit.repository.CreditPaymentReversalRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the supplier's Receivables screens read (plan B3): the home totals, one row per restaurant, the ageing and
 * the payment feeds. Read-only, and like the restaurant side every date is an India day from the credit clock and
 * every state is {@link CreditDueState}'s: overdue is never the status column (the hourly sweep may not have run),
 * so these figures agree with what each invoice itself reports. The server adds up the money; the app only shows it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditSupplierReadService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /** How many restaurants an ageing bucket lists. */
    static final int TOP_RESTAURANTS = 5;
    /** "Due this week": today and the six days after it. */
    static final int WEEK_DAYS = 7;

    /** The feed's open ends: credit_payment.paid_at is a TIMESTAMP, so the bounds stay inside its range. */
    private static final Instant FEED_FROM = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant FEED_TO = Instant.parse("2037-01-01T00:00:00Z");

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID,
            CreditInvoiceStatus.WRITTEN_OFF);
    /** Worst first, among the states an open invoice can be in. */
    private static final List<CreditDueState> WORST_FIRST = List.of(CreditDueState.OVERDUE, CreditDueState.IN_GRACE,
            CreditDueState.DUE_TODAY, CreditDueState.DUE_SOON, CreditDueState.DUE_LATER);
    private static final String[] BUCKETS = {"CURRENT", "D1_7", "D8_30", "D30_PLUS"};
    private static final Set<String> SORTS = Set.of("overdue", "owed", "nextDue");

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditPaymentRepository payments;
    private final CreditPaymentReversalRepository reversals;
    private final CreditPaymentClaimRepository claims;
    private final CreditAgreementService agreementService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    // Field-injected: Lombok's constructor would drop the @Qualifier, and there must be no unqualified Clock here.
    @Autowired
    @Qualifier("creditClock")
    private Clock clock;

    // ── Home totals ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditDtos.ReceivablesResponse receivables(Long actorId, Long storeId) {
        requireStore(actorId, storeId);
        LocalDate today = LocalDate.now(clock);
        var lines = agreements.findBySupplierStoreIdOrderByCreatedAtDesc(storeId);
        var open = openByAgreement(storeId);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal inGrace = BigDecimal.ZERO;
        BigDecimal dueToday = BigDecimal.ZERO;
        BigDecimal dueThisWeek = BigDecimal.ZERO;
        for (List<CreditInvoice> list : open.values()) {
            for (CreditInvoice invoice : list) {
                BigDecimal outstanding = invoice.outstanding();
                var state = stateOf(invoice, today);
                total = total.add(outstanding);
                if (state == CreditDueState.OVERDUE) {
                    overdue = overdue.add(outstanding);
                } else if (state == CreditDueState.IN_GRACE) {
                    inGrace = inGrace.add(outstanding);
                } else {
                    // Not yet due: DUE_TODAY, DUE_SOON or DUE_LATER. This week is today through today + 6.
                    long ahead = ChronoUnit.DAYS.between(today, invoice.getDueDate());
                    if (state == CreditDueState.DUE_TODAY) {
                        dueToday = dueToday.add(outstanding);
                    }
                    if (ahead < WEEK_DAYS) {
                        dueThisWeek = dueThisWeek.add(outstanding);
                    }
                }
            }
        }

        BigDecimal extended = BigDecimal.ZERO;
        BigDecimal drawn = BigDecimal.ZERO;
        BigDecimal availableToLend = BigDecimal.ZERO;
        int restaurants = 0;
        int active = 0;
        int suspended = 0;
        int requests = 0;
        int atLimit = 0;
        int overdueRestaurants = 0;
        for (CreditAgreement line : lines) {
            var list = open.getOrDefault(line.getId(), List.of());
            if (isListed(line, list)) {
                restaurants++;
            }
            if (list.stream().anyMatch(i -> stateOf(i, today) == CreditDueState.OVERDUE)) {
                overdueRestaurants++;
            }
            switch (line.getStatus()) {
                case ACTIVE -> {
                    active++;
                    extended = extended.add(line.getApprovedLimit());
                    drawn = drawn.add(line.getUtilizedAmount());
                    BigDecimal left = line.available().max(BigDecimal.ZERO);
                    availableToLend = availableToLend.add(left);
                    if (left.signum() == 0) {
                        atLimit++;
                    }
                }
                case SUSPENDED -> suspended++;
                case REQUESTED -> requests++;
                default -> {
                }
            }
        }
        int claimsWaiting = claimsWaitingByAgreement(storeId).values().stream().mapToInt(Integer::intValue).sum();

        var monthStart = today.withDayOfMonth(1);
        BigDecimal collected = payments.sumForStoreBetween(storeId,
                monthStart.atStartOfDay(clock.getZone()).toInstant(),
                monthStart.plusMonths(1).atStartOfDay(clock.getZone()).toInstant());

        var pending = new ArrayList<CreditDtos.PendingAction>();
        addPending(pending, CreditDtos.PendingActionKind.CLAIMS_WAITING, claimsWaiting);
        addPending(pending, CreditDtos.PendingActionKind.REQUESTS_PENDING, requests);
        addPending(pending, CreditDtos.PendingActionKind.OVERDUE_RESTAURANTS, overdueRestaurants);
        addPending(pending, CreditDtos.PendingActionKind.LINE_AT_LIMIT, atLimit);

        return new CreditDtos.ReceivablesResponse(today, m(total), m(overdue), m(inGrace), m(dueToday),
                m(dueThisWeek), m(collected),
                new CreditDtos.Exposure(m(extended), m(drawn), m(availableToLend)),
                new CreditDtos.Counts(restaurants, active, suspended, requests, claimsWaiting, overdueRestaurants),
                pending);
    }

    private static void addPending(List<CreditDtos.PendingAction> into, CreditDtos.PendingActionKind kind, int count) {
        if (count > 0) {
            into.add(new CreditDtos.PendingAction(kind, count));
        }
    }

    // ── Restaurants ──────────────────────────────────────────────────────

    /**
     * One row per credit line that is live or still owes something. {@code sort}: {@code overdue} (most overdue
     * first), {@code owed} (most owed first) or {@code nextDue} (earliest due date first, nothing due last); every
     * order ends on the agreement id, so a page never repeats or skips a row.
     */
    @Transactional(readOnly = true)
    public CreditDtos.PageOf<CreditDtos.ReceivableRestaurantResponse> restaurants(
            Long actorId, Long storeId, String sort, CreditAgreementStatus status, String q, int page, int size) {
        requireStore(actorId, storeId);
        String order = sort == null || sort.isBlank() ? "overdue" : sort;
        if (!SORTS.contains(order)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Sort by overdue, owed or nextDue.");
        }
        checkPage(page, size);
        size = Math.min(size, MAX_PAGE_SIZE);
        LocalDate today = LocalDate.now(clock);

        var open = openByAgreement(storeId);
        var waiting = claimsWaitingByAgreement(storeId);
        var lines = agreements.findBySupplierStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(a -> isListed(a, open.getOrDefault(a.getId(), List.of())))
                .filter(a -> status == null || a.getStatus() == status)
                .toList();
        var outlets = directory.outlets(lines.stream().map(CreditAgreement::getOutletId).distinct().toList());
        String needle = q == null ? "" : q.trim().toLowerCase();

        var rows = new ArrayList<CreditDtos.ReceivableRestaurantResponse>();
        for (CreditAgreement line : lines) {
            var outlet = outlets.get(line.getOutletId());
            String outletName = outlet == null ? null : outlet.outletName();
            String restaurantName = outlet == null ? null : outlet.restaurantName();
            if (!needle.isEmpty() && !contains(outletName, needle) && !contains(restaurantName, needle)) {
                continue;
            }
            rows.add(row(line, open.getOrDefault(line.getId(), List.of()), waiting.getOrDefault(line.getId(), 0),
                    outletName, restaurantName, today));
        }

        Comparator<CreditDtos.ReceivableRestaurantResponse> byId =
                Comparator.comparing(CreditDtos.ReceivableRestaurantResponse::agreementId);
        Comparator<CreditDtos.ReceivableRestaurantResponse> comparator = switch (order) {
            case "owed" -> Comparator.comparing(CreditDtos.ReceivableRestaurantResponse::owed).reversed().thenComparing(byId);
            case "nextDue" -> Comparator.comparing(CreditDtos.ReceivableRestaurantResponse::nextDueDate,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byId);
            default -> Comparator.comparing(CreditDtos.ReceivableRestaurantResponse::overdue).reversed().thenComparing(byId);
        };
        rows.sort(comparator);

        int from = (int) Math.min((long) page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return new CreditDtos.PageOf<>(List.copyOf(rows.subList(from, to)), page, size, rows.size(), to < rows.size());
    }

    private CreditDtos.ReceivableRestaurantResponse row(CreditAgreement line, List<CreditInvoice> open, int claimsWaiting,
                                                        String outletName, String restaurantName, LocalDate today) {
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        LocalDate nextDue = null;
        BigDecimal nextDueAmount = null;
        CreditDueState worst = null;
        for (CreditInvoice invoice : open) {
            owed = owed.add(invoice.outstanding());
            var state = stateOf(invoice, today);
            if (state == CreditDueState.OVERDUE) {
                overdue = overdue.add(invoice.outstanding());
            }
            if (worst == null || WORST_FIRST.indexOf(state) < WORST_FIRST.indexOf(worst)) {
                worst = state;
            }
            if (nextDue == null || invoice.getDueDate().isBefore(nextDue)) {
                nextDue = invoice.getDueDate();
            }
        }
        for (CreditInvoice invoice : open) {
            if (invoice.getDueDate().equals(nextDue)) {
                nextDueAmount = (nextDueAmount == null ? BigDecimal.ZERO : nextDueAmount).add(invoice.outstanding());
            }
        }
        BigDecimal utilization = line.getApprovedLimit().signum() == 0 ? null
                : line.getUtilizedAmount().multiply(BigDecimal.valueOf(100))
                .divide(line.getApprovedLimit(), 1, RoundingMode.HALF_UP);
        return new CreditDtos.ReceivableRestaurantResponse(line.getId(), line.getOutletId(), outletName, restaurantName,
                line.getStatus(), m(owed), m(overdue), nextDueAmount == null ? null : m(nextDueAmount), nextDue, worst,
                claimsWaiting, m(line.getApprovedLimit()), m(line.getUtilizedAmount()), utilization);
    }

    // ── Ageing ───────────────────────────────────────────────────────────

    /**
     * Open outstanding by days past the due date (not the grace-adjusted date), India time: not yet due or due today
     * is CURRENT, 1-7 days late D1_7 (in-grace invoices land here), 8-30 D8_30, more D30_PLUS. Every open invoice is in
     * exactly one bucket, so the buckets add up to the receivable.
     */
    @Transactional(readOnly = true)
    public CreditDtos.AgeingResponse ageing(Long actorId, Long storeId) {
        requireStore(actorId, storeId);
        LocalDate today = LocalDate.now(clock);
        var open = openByAgreement(storeId);

        BigDecimal[] amount = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] count = new int[4];
        List<Map<Long, BigDecimal>> perLineAmount = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>());
        List<Map<Long, Integer>> perLineCount = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>());
        for (var entry : open.entrySet()) {
            for (CreditInvoice invoice : entry.getValue()) {
                int b = bucketOf(ChronoUnit.DAYS.between(invoice.getDueDate(), today));
                amount[b] = amount[b].add(invoice.outstanding());
                count[b]++;
                perLineAmount.get(b).merge(entry.getKey(), invoice.outstanding(), BigDecimal::add);
                perLineCount.get(b).merge(entry.getKey(), 1, Integer::sum);
            }
        }

        var lineIds = open.keySet();
        var lineById = agreements.findAllById(lineIds).stream().collect(Collectors.toMap(CreditAgreement::getId, a -> a));
        var outlets = directory.outlets(lineById.values().stream().map(CreditAgreement::getOutletId).distinct().toList());

        var buckets = new ArrayList<CreditDtos.AgeingBucket>();
        BigDecimal total = BigDecimal.ZERO;
        for (int bucket = 0; bucket < 4; bucket++) {
            final int b = bucket;
            total = total.add(amount[b]);
            var top = perLineAmount.get(b).entrySet().stream()
                    .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(TOP_RESTAURANTS)
                    .map(e -> {
                        var outlet = outlets.get(lineById.get(e.getKey()).getOutletId());
                        return new CreditDtos.AgeingBucketRestaurant(e.getKey(),
                                outlet == null ? null : outlet.outletName(),
                                outlet == null ? null : outlet.restaurantName(),
                                m(e.getValue()), perLineCount.get(b).get(e.getKey()));
                    })
                    .toList();
            buckets.add(new CreditDtos.AgeingBucket(BUCKETS[b], m(amount[b]), count[b],
                    perLineAmount.get(b).size(), top));
        }
        return new CreditDtos.AgeingResponse(today, m(total), buckets);
    }

    static int bucketOf(long daysPastDue) {
        if (daysPastDue <= 0) {
            return 0;
        }
        if (daysPastDue <= 7) {
            return 1;
        }
        return daysPastDue <= 30 ? 2 : 3;
    }

    // ── Payments ─────────────────────────────────────────────────────────

    /** The store's payments newest first, between two India days (both inclusive; either may be left out). */
    @Transactional(readOnly = true)
    public CreditDtos.PageOf<CreditDtos.PaymentFeedItem> storePayments(
            Long actorId, Long storeId, LocalDate from, LocalDate to, CreditPaymentSource source, int page, int size) {
        requireStore(actorId, storeId);
        checkPage(page, size);
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The end date can't be before the start date.");
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        Instant start = from == null ? FEED_FROM : from.atStartOfDay(clock.getZone()).toInstant();
        Instant end = to == null ? FEED_TO : to.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        var result = payments.pageForStore(storeId, source, start, end, PageRequest.of(page, size));
        return respond(result.getContent(), page, size, result.getTotalElements(), result.hasNext());
    }

    /** One agreement's payments, newest first. Same access as the agreement itself: either side, else 404. */
    @Transactional(readOnly = true)
    public CreditDtos.PageOf<CreditDtos.PaymentFeedItem> agreementPayments(
            Long actorId, Long agreementId, int page, int size) {
        agreementService.loadForEitherSide(actorId, agreementId);
        checkPage(page, size);
        size = Math.min(size, MAX_PAGE_SIZE);
        var result = payments.findByCreditAgreementIdOrderByPaidAtDescIdDesc(agreementId, PageRequest.of(page, size));
        return respond(result.getContent(), page, size, result.getTotalElements(), result.hasNext());
    }

    private CreditDtos.PageOf<CreditDtos.PaymentFeedItem> respond(
            List<CreditPayment> rows, int page, int size, long total, boolean hasNext) {
        var invoiceNumbers = invoices.findAllById(rows.stream().map(CreditPayment::getCreditInvoiceId).distinct().toList())
                .stream().collect(Collectors.toMap(CreditInvoice::getId, CreditInvoice::getInvoiceNumber));
        var lineById = agreements.findAllById(rows.stream().map(CreditPayment::getCreditAgreementId).distinct().toList())
                .stream().collect(Collectors.toMap(CreditAgreement::getId, a -> a));
        var outlets = directory.outlets(lineById.values().stream().map(CreditAgreement::getOutletId).distinct().toList());
        var reversedAt = reversals.findByCreditPaymentIdIn(rows.stream().map(CreditPayment::getId).toList()).stream()
                .collect(Collectors.toMap(r -> r.getCreditPaymentId(), r -> r.getReversedAt()));
        var writtenOff = invoices.findAllById(rows.stream().map(CreditPayment::getCreditInvoiceId).distinct().toList())
                .stream().filter(i -> i.getStatus() == CreditInvoiceStatus.WRITTEN_OFF).map(CreditInvoice::getId)
                .collect(Collectors.toSet());
        LocalDate today = LocalDate.now(clock);
        var items = rows.stream().map(p -> {
            var line = lineById.get(p.getCreditAgreementId());
            var outlet = outlets.get(line.getOutletId());
            // Undo (B6): what the supplier may still take back, decided here and never by the app.
            Instant reversed = reversedAt.get(p.getId());
            LocalDate until = reversed == null && CreditReversalRules.sourceAllowed(p.getSource())
                    ? CreditReversalRules.lastDay(p.getCreatedAt(), p.getMethod(), clock.getZone()) : null;
            boolean reversible = until != null && CreditReversalRules.isOpen(until, today)
                    && !writtenOff.contains(p.getCreditInvoiceId());
            Long receiptId = p.getSource() == CreditPaymentSource.SUPPLIER_RECORDED ? p.getCreditRepaymentId() : null;
            return new CreditDtos.PaymentFeedItem(p.getId(), p.getPaidAt(),
                    LocalDate.ofInstant(p.getPaidAt(), clock.getZone()), line.getId(), line.getOutletId(),
                    outlet == null ? null : outlet.outletName(), outlet == null ? null : outlet.restaurantName(),
                    p.getCreditInvoiceId(), invoiceNumbers.get(p.getCreditInvoiceId()), m(p.getAmount()),
                    p.getSource(), p.getMethod(), p.getReference(), receiptId, reversible, until, reversed);
        }).toList();
        return new CreditDtos.PageOf<>(items, page, size, total, hasNext);
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Same visibility as the store's agreements and claims; anyone else learns nothing exists (404). */
    private void requireStore(Long actorId, Long storeId) {
        if (!accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE, storeId)
                && !accessControl.has(actorId, Permissions.CREDIT_REQUEST_VIEW, ScopeType.SUPPLIER_STORE, storeId)) {
            log.warn("Scope violation: user={} SupplierStore={} — reported as not found", actorId, storeId);
            throw new NotFoundException("SupplierStore", storeId);
        }
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Page must be 0 or more and size at least 1.");
        }
    }

    /** The store's open invoices (not PAID, not WRITTEN_OFF) grouped by agreement, each list in id order. */
    private Map<Long, List<CreditInvoice>> openByAgreement(Long storeId) {
        var byAgreement = new LinkedHashMap<Long, List<CreditInvoice>>();
        invoices.findBySupplierStoreIdAndStatusNotIn(storeId, SETTLED).stream()
                .sorted(Comparator.comparing(CreditInvoice::getId))
                .forEach(i -> byAgreement.computeIfAbsent(i.getCreditAgreementId(), k -> new ArrayList<>()).add(i));
        return byAgreement;
    }

    private Map<Long, Integer> claimsWaitingByAgreement(Long storeId) {
        var counts = new HashMap<Long, Integer>();
        for (Object[] row : claims.countsByAgreementForStore(storeId, CreditClaimStatus.SUBMITTED)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private static CreditDueState stateOf(CreditInvoice invoice, LocalDate today) {
        return CreditDueState.of(invoice.getStatus(), invoice.getDueDate(), invoice.getOverdueAfter(), today);
    }

    /** Live lines (active or suspended) and any line that still owes, whatever its status. */
    private static boolean isListed(CreditAgreement line, List<CreditInvoice> open) {
        return line.getStatus() == CreditAgreementStatus.ACTIVE || line.getStatus() == CreditAgreementStatus.SUSPENDED
                || !open.isEmpty();
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase().contains(needle);
    }

    /** Money as the API sends it: two decimals. */
    private static BigDecimal m(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
