package com.costonomy.mp.admin.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Doc 08 §11's operational figures. "Operations web UI is future scope; APIs are
 * required now."
 *
 * <p><b>Every rate is null where its denominator is zero.</b> The same rule the
 * ranking signals follow (doc 07 §4, D-039), and it matters more here because a
 * dashboard is read at a glance: a fresh environment showing 100% acceptance and
 * 100% on-time because nothing has happened is worse than one showing a dash, and
 * an operator who learns to discount the green numbers will discount the real ones
 * too.
 *
 * <p><b>Counts are absolute; rates are windowed.</b> "How many orders are in
 * flight" is a question about now. "What share were accepted" is meaningless
 * without a period, and a lifetime average hides this week entirely.
 */
@Service
@RequiredArgsConstructor
public class OperationsDashboardService {

    private static final int DEFAULT_WINDOW_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;

    @Transactional(readOnly = true)
    public AdminDtos.OperationsDashboard dashboard(Long actorId, Integer windowDays) {
        // Reading the whole marketplace's health is a support-level read, so the
        // broadest inspection permission gates it.
        accessControl.require(actorId, Permissions.ORDER_INSPECT, ScopeType.PLATFORM, null);

        int window = windowDays == null ? DEFAULT_WINDOW_DAYS
                : Math.min(Math.max(windowDays, 1), 90);

        return new AdminDtos.OperationsDashboard(Instant.now(), window,
                orders(window), payments(window), delivery(window),
                credit(), disputes(window));
    }

    private AdminDtos.OrderMetrics orders(int window) {
        var row = jdbc.queryForMap("""
                select
                  (select count(*) from supplier_order
                    where status in ('PENDING_ACCEPTANCE','CONFIRMED','PARTIALLY_ACCEPTED',
                                     'PREPARING','READY_FOR_PICKUP','OUT_FOR_DELIVERY'))  as active,
                  (select count(*) from supplier_order
                    where status = 'PENDING_ACCEPTANCE')                                  as awaiting,
                  (select count(*) from supplier_order
                    where created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and status <> 'DRAFT')                                              as placed,
                  (select count(*) from supplier_order
                    where created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and status not in ('DRAFT','PENDING_ACCEPTANCE'))                   as answered,
                  (select count(*) from supplier_order
                    where created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and status in ('CONFIRMED','PARTIALLY_ACCEPTED','PREPARING',
                                     'READY_FOR_PICKUP','OUT_FOR_DELIVERY','DELIVERED',
                                     'COMPLETED'))                                        as accepted,
                  (select count(*) from supplier_order
                    where created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and status = 'EXPIRED')                                             as expired,
                  (select sum(i.fulfilled_quantity) from supplier_order_item i
                     join supplier_order o on o.id = i.supplier_order_id
                    where o.created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and i.fulfilled_quantity is not null)                               as filled,
                  (select sum(i2.accepted_quantity) from supplier_order_item i2
                     join supplier_order o2 on o2.id = i2.supplier_order_id
                    where o2.created_at >= date_sub(utc_timestamp(6), interval ? day)
                      and i2.fulfilled_quantity is not null)                              as committed
                """, window, window, window, window, window, window);

        long answered = number(row.get("answered"));
        return new AdminDtos.OrderMetrics(
                number(row.get("active")),
                number(row.get("awaiting")),
                number(row.get("placed")),
                // Against orders the supplier actually answered — counting those
                // still pending would make the rate fall because an order arrived
                // a second ago (the same denominator D-019 settled).
                rate(number(row.get("accepted")), answered),
                rate(number(row.get("expired")), answered),
                share(decimal(row.get("filled")), decimal(row.get("committed"))));
    }

    private AdminDtos.PaymentMetrics payments(int window) {
        var row = jdbc.queryForMap("""
                select
                  (select count(*) from payment where status = 'AUTHORIZED')              as authorized,
                  (select count(*) from payment where status = 'CAPTURED')                as captured,
                  (select count(*) from payment
                    where status = 'FAILED'
                      and created_at >= date_sub(utc_timestamp(6), interval ? day))       as failed,
                  (select count(*) from payment
                    where status in ('CREATED','CAPTURE_PENDING'))                        as pending,
                  (select coalesce(sum(captured_amount), 0) from payment
                    where captured_at >= date_sub(utc_timestamp(6), interval ? day))      as value
                """, window, window);

        return new AdminDtos.PaymentMetrics(
                number(row.get("authorized")), number(row.get("captured")),
                number(row.get("failed")), number(row.get("pending")),
                decimal(row.get("value")));
    }

