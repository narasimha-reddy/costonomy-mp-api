package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.service.WalletService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a wallet statement from the ledger, and refuses to hand one over that does not
 * add up (D-108).
 *
 * <p><b>The ledger is the source.</b> {@code wallet_transaction} is append-only and every
 * row carries {@code balance_after}, so a period's opening balance is one row's figure, not
 * a sum of history. Only ledger rows appear: a top-up that was paid and returned never
 * moved the balance, so it is not on the statement, exactly as it is not in the totals.
 *
 * <p><b>It reconciles or it does not exist.</b> Opening + added &minus; spent must equal
 * closing, and every row's {@code balance_after} must follow from the row before it. A
 * statement is what a restaurant hands its accountant; one that is quietly wrong is worse
 * than none. If the figures disagree, the ledger or the code is broken, and the honest
 * answer is a 500 with an ERROR that someone reads, not a file with a plausible total.
 * When the period runs up to the latest row, the closing balance is also checked against
 * the wallet's own balance, which is maintained by a different statement in the same
 * transactions.
 *
 * <p>All reads are in one read-only transaction, so they see one moment of the database
 * and a movement landing halfway through cannot make a correct ledger look inconsistent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletStatementService {

    /**
     * The most rows one statement holds. A statement is read by a person; beyond this it is
     * a data export, and building it in memory is a way to take the service down.
     */
    public static final int MAX_ROWS = 20_000;

    private final EntityManager em;
    private final WalletService wallets;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public WalletStatement build(Long outletId, StatementPeriod period) {
        var wallet = wallets.find(outletId).orElse(null);
        String outletName = jdbc.queryForList("select name from outlet where id = ?", String.class, outletId)
                .stream().findFirst().orElse("");
        var start = period.start();
        var end = period.endExclusive();

        if (wallet == null) {
            // Never used a wallet: a statement of nothing, which is still a true statement.
            return new WalletStatement(outletName, period, Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }

        long count = em.createQuery("""
                        select count(t) from WalletTransaction t
                         where t.walletId = :w and t.createdAt >= :from and t.createdAt < :to
                        """, Long.class)
                .setParameter("w", wallet.getId()).setParameter("from", start).setParameter("to", end)
                .getSingleResult();
        if (count > MAX_ROWS) {
            log.info("Wallet statement for outlet {} refused: {} rows in {} to {}",
                    outletId, count, period.from(), period.to());
            throw new BusinessException(ErrorCode.STATEMENT_TOO_LARGE,
                    "That period has %d entries, more than one statement can hold (%d). Please choose a shorter period."
                            .formatted(count, MAX_ROWS));
        }

        // Ordered by (created_at, id), the order the balance moved in: rows are written under
        // the wallet lock, so a later id is a later balance.
        List<WalletTransaction> rows = em.createQuery("""
                        select t from WalletTransaction t
                         where t.walletId = :w and t.createdAt >= :from and t.createdAt < :to
                         order by t.createdAt asc, t.id asc
                        """, WalletTransaction.class)
                .setParameter("w", wallet.getId()).setParameter("from", start).setParameter("to", end)
                .getResultList();

        var before = em.createQuery("""
                        select t.balanceAfter from WalletTransaction t
                         where t.walletId = :w and t.createdAt < :from
                         order by t.createdAt desc, t.id desc
                        """, BigDecimal.class)
                .setParameter("w", wallet.getId()).setParameter("from", start)
                .setMaxResults(1).getResultList();
        BigDecimal opening = before.isEmpty() ? BigDecimal.ZERO : before.get(0);

        var orderNumbers = orderNumbers(rows);
        BigDecimal added = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal running = opening;
        var lines = new ArrayList<WalletStatement.Line>(rows.size());
        for (var row : rows) {
            BigDecimal expected = row.getDirection() == WalletDirection.CREDIT
                    ? running.add(row.getAmount()) : running.subtract(row.getAmount());
            if (expected.compareTo(row.getBalanceAfter()) != 0) {
                throw inconsistent(outletId, period,
                        "ledger row %d leaves %s but the row before it implies %s"
                                .formatted(row.getId(), row.getBalanceAfter().toPlainString(), expected.toPlainString()));
            }
            running = row.getBalanceAfter();
            if (row.getDirection() == WalletDirection.CREDIT) {
                added = added.add(row.getAmount());
            } else {
                spent = spent.add(row.getAmount());
            }
            lines.add(new WalletStatement.Line(row.getCreatedAt(),
                    WalletEntryCopy.label(row.getKind(), row.getDirection()),
                    row.getSupplierOrderId() == null ? "" : orderNumbers.getOrDefault(row.getSupplierOrderId(), ""),
                    row.getDirection(), row.getAmount(), row.getBalanceAfter(),
                    row.getReason() == null ? "" : row.getReason()));
        }
        BigDecimal closing = rows.isEmpty() ? opening : rows.get(rows.size() - 1).getBalanceAfter();

        var statement = new WalletStatement(outletName, period, Instant.now(), opening, closing, added, spent,
                List.copyOf(lines));
        verify(outletId, statement);

        // If nothing came after the period, its closing balance is the wallet's balance today.
        boolean laterRows = !em.createQuery(
                        "select t.id from WalletTransaction t where t.walletId = :w and t.createdAt >= :to",
                        Long.class)
                .setParameter("w", wallet.getId()).setParameter("to", end).setMaxResults(1).getResultList().isEmpty();
        if (!laterRows && closing.compareTo(wallet.getBalance()) != 0) {
            throw inconsistent(outletId, period, "closing balance %s but the wallet holds %s"
                    .formatted(closing.toPlainString(), wallet.getBalance().toPlainString()));
        }

        log.info("Wallet statement built for outlet {}: {} to {}, {} rows",
                outletId, period.from(), period.to(), lines.size());
        return statement;
    }

    /**
     * The arithmetic a reader will do with a pencil. Public so a test can hand it a statement
     * that does not add up and see it refused, which no real ledger will provide.
     */
    public static void verify(Long outletId, WalletStatement s) {
        BigDecimal expectedClosing = s.openingBalance().add(s.totalAdded()).subtract(s.totalSpent());
        if (expectedClosing.compareTo(s.closingBalance()) != 0) {
            throw inconsistent(outletId, s.period(),
                    "opening %s + added %s - spent %s = %s, not closing %s".formatted(
                            s.openingBalance().toPlainString(), s.totalAdded().toPlainString(),
                            s.totalSpent().toPlainString(), expectedClosing.toPlainString(),
                            s.closingBalance().toPlainString()));
        }
    }

    private static IllegalStateException inconsistent(Long outletId, StatementPeriod period, String what) {
        // ERROR, for an alert to match: money records that disagree with themselves.
        log.error("Wallet statement for outlet {} ({} to {}) does not reconcile: {}. Not produced.",
                outletId, period.from(), period.to(), what);
        return new IllegalStateException("Wallet statement does not reconcile");
    }

    private Map<Long, String> orderNumbers(List<WalletTransaction> rows) {
        var ids = rows.stream().map(WalletTransaction::getSupplierOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        var out = new HashMap<Long, String>();
        for (int i = 0; i < ids.size(); i += 500) {
            var chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            jdbc.query("select id, order_number from supplier_order where id in (" + marks + ")",
                    rs -> {
                        out.put(rs.getLong(1), rs.getString(2));
                    }, chunk.toArray());
        }
        return out;
    }
}
