package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.domain.WalletTopUp;
import com.costonomy.mp.wallet.domain.WalletTopUpStatus;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.invoice.domain.BillStatus;
import com.costonomy.mp.wallet.invoice.service.BillStatuses;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The wallet history a restaurant scrolls through (D-108): every movement of the
 * balance, newest first, with the filters a person actually uses.
 *
 * <p><b>Two tables, one list.</b> Almost everything is a ledger row. The exception is
 * a top-up that was paid and then returned (D-107): the money left the customer's bank
 * and came back, and the wallet never moved, so there is no ledger row. It is shown
 * anyway, as a {@code TOP_UP} with status {@code RETURNED} and no balance, because the
 * customer will look for a debit on their bank statement and it must be explained.
 * The two sources are read separately, each with its own index-friendly keyset query,
 * and merged in memory: the first {@code size + 1} of the merge can only come from the
 * first {@code size + 1} of each.
 *
 * <p><b>Keyset, not offset.</b> The position is {@code (created_at, source, id)}. Ledger
 * rows are written under the wallet lock in one transaction with the balance, so
 * {@code id} order is balance order, and rows created in the same microsecond are told
 * apart by it. A new row can only arrive at the front of a newest-first list, so a page
 * boundary never duplicates or skips a row however many arrive while the customer reads
 * (an offset would shift by exactly that many).
 *
 * <p><b>Months are Asia/Kolkata months</b>, worked out in Java and turned into instant
 * ranges, never by asking the database to convert zones: the answer must not depend on
 * how a database session happens to be configured. A payment at 19:00 UTC on 30 September
 * is 00:30 on 1 October at the restaurant.
 *
 * <p><b>Totals are the ledger's, unfiltered by kind or status.</b> "Added" and "spent" for
 * a month are what the statement for that month would say, so the header and the
 * downloadable statement can never disagree because a filter was on.
 *
 * <p><b>Bills (D-116).</b> Each ledger row carries its bill status, read in the same query as the page (three left
 * joins on unique keys, see {@link BillStatuses}), so a page costs no extra query per row; the {@code bills} filter is
 * a where-clause term of that query, so paging stays exact. The banner's counts and each month's
 * {@code billsPending} come from one more grouped query per request, unfiltered like the totals.
 */