    private AdminDtos.DeliveryMetrics delivery(int window) {
        var row = jdbc.queryForMap("""
                select
                  (select count(*) from delivery
                    where status in ('PROVIDER_SELECTED','DRIVER_ASSIGNED','DRIVER_AT_PICKUP',
                                     'PICKED_UP','IN_TRANSIT','ARRIVED_AT_DESTINATION'))  as in_flight,
                  (select count(*) from delivery
                    where status = 'DELIVERED'
                      and delivered_at >= date_sub(utc_timestamp(6), interval ? day))     as delivered,
                  (select count(*) from delivery
                    where status in ('QUOTE_FAILED','PROVIDER_UNAVAILABLE','DELIVERY_FAILED')
                      and created_at >= date_sub(utc_timestamp(6), interval ? day))       as failed,
                  (select count(*) from delivery
                    where mode = 'COSTONOMY' and status = 'DELIVERED'
                      and delivered_at is not null and estimated_arrival_at is not null
                      and delivered_at >= date_sub(utc_timestamp(6), interval ? day))     as measured,
                  (select count(*) from delivery
                    where mode = 'COSTONOMY' and status = 'DELIVERED'
                      and delivered_at <= estimated_arrival_at
                      and delivered_at >= date_sub(utc_timestamp(6), interval ? day))     as on_time,
                  (select count(*) from delivery
                    where attempt_count > 1
                      and created_at >= date_sub(utc_timestamp(6), interval ? day))       as reassigned,
                  (select count(*) from delivery
                    where created_at >= date_sub(utc_timestamp(6), interval ? day))       as total
                """, window, window, window, window, window, window);

        return new AdminDtos.DeliveryMetrics(
                number(row.get("in_flight")), number(row.get("delivered")),
                number(row.get("failed")),
                rate(number(row.get("on_time")), number(row.get("measured"))),
                rate(number(row.get("reassigned")), number(row.get("total"))));
    }

    private AdminDtos.CreditMetrics credit() {
        var row = jdbc.queryForMap("""
                select
                  (select count(*) from credit_agreement where status = 'ACTIVE')          as active,
                  (select coalesce(sum(approved_limit), 0) from credit_agreement
                    where status in ('ACTIVE','SUSPENDED'))                                as approved,
                  (select coalesce(sum(utilized_amount), 0) from credit_agreement
                    where status in ('ACTIVE','SUSPENDED'))                                as utilized,
                  (select coalesce(sum(amount - paid_amount), 0) from credit_invoice
                    where status = 'OVERDUE')                                              as overdue,
                  (select count(*) from credit_invoice where status = 'OVERDUE')           as overdue_count,
                  (select count(*) from credit_agreement where status = 'SUSPENDED')       as suspended
                """);

        return new AdminDtos.CreditMetrics(
                number(row.get("active")), decimal(row.get("approved")),
                decimal(row.get("utilized")), decimal(row.get("overdue")),
                number(row.get("overdue_count")), number(row.get("suspended")));
    }

    private AdminDtos.DisputeMetrics disputes(int window) {
        var row = jdbc.queryForMap("""
                select
                  (select count(*) from dispute
                    where status in ('OPEN','UNDER_REVIEW','RESPONDED'))                   as open,
                  (select count(*) from dispute
                    where created_at >= date_sub(utc_timestamp(6), interval ? day))        as opened,
                  (select count(*) from dispute
                    where resolved_at >= date_sub(utc_timestamp(6), interval ? day))       as resolved
                """, window, window);

        Map<String, Long> byCategory = new LinkedHashMap<>();
        jdbc.query("""
                select category, count(*) from dispute
                 where created_at >= date_sub(utc_timestamp(6), interval ? day)
                 group by category order by 2 desc
                """,
                rs -> {
                    byCategory.put(rs.getString(1), rs.getLong(2));
                },
                window);

        return new AdminDtos.DisputeMetrics(
                number(row.get("open")), number(row.get("opened")),
                number(row.get("resolved")), byCategory);
    }

    // ── arithmetic that refuses to invent a number ───────────────────────

    /** @return null when nothing was measured — never a flattering 100%. */
    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal share(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal
                ? decimal : BigDecimal.valueOf(((Number) value).doubleValue());
    }
}
