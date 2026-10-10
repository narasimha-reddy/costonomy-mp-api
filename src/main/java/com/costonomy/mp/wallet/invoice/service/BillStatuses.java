package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.invoice.domain.BillStatus;
import com.costonomy.mp.wallet.invoice.domain.InvoiceStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bill status of wallet payments (D-116), worked out in one place so the History, its filter and counts, the
 * details page and the statement can never disagree.
 *
 * <p><b>Which payments need a bill.</b> Only payments made from the wallet: {@code ORDER_PAYMENT} and
 * {@code QUICKSCAN_PAYMENT} whose entry status is COMPLETED. For these two kinds the History's status
 * ({@code WalletHistoryService.statusOf}) is always COMPLETED, since only a withdrawal can be on its way or returned;
 * two kinds of payment ended the other way and are not eligible: a QuickScan payment whose payout failed and whose
 * money came back (a {@code QUICKSCAN_RETURN} with reference {@code quickscan-return-<id>}), and an order payment
 * whose order was cancelled and paid back in full (an {@code ORDER_REFUND} with the same {@code supplier_order_id},
 * written once per order by {@code WalletService.refundFor}). A partial money-back ({@code DISPUTE_REFUND}) keeps the
 * payment eligible: goods were delivered, so a bill is still owed.
 *
 * <p><b>The status.</b> A bill that exists always shows its own status, whatever the date: READING, UNREADABLE, and
 * READ as REVIEWED once a review was saved ({@code reviewed_at}, written with {@code review_json}) else ADDED; an
 * UNREADABLE bill that a person then filled in by hand (a review saved) is REVIEWED too (owner's decision, D-116 M3). Without
 * a bill ('a bill wins': should a waiver row exist next to a bill, the bill decides): 'No bill needed' (a waiver) is NOT_REQUIRED (no chip in the list); an eligible payment created on or after
 * the tracking start ({@link BillTracking}) is PENDING; anything else has no bill status.
 *
 * <p><b>In SQL.</b> The same rules are written once as JPQL fragments over a ledger row aliased {@code t}, with three
 * left joins on unique keys (the bill, the waiver, the QuickScan return) and one {@code exists} on
 * {@code ix_wallet_txn_order} (the order's cancellation; not a join, since (supplier_order_id, kind) has no unique key), so the list's filter, the counts and the
 * statement are evaluated by the database, a page or a period at a time, never one query per row.
 */
@Component
@RequiredArgsConstructor
public class BillStatuses {

    /** The kinds of entry that take a bill (D-113). */
    public static final Set<WalletEntryKind> KINDS =
            EnumSet.of(WalletEntryKind.ORDER_PAYMENT, WalletEntryKind.QUICKSCAN_PAYMENT);
    /** The values the History's {@code bills} filter accepts. */
    public static final Set<BillStatus> FILTERABLE = EnumSet.of(BillStatus.PENDING, BillStatus.READING,
            BillStatus.ADDED, BillStatus.REVIEWED, BillStatus.UNREADABLE);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KIND = "com.costonomy.mp.wallet.domain.WalletEntryKind.";
    private static final String INV = "com.costonomy.mp.wallet.invoice.domain.InvoiceStatus.";
    private static final int CHUNK = 1000;

    /** The bill, the waiver and the QuickScan return of a ledger row aliased {@code t}: each on a unique key. */
    public static final String JOINS = """
             left join WalletEntryInvoice bi on bi.walletTransactionId = t.id
             left join WalletEntryInvoiceWaiver bw on bw.walletTransactionId = t.id
             left join WalletTransaction br on br.walletId = t.walletId and t.kind = %1$sQUICKSCAN_PAYMENT
                   and br.kind = %1$sQUICKSCAN_RETURN
                   and br.reference = concat('quickscan-return-', substring(t.reference, 11))
            """.formatted(KIND);

    /** An order payment whose order was cancelled and paid back in full (H1). Served by {@code ix_wallet_txn_order}. */
    static final String CANCELLED = """
            (t.kind = %1$sORDER_PAYMENT and exists (select 1 from WalletTransaction o
                   where o.supplierOrderId = t.supplierOrderId and o.walletId = t.walletId
                     and o.kind = %1$sORDER_REFUND))""".formatted(KIND);

    /**
     * Selected after the row: bill status, reviewed at, waiver id, and whether the money came back in full (the
     * QuickScan return, or the order's cancellation; non-null when it did). See {@link #facts}.
     */
    public static final String COLUMNS = "bi.status, bi.reviewedAt, bw.id, case when br.id is not null or "
            + CANCELLED + " then 1 end";

    /** A payment that takes a bill and whose money did not come back in full. */
    static final String ELIGIBLE = ("(t.kind in (%1$sORDER_PAYMENT, %1$sQUICKSCAN_PAYMENT) and br.id is null"
            + " and not " + CANCELLED + ")").formatted(KIND);

    private final EntityManager em;
    private final BillTracking tracking;

    /**
     * What decides one entry's bill status. {@code returned}: the money came back in full, by a QuickScan return or the
     * order's cancellation ({@code ORDER_REFUND}).
     */
    public record Facts(WalletEntryKind kind, Instant createdAt, InvoiceStatus invoiceStatus, boolean reviewed,
                        boolean waived, boolean returned) {

        /** A completed payment from the wallet that takes a bill, and whose money did not come back in full. */
        public boolean eligible() {
            return KINDS.contains(kind) && !returned;
        }

        public boolean hasBill() {
            return invoiceStatus != null;
        }
    }

    /** The Bill, Shop and Bill no. columns of one statement row. */
    public record StatementBill(BillStatus status, String shop, String billNumber) {
    }

    /** The banner's and month sheet's counts. */
    public record Summary(int pending, int reading, int unreadable, Map<YearMonth, Integer> pendingByMonth) {
    }

    // ── The rule, in Java ────────────────────────────────────────────────

    /** The full status, NOT_REQUIRED included; null when the entry has none. */
    public static BillStatus resolve(Facts f, Optional<Instant> start) {
        if (f.invoiceStatus() != null) {
            return switch (f.invoiceStatus()) {
                case READING -> BillStatus.READING;
                // M3 (owner's decision): an unreadable bill filled in by hand is as good as a reviewed one.
                case UNREADABLE -> f.reviewed() ? BillStatus.REVIEWED : BillStatus.UNREADABLE;
                case READ -> f.reviewed() ? BillStatus.REVIEWED : BillStatus.ADDED;
            };
        }
        if (!f.eligible()) {
            return null;
        }
        if (f.waived()) {
            return BillStatus.NOT_REQUIRED;
        }
        return start.isPresent() && !f.createdAt().isBefore(start.get()) ? BillStatus.PENDING : null;
    }

    /** What the History shows: a waiver shows no chip. */
    public static BillStatus forList(Facts f, Optional<Instant> start) {
        var status = resolve(f, start);
        return status == BillStatus.NOT_REQUIRED ? null : status;
    }

    /** Facts from a row selected with {@link #COLUMNS} starting at {@code offset}. */
    public static Facts facts(WalletEntryKind kind, Instant createdAt, Object[] row, int offset) {
        return new Facts(kind, createdAt, (InvoiceStatus) row[offset], row[offset + 1] != null,
                row[offset + 2] != null, row[offset + 3] != null);
    }

    public Optional<Instant> start() {
        return tracking.start();
    }

    // ── The rule, in JPQL ────────────────────────────────────────────────

    /** UNREADABLE: could not be read, and nobody has filled it in by hand yet (M3). */
    static final String UNREADABLE_OPEN = "(bi.status = " + INV + "UNREADABLE and bi.reviewedAt is null)";

    /** PENDING; never true while tracking is off. Uses {@code :billStart} when on. */
    static String pending(boolean trackingOn) {
        return trackingOn
                ? "(bi.id is null and bw.id is null and " + ELIGIBLE + " and t.createdAt >= :billStart)"
                : "(t.id < 0)";
    }

    /**
     * A where-clause term: the row's bill status is one of {@code wanted}. A row without a bill status never matches.
     * Bind {@code :billStart} with {@link #bindStart} when {@link #usesStart} says so.
     */
    public static String matching(Set<BillStatus> wanted, boolean trackingOn) {
        var terms = new ArrayList<String>();
        for (var status : wanted) {
            switch (status) {
                case PENDING -> terms.add(pending(trackingOn));
                case READING -> terms.add("bi.status = " + INV + "READING");
                case UNREADABLE -> terms.add(UNREADABLE_OPEN);
                case ADDED -> terms.add("(bi.status = " + INV + "READ and bi.reviewedAt is null)");
                case REVIEWED -> terms.add("(bi.status in (" + INV + "READ, " + INV + "UNREADABLE)"
                        + " and bi.reviewedAt is not null)");
                case NOT_REQUIRED -> throw new IllegalArgumentException("NOT_REQUIRED is not a filter");
            }
        }
        return terms.isEmpty() ? "(t.id < 0)" : "(" + String.join(" or ", terms) + ")";
    }

    public static boolean usesStart(Set<BillStatus> wanted, boolean trackingOn) {
        return trackingOn && wanted.contains(BillStatus.PENDING);
    }

    public static void bindStart(Query query, Instant start) {
        query.setParameter("billStart", start);
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /** The facts of the given entries of one wallet, in one query per thousand ids. */
    public Map<Long, Facts> forEntries(Long walletId, Collection<Long> ids) {
        var out = new HashMap<Long, Facts>();
        var all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += CHUNK) {
            List<Object[]> rows = em.createQuery("select t.id, t.kind, t.createdAt, " + COLUMNS
                            + " from WalletTransaction t " + JOINS
                            + " where t.walletId = :wallet and t.id in :ids", Object[].class)
                    .setParameter("wallet", walletId)
                    .setParameter("ids", all.subList(i, Math.min(all.size(), i + CHUNK)))
                    .getResultList();
            for (var row : rows) {
                out.put((Long) row[0], facts((WalletEntryKind) row[1], (Instant) row[2], row, 3));
            }
        }
        return out;
    }

    /**
     * What the History list shows ({@link #forList}) for the given entries of one wallet, read by {@link #forEntries}
     * (one query per thousand ids; none for no ids or no wallet). Entries with no bill status are left out.
     */
    public Map<Long, BillStatus> forListOf(Long walletId, Collection<Long> ids) {
        var out = new HashMap<Long, BillStatus>();
        if (walletId == null || ids.isEmpty()) {
            return out;
        }
        var start = tracking.start();
        forEntries(walletId, ids).forEach((id, f) -> {
            var status = forList(f, start);
            if (status != null) {
                out.put(id, status);
            }
        });
        return out;
    }

    /**
     * The Bill, Shop and Bill no. of every entry of a wallet in {@code [from, to)} that has any, in one query. Only
     * the two kinds that take a bill can have one. Shop and bill number: the review's when saved and not blank,
     * else what was read.
     */
    public Map<Long, StatementBill> forStatement(Long walletId, Instant from, Instant to,
                                                 java.util.function.Function<String, String[]> reviewShopAndNumber) {
        List<Object[]> rows = em.createQuery("select t.id, t.kind, t.createdAt, " + COLUMNS
                        + ", bi.vendorName, bi.invoiceNumber, bi.reviewJson"
                        + " from WalletTransaction t " + JOINS
                        + " where t.walletId = :wallet and t.createdAt >= :from and t.createdAt < :to"
                        + " and t.kind in (" + KIND + "ORDER_PAYMENT, " + KIND + "QUICKSCAN_PAYMENT)", Object[].class)
                .setParameter("wallet", walletId).setParameter("from", from).setParameter("to", to)
                .getResultList();
        var start = tracking.start();
        var out = new LinkedHashMap<Long, StatementBill>();
        for (var row : rows) {
            var f = facts((WalletEntryKind) row[1], (Instant) row[2], row, 3);
            var status = resolve(f, start);
            String shop = (String) row[7];
            String number = (String) row[8];
            if (row[9] != null) {
                String[] reviewed = reviewShopAndNumber.apply((String) row[9]);
                if (reviewed[0] != null && !reviewed[0].isBlank()) {
                    shop = reviewed[0];
                }
                if (reviewed[1] != null && !reviewed[1].isBlank()) {
                    number = reviewed[1];
                }
            }
            if (status != null || shop != null || number != null) {
                out.put((Long) row[0], new StatementBill(status, shop, number));
            }
        }
        return out;
    }

    /**
     * The counts for the banner and the month sheet, in one query; filters never apply (M2: each count is exactly what
     * its {@code bills=} filter returns):
     * <ul>
     *   <li>{@code pending}: PENDING (eligible, created on or after the tracking start, no bill, not waived);</li>
     *   <li>{@code reading} and {@code unreadable}: every bill on this wallet in that state, whatever its payment's date
     *       or eligibility (a bill that exists always shows, and a bill wins over a waiver);</li>
     *   <li>PENDING in each of {@code months} (Asia/Kolkata months).</li>
     * </ul>
     * With {@code banner} false only the month counts are worked out (the first three are 0), and with no months
     * either, nothing is queried (L2: later pages of the History).
     */
    public Summary summary(Long walletId, List<YearMonth> months, boolean banner) {
        var byMonth = new LinkedHashMap<YearMonth, Integer>();
        months.forEach(m -> byMonth.put(m, 0));
        var start = tracking.start();
        boolean on = start.isPresent();
        if (walletId == null || (!banner && (months.isEmpty() || !on))) {
            return new Summary(0, 0, 0, byMonth);
        }
        String pending = pending(on);
        var jpql = new StringBuilder("select coalesce(sum(case when ").append(pending).append(" then 1 else 0 end), 0)")
                .append(", coalesce(sum(case when bi.status = ").append(INV).append("READING then 1 else 0 end), 0)")
                .append(", coalesce(sum(case when ").append(UNREADABLE_OPEN).append(" then 1 else 0 end), 0)");
        for (int i = 0; i < months.size(); i++) {
            jpql.append(", coalesce(sum(case when ").append(pending)
                    .append(" and t.createdAt >= :ms").append(i).append(" and t.createdAt < :me").append(i)
                    .append(" then 1 else 0 end), 0)");
        }
        jpql.append(" from WalletTransaction t ").append(JOINS).append(" where t.walletId = :wallet and ");
        // The population: every row that has a bill (banner only), and the rows that can be PENDING.
        String pendingRows = on ? "(" + ELIGIBLE + " and t.createdAt >= :billStart)" : null;
        if (banner && pendingRows != null) {
            jpql.append("(bi.id is not null or ").append(pendingRows).append(")");
        } else if (banner) {
            jpql.append("bi.id is not null");
        } else {
            jpql.append(pendingRows);
        }
        var query = em.createQuery(jpql.toString(), Object[].class).setParameter("wallet", walletId);
        start.ifPresent(s -> bindStart(query, s));
        for (int i = 0; i < months.size(); i++) {
            query.setParameter("ms" + i, months.get(i).atDay(1).atStartOfDay(IST).toInstant());
            query.setParameter("me" + i, months.get(i).plusMonths(1).atDay(1).atStartOfDay(IST).toInstant());
        }
        Object[] row = query.getSingleResult();
        for (int i = 0; i < months.size(); i++) {
            byMonth.put(months.get(i), number(row[3 + i]));
        }
        return banner ? new Summary(number(row[0]), number(row[1]), number(row[2]), byMonth)
                : new Summary(0, 0, 0, byMonth);
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