@Service
@RequiredArgsConstructor
public class WalletHistoryService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** More than this many months in one request is a mistake or an attempt to make the database work. */
    static final int MAX_MONTHS = 24;
    /** Enough for any outlet's history; a bound on the loop that finds which months have any. */
    private static final int MAX_AVAILABLE_MONTHS = 240;

    /** Ledger rows sort after (come first in newest-first order than) top-ups at the same instant. */
    private static final int LEDGER = 1;
    private static final int TOP_UP = 0;

    /**
     * What the customer is told about a row: the ledger's rows are done, a withdrawal may not be.
     * A withdrawal whose refund the provider refused and that went back into the wallet (refund
     * status REVERSED, D-110) is RETURNED, like a top-up that went back to the bank: the money
     * came back, and the credit that says so is its own row (WITHDRAWAL_REVERSAL).
     */
    public enum Status { COMPLETED, IN_PROGRESS, FAILED, RETURNED }

    /** A parsed request. Empty sets mean "no filter". */
    public record Filter(List<YearMonth> months, Set<WalletEntryKind> kinds, Set<Status> statuses,
                         Set<BillStatus> bills) {
    }

    private record Cursor(Instant at, int source, long id) {
    }

    private record Row(Instant at, int source, long id, WalletDtos.HistoryItem item) {
    }

    private final EntityManager em;
    private final WalletService wallets;
    private final BillStatuses billStatuses;

    // ── Parsing: every bad value is a 400 that says which ────────────────

    public static Filter parseFilter(String months, String kinds, String statuses) {
        return parseFilter(months, kinds, statuses, null);
    }

    public static Filter parseFilter(String months, String kinds, String statuses, String bills) {
        var parsedMonths = new TreeSet<YearMonth>(Comparator.reverseOrder());
        for (String token : split(months)) {
            try {
                // Strict: "2026-9" is not a month here, and neither is "2026-13".
                if (!token.matches("\\d{4}-\\d{2}")) {
                    throw new IllegalArgumentException(token);
                }
                parsedMonths.add(YearMonth.parse(token));
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "'%s' is not a month. Use yyyy-MM, like 2026-09.".formatted(token));
            }
        }
        if (parsedMonths.size() > MAX_MONTHS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose at most %d months at a time.".formatted(MAX_MONTHS));
        }
        var parsedKinds = EnumSet.noneOf(WalletEntryKind.class);
        for (String token : split(kinds)) {
            try {
                parsedKinds.add(WalletEntryKind.valueOf(token.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "'%s' is not a kind of entry.".formatted(token));
            }
        }
        var parsedStatuses = EnumSet.noneOf(Status.class);
        for (String token : split(statuses)) {
            try {
                parsedStatuses.add(Status.valueOf(token.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "'%s' is not a status.".formatted(token));
            }
        }
        var parsedBills = EnumSet.noneOf(BillStatus.class);
        for (String token : split(bills)) {
            BillStatus bill = null;
            try {
                bill = BillStatus.valueOf(token.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
            if (bill == null || !BillStatuses.FILTERABLE.contains(bill)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "'%s' is not a bill status. Use PENDING, READING, ADDED, REVIEWED or UNREADABLE."
                                .formatted(token));
            }
            parsedBills.add(bill);
        }
        return new Filter(List.copyOf(parsedMonths), parsedKinds, parsedStatuses, parsedBills);
    }

    public static int parseSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "size must be between 1 and %d.".formatted(MAX_SIZE));
        }
        return size;
    }

    private static List<String> split(String csv) {
        var out = new LinkedHashSet<String>();
        if (csv != null) {
            for (String token : csv.split(",")) {
                if (!token.isBlank()) {
                    out.add(token.trim());
                }
            }
        }
        return List.copyOf(out);
    }

    // ── The history ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WalletDtos.HistoryResponse history(Long outletId, Filter filter, String cursorText, int size) {
        Cursor cursor = decode(cursorText);
        Long walletId = wallets.find(outletId).map(w -> w.getId()).orElse(null);

        boolean ledgerWanted = walletId != null && ledgerWanted(filter);
        boolean topUpsWanted = topUpsWanted(filter);

        var rows = new ArrayList<Row>();
        if (ledgerWanted) {
            rows.addAll(ledgerRows(walletId, outletId, filter, cursor, size + 1));
        }
        if (topUpsWanted) {
            rows.addAll(returnedTopUps(outletId, filter, cursor, size + 1));
        }
        rows.sort(Comparator.comparing(Row::at).thenComparingInt(Row::source).thenComparingLong(Row::id).reversed());

        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        String next = more ? encode(page.get(page.size() - 1)) : null;

        // Which months' totals to send: the ones asked for, else the ones on this page,
        // so every month header the client draws has its figures.
        var monthsForTotals = new TreeSet<YearMonth>(Comparator.reverseOrder());
        if (!filter.months().isEmpty()) {
            monthsForTotals.addAll(filter.months());
        } else {
            page.forEach(row -> monthsForTotals.add(YearMonth.from(row.at().atZone(ZONE))));
        }
        // One grouped query for the banner and every month's pending bills, whatever the filters. L2: the app reads the
        // banner from the first page only, so a later page (a cursor) gets billSummary null and only the month counts.
        boolean firstPage = cursor == null;
        var bills = billStatuses.summary(walletId, List.copyOf(monthsForTotals), firstPage);
        var totals = new ArrayList<WalletDtos.MonthTotal>();
        for (var month : monthsForTotals) {
            totals.add(monthTotal(walletId, month, bills.pendingByMonth().getOrDefault(month, 0)));
        }

        return new WalletDtos.HistoryResponse(
                page.stream().map(Row::item).toList(), totals, availableMonths(walletId, outletId), next,
                firstPage ? new WalletDtos.BillSummary(bills.pending(), bills.reading(), bills.unreadable()) : null);
    }

    /**
     * The {@code bill} the History list shows for each of these ledger entries of one wallet (D-116), for lists built
     * elsewhere (the wallet's {@code recent[]}): the same rule ({@link BillStatuses#forList}) in one query for the whole
     * list. An entry with no bill status is absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<Long, WalletDtos.Bill> billsOf(Long walletId, java.util.Collection<Long> entryIds) {
        var out = new HashMap<Long, WalletDtos.Bill>();
        billStatuses.forListOf(walletId, entryIds).forEach((id, status) -> out.put(id, new WalletDtos.Bill(status.name())));
        return out;
    }

    private static boolean ledgerWanted(Filter filter) {
        if (filter.statuses().isEmpty()) {
            return true;
        }
        // A reversed withdrawal is a ledger row too, and is RETURNED.
        return filter.statuses().contains(Status.COMPLETED) || filter.statuses().contains(Status.IN_PROGRESS)
                || filter.statuses().contains(Status.RETURNED);
    }

    private static boolean topUpsWanted(Filter filter) {
        if (!filter.bills().isEmpty()) {
            return false; // a returned top-up has no bill status, so a bill filter never matches one
        }
        boolean kindOk = filter.kinds().isEmpty() || filter.kinds().contains(WalletEntryKind.TOP_UP);
        boolean statusOk = filter.statuses().isEmpty() || filter.statuses().contains(Status.RETURNED);
        return kindOk && statusOk;
    }

    @SuppressWarnings("unchecked")
    private List<Row> ledgerRows(Long walletId, Long outletId, Filter filter, Cursor cursor, int limit) {
        var jpql = new StringBuilder("""
                select t, r.status, %s from WalletTransaction t
                  left join Refund r on r.id = t.refundId
                  %s
                 where t.walletId = :wallet
                """.formatted(BillStatuses.COLUMNS, BillStatuses.JOINS));
        var billStart = billStatuses.start();
        boolean billFiltered = !filter.bills().isEmpty();
        if (billFiltered) {
            jpql.append(" and ").append(BillStatuses.matching(filter.bills(), billStart.isPresent()));
        }
        if (!filter.months().isEmpty()) {
            jpql.append(" and ").append(rangeClause("t", filter.months().size()));
        }
        if (!filter.kinds().isEmpty()) {
            jpql.append(" and t.kind in :kinds");
        }
        // A withdrawal whose refund is not COMPLETED is on its way. FAILED, NEEDS_REVIEW and REJECTED
        // are on their way too: the customer's question is "has it arrived", and a retry, a check of
        // the provider's list or a person working on it is not a different answer. REVERSED is the
        // one that ended the other way: the money is back in the wallet, RETURNED.
        boolean wantDone = filter.statuses().isEmpty() || filter.statuses().contains(Status.COMPLETED);
        boolean wantOnItsWay = filter.statuses().isEmpty() || filter.statuses().contains(Status.IN_PROGRESS);
        boolean wantReturned = filter.statuses().isEmpty() || filter.statuses().contains(Status.RETURNED);
        boolean statusFiltered = !(wantDone && wantOnItsWay && wantReturned);
        if (statusFiltered) {
            var wanted = new ArrayList<String>();
            if (wantDone) {
                wanted.add("not (t.kind = :withdrawal and r.id is not null and r.status <> :sent)");
            }
            if (wantOnItsWay) {
                wanted.add("(t.kind = :withdrawal and r.id is not null and r.status <> :sent and r.status <> :reversed)");
            }
            if (wantReturned) {
                wanted.add("(t.kind = :withdrawal and r.id is not null and r.status = :reversed)");
            }
            jpql.append(" and (").append(String.join(" or ", wanted)).append(")");
        }
        if (cursor != null) {
            jpql.append(cursor.source() == LEDGER
                    ? " and (t.createdAt < :cAt or (t.createdAt = :cAt and t.id < :cId))"
                    // A top-up sorts after a ledger row at the same instant, so a cursor on one
                    // has already passed every ledger row at that instant.
                    : " and t.createdAt < :cAt");
        }
        jpql.append(" order by t.createdAt desc, t.id desc");

        Query query = em.createQuery(jpql.toString());
        query.setParameter("wallet", walletId);
        bindRanges(query, filter.months());
        if (!filter.kinds().isEmpty()) {
            query.setParameter("kinds", filter.kinds());
        }
        // Bound only where the clause built above names it: a parameter the query does not have is an error.
        if (statusFiltered) {
            query.setParameter("withdrawal", WalletEntryKind.WITHDRAWAL);
            if (wantDone || wantOnItsWay) {
                query.setParameter("sent", RefundStatus.COMPLETED);
            }
            if (wantOnItsWay || wantReturned) {
                query.setParameter("reversed", RefundStatus.REVERSED);
            }
        }
        if (cursor != null) {
            query.setParameter("cAt", cursor.at());
            if (cursor.source() == LEDGER) {
                query.setParameter("cId", cursor.id());
            }
        }
        if (billFiltered && BillStatuses.usesStart(filter.bills(), billStart.isPresent())) {
            BillStatuses.bindStart(query, billStart.get());
        }
        query.setMaxResults(limit);
        List<Object[]> found = query.getResultList();

        // Where each top-up's money came from: one read for the whole page.
        var topUpIds = new LinkedHashSet<Long>();
        for (var row : found) {
            var entry = (WalletTransaction) row[0];
            if (entry.getKind() == WalletEntryKind.TOP_UP && entry.getReference() != null
                    && entry.getReference().startsWith("topup-")) {
                try {
                    topUpIds.add(Long.parseLong(entry.getReference().substring("topup-".length())));
                } catch (NumberFormatException ignored) {
                    // A reference we did not write; no instrument rather than a wrong one.
                }
            }
        }
        Map<Long, WalletTopUp> topUpById = new HashMap<>();
        if (!topUpIds.isEmpty()) {
            List<WalletTopUp> loaded = em.createQuery(
                            "select t from WalletTopUp t where t.id in :ids and t.outletId = :outlet",
                            WalletTopUp.class)
                    .setParameter("ids", topUpIds).setParameter("outlet", outletId).getResultList();
            loaded.forEach(t -> topUpById.put(t.getId(), t));
        }

        var out = new ArrayList<Row>();
        for (var row : found) {
            var entry = (WalletTransaction) row[0];
            var refundStatus = (RefundStatus) row[1];
            boolean withdrawal = entry.getKind() == WalletEntryKind.WITHDRAWAL;
            String status = statusOf(entry.getKind(), refundStatus).name();
            String instrument = null;
            if (entry.getKind() == WalletEntryKind.TOP_UP && entry.getReference() != null
                    && entry.getReference().startsWith("topup-")) {
                try {
                    var topUp = topUpById.get(Long.parseLong(entry.getReference().substring("topup-".length())));
                    instrument = topUp == null ? null : instrumentOf(topUp);
                } catch (NumberFormatException ignored) {
                    // see above
                }
            }
            BillStatus bill = BillStatuses.forList(
                    BillStatuses.facts(entry.getKind(), entry.getCreatedAt(), row, 2), billStart);
            var item = new WalletDtos.HistoryItem("L" + entry.getId(), entry.getId(), entry.getDirection(),
                    entry.getKind().name(), entry.getAmount(), entry.getBalanceAfter(),
                    entry.getSupplierOrderId(), entry.getReason(), status,
                    withdrawal && refundStatus != null ? refundStatus.name() : null,
                    instrument, entry.getCreatedAt(), bill == null ? null : new WalletDtos.Bill(bill.name()));
            out.add(new Row(entry.getCreatedAt(), LEDGER, entry.getId(), item));
        }
        return out;
    }

    /** What the customer is told about a ledger row; shared by the list and the detail so they never differ. */
    static Status statusOf(WalletEntryKind kind, RefundStatus refundStatus) {
        boolean withdrawal = kind == WalletEntryKind.WITHDRAWAL;
        return !withdrawal || refundStatus == null || refundStatus == RefundStatus.COMPLETED
                ? Status.COMPLETED
                : refundStatus == RefundStatus.REVERSED ? Status.RETURNED : Status.IN_PROGRESS;
    }

    private List<Row> returnedTopUps(Long outletId, Filter filter, Cursor cursor, int limit) {
        var jpql = new StringBuilder("""
                select t from WalletTopUp t
                 where t.outletId = :outlet and t.status = :refunded
                """);
        if (!filter.months().isEmpty()) {
            jpql.append(" and ").append(rangeClause("t", filter.months().size()));
        }
        if (cursor != null) {
            jpql.append(cursor.source() == TOP_UP
                    ? " and (t.createdAt < :cAt or (t.createdAt = :cAt and t.id < :cId))"
                    // A ledger row sorts before a top-up at the same instant: a cursor on a
                    // ledger row has not yet reached the top-ups at that instant.
                    : " and t.createdAt <= :cAt");
        }
        jpql.append(" order by t.createdAt desc, t.id desc");
        var query = em.createQuery(jpql.toString(), WalletTopUp.class);
        query.setParameter("outlet", outletId);
        query.setParameter("refunded", WalletTopUpStatus.REFUNDED);
        bindRanges(query, filter.months());
        if (cursor != null) {
            query.setParameter("cAt", cursor.at());
            if (cursor.source() == TOP_UP) {
                query.setParameter("cId", cursor.id());
            }
        }
        query.setMaxResults(limit);

        var out = new ArrayList<Row>();
        for (var topUp : query.getResultList()) {
            // Dated when it was started, which is when the money left the bank. It is the
            // one timestamp on the row that never changes, which a cursor needs.
            var item = new WalletDtos.HistoryItem("T" + topUp.getId(), topUp.getId(), WalletDirection.CREDIT,
                    WalletEntryKind.TOP_UP.name(), topUp.getAmount(), null, null,
                    "Payment returned to your card or bank", Status.RETURNED.name(), null,
                    instrumentOf(topUp), topUp.getCreatedAt(), null);
            out.add(new Row(topUp.getCreatedAt(), TOP_UP, topUp.getId(), item));
        }
        return out;
    }

    /** A short display string for where a top-up's money came from, or null when we do not know. */
    static String instrumentOf(WalletTopUp topUp) {
        String method = topUp.getPaymentMethod();
        if (method == null) {
            return null;
        }
        String detail = topUp.getPaymentDetail();
        return switch (method) {
            case "card" -> detail == null ? "Card" : "Card •" + detail;
            case "upi" -> "UPI";
            case "netbanking" -> "Netbanking";
            case "wallet" -> detail == null ? "Wallet" : detail;
            case "emi" -> "EMI";
            default -> null;
        };
    }

    // ── Month totals and the months there are ────────────────────────────

    private WalletDtos.MonthTotal monthTotal(Long walletId, YearMonth month, int billsPending) {
        BigDecimal added = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        if (walletId != null) {
            List<Object[]> result = em.createQuery("""
                            select coalesce(sum(case when t.direction = :credit then t.amount else 0 end), 0),
                                   coalesce(sum(case when t.direction = :debit then t.amount else 0 end), 0)
                              from WalletTransaction t
                             where t.walletId = :wallet and t.createdAt >= :from and t.createdAt < :to
                            """, Object[].class)
                    .setParameter("credit", WalletDirection.CREDIT)
                    .setParameter("debit", WalletDirection.DEBIT)
                    .setParameter("wallet", walletId)
                    .setParameter("from", startOf(month))
                    .setParameter("to", startOf(month.plusMonths(1)))
                    .getResultList();
            added = new BigDecimal(result.get(0)[0].toString());
            spent = new BigDecimal(result.get(0)[1].toString());
        }
        return new WalletDtos.MonthTotal(month.toString(), added, spent, billsPending);
    }

    /**
     * Every month with anything to show, newest first. Found by asking, month by month, whether
     * there is a row — because grouping by month in SQL would need the database to convert time
     * zones, and this must not depend on it. The loop is bounded by the first and last row.
     */
    private List<String> availableMonths(Long walletId, Long outletId) {
        Instant first = null;
        Instant last = null;
        if (walletId != null) {
            Object[] bounds = em.createQuery(
                            "select min(t.createdAt), max(t.createdAt) from WalletTransaction t where t.walletId = :w",
                            Object[].class).setParameter("w", walletId).getSingleResult();
            first = (Instant) bounds[0];
            last = (Instant) bounds[1];
        }
        Object[] topUpBounds = em.createQuery("""
                        select min(t.createdAt), max(t.createdAt) from WalletTopUp t
                         where t.outletId = :o and t.status = :s
                        """, Object[].class)
                .setParameter("o", outletId).setParameter("s", WalletTopUpStatus.REFUNDED).getSingleResult();
        first = earliest(first, (Instant) topUpBounds[0]);
        last = latest(last, (Instant) topUpBounds[1]);
        if (first == null || last == null) {
            return List.of();
        }
        var months = new ArrayList<String>();
        var month = YearMonth.from(last.atZone(ZONE));
        var stop = YearMonth.from(first.atZone(ZONE));
        for (int i = 0; i < MAX_AVAILABLE_MONTHS && !month.isBefore(stop); i++, month = month.minusMonths(1)) {
            if (hasAnything(walletId, outletId, month)) {
                months.add(month.toString());
            }
        }
        return months;
    }

    private boolean hasAnything(Long walletId, Long outletId, YearMonth month) {
        var from = startOf(month);
        var to = startOf(month.plusMonths(1));
        if (walletId != null && !em.createQuery("""
                        select t.id from WalletTransaction t
                         where t.walletId = :w and t.createdAt >= :from and t.createdAt < :to
                        """, Long.class)
                .setParameter("w", walletId).setParameter("from", from).setParameter("to", to)
                .setMaxResults(1).getResultList().isEmpty()) {
            return true;
        }
        return !em.createQuery("""
                        select t.id from WalletTopUp t
                         where t.outletId = :o and t.status = :s and t.createdAt >= :from and t.createdAt < :to
                        """, Long.class)
                .setParameter("o", outletId).setParameter("s", WalletTopUpStatus.REFUNDED)
                .setParameter("from", from).setParameter("to", to)
                .setMaxResults(1).getResultList().isEmpty();
    }

    private static Instant earliest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    // ── Months as instant ranges ─────────────────────────────────────────

    /** Midnight at the start of the month in Asia/Kolkata, as an instant. */
    static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZONE).toInstant();
    }

    private static String rangeClause(String alias, int count) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            parts.add("(%1$s.createdAt >= :ms%2$d and %1$s.createdAt < :me%2$d)".formatted(alias, i));
        }
        return "(" + String.join(" or ", parts) + ")";
    }

    private static void bindRanges(Query query, List<YearMonth> months) {
        for (int i = 0; i < months.size(); i++) {
            query.setParameter("ms" + i, startOf(months.get(i)));
            query.setParameter("me" + i, startOf(months.get(i).plusMonths(1)));
        }
    }

    // ── Cursor: opaque to the client, strict to us ───────────────────────

    private static String encode(Row row) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, row.at());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString("%d:%d:%d".formatted(micros, row.source(), row.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var parts = new String(Base64.getUrlDecoder().decode(text.trim()), StandardCharsets.UTF_8).split(":");
            int source = Integer.parseInt(parts[1]);
            if (parts.length != 3 || (source != LEDGER && source != TOP_UP)) {
                throw new IllegalArgumentException("shape");
            }
            return new Cursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), source,
                    Long.parseLong(parts[2]));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "That page marker isn't valid. Start again from the top.");
        }
    }
}
