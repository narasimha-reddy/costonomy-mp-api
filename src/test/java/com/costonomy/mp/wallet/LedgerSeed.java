package com.costonomy.mp.wallet;

import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ledger rows straight into the database at chosen instants, keeping the running
 * balance the way the wallet does (D-108). The history and statements are read models over
 * the ledger, so tests need rows at exact times (an IST midnight, a shared microsecond),
 * which real operations cannot be told to produce.
 *
 * <p>Instants are UTC strings, {@code 2026-09-30 18:30:00.000000}: the test database runs
 * at +00:00 and Hibernate reads and writes UTC, so a literal means the same thing to both.
 */
final class LedgerSeed {

    private final JdbcTemplate jdbc;
    private final long outletId;
    final long walletId;
    BigDecimal balance = BigDecimal.ZERO;
    long lastId;

    LedgerSeed(WalletTopUpSupport support, JdbcTemplate jdbc, Buyer buyer) throws Exception {
        this.jdbc = jdbc;
        this.outletId = buyer.outletId();
        support.wallet(buyer); // creates the wallet row
        this.walletId = jdbc.queryForObject("select id from wallet where outlet_id = ?", Long.class, buyer.outletId());
    }

    LedgerSeed credit(String at, WalletEntryKind kind, String amount, String reason) {
        return add(at, "CREDIT", kind, amount, reason, null, null);
    }

    LedgerSeed debit(String at, WalletEntryKind kind, String amount, String reason) {
        return add(at, "DEBIT", kind, amount, reason, null, null);
    }

    LedgerSeed add(String at, String direction, WalletEntryKind kind, String amount, String reason,
                   Long orderId, Long refundId) {
        var value = new BigDecimal(amount);
        balance = "CREDIT".equals(direction) ? balance.add(value) : balance.subtract(value);
        String sql = """
                insert into wallet_transaction (wallet_id, supplier_order_id, direction, kind, refund_id, amount,
                       balance_after, reason, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """;
        Object[] args = {walletId, orderId, direction, kind.name(), refundId, value, balance, reason, at, at};
        if (orderId == null && refundId == null) {
            jdbc.update(sql, args);
        } else {
            // A movement about an order or a refund whose rows are beside the point of the test.
            jdbc.execute((ConnectionCallback<Void>) con -> {
                try (Statement off = con.createStatement()) {
                    off.execute("SET FOREIGN_KEY_CHECKS=0");
                }
                try (var insert = con.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        insert.setObject(i + 1, args[i]);
                    }
                    insert.executeUpdate();
                } finally {
                    try (Statement on = con.createStatement()) {
                        on.execute("SET FOREIGN_KEY_CHECKS=1");
                    }
                }
                return null;
            });
        }
        lastId = jdbc.queryForObject("select max(id) from wallet_transaction where wallet_id = ?", Long.class, walletId);
        return this;
    }

    /** A supplier order that exists only to have a number, whatever else is missing from it. */
    long order(String orderNumber) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (Statement off = con.createStatement()) {
                off.execute("SET FOREIGN_KEY_CHECKS=0");
            }
            try (var insert = con.prepareStatement("""
                    insert into supplier_order (procurement_id, supplier_store_id, outlet_id, order_number,
                           delivery_mode, created_at, updated_at, version)
                    values (?, ?, ?, ?, 'COSTONOMY', now(6), now(6), 0)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, System.nanoTime());
                insert.setLong(2, 1);
                insert.setLong(3, outletId);
                insert.setString(4, orderNumber);
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            } finally {
                try (Statement on = con.createStatement()) {
                    on.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
        });
    }

    /** Many one-rupee credits at one instant, in a batch. */
    LedgerSeed bulkCredits(String at, int count) {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < count; i++) {
            balance = balance.add(BigDecimal.ONE);
            rows.add(new Object[]{walletId, "CREDIT", "TOP_UP", BigDecimal.ONE, balance, "bulk", at, at});
        }
        jdbc.batchUpdate("""
                insert into wallet_transaction (wallet_id, direction, kind, amount, balance_after, reason,
                       created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, rows);
        return this;
    }

    /** Makes the wallet's balance what the ledger says it is. */
    LedgerSeed sync() {
        jdbc.update("update wallet set balance = ? where id = ?", balance, walletId);
        return this;
    }

    /** A refund row in the given status, whatever it refunds: only its status matters to the history. */
    long refund(String status) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (Statement off = con.createStatement()) {
                off.execute("SET FOREIGN_KEY_CHECKS=0");
            }
            try (var insert = con.prepareStatement("""
                    insert into refund (payment_id, supplier_order_id, amount, reason, status, idempotency_key,
                           created_at, updated_at, version)
                    values (999999, 999999, 1, 'CANCELLATION', ?, ?, now(6), now(6), 0)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, status);
                insert.setString(2, "seed-" + java.util.UUID.randomUUID());
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            } finally {
                try (Statement on = con.createStatement()) {
                    on.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
        });
    }

    /** A top-up that was paid and returned, created at the given instant. */
    long returnedTopUp(long userId, String at, String amount, String method, String detail) {
        jdbc.update("""
                insert into wallet_top_up (outlet_id, created_by, amount, status, razorpay_order_id,
                       razorpay_payment_id, payment_method, payment_detail, idempotency_key,
                       created_at, updated_at, version)
                values (?, ?, ?, 'REFUNDED', ?, ?, ?, ?, ?, ?, ?, 0)
                """, outletId, userId, new BigDecimal(amount), "order_" + java.util.UUID.randomUUID(),
                "pay_" + java.util.UUID.randomUUID(), method, detail, "seed-" + java.util.UUID.randomUUID(), at, at);
        return jdbc.queryForObject("select max(id) from wallet_top_up where outlet_id = ?", Long.class, outletId);
    }

    List<Long> ids() {
        return jdbc.queryForList("select id from wallet_transaction where wallet_id = ? order by id", Long.class, walletId);
    }
}
