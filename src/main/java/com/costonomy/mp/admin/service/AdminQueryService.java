package com.costonomy.mp.admin.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything operations reads. Doc 09 §12.
 *
 * <p><b>Read projections in SQL, not other modules' repositories.</b> The same
 * boundary rule the directories follow, for a stronger reason here: operations
 * queries cut across every module at once — an order's timeline touches
 * procurement, payment, delivery and trust — and importing four modules'
 * aggregates would make the admin module depend on all of them and nothing depend
 * on it. It would also load entity graphs to render a flat list.
 *
 * <p><b>Every method checks an INTERNAL permission at PLATFORM scope.</b> No
 * tenant permission appears anywhere in this class. That is what stops an
 * operations endpoint becoming a way for a restaurant with a clever token to read
 * the marketplace, and it is why V17 took the borrowed `ORDER_VIEW` grants off the
 * OPS roles.
 *
 * <p><b>These are reads.</b> Doc 09 §13 separates inspection from mutation, so
 * nothing here writes — the mutations live in {@code AdminModerationService},
 * behind different permissions.
 */
@Service
@RequiredArgsConstructor
public class AdminQueryService {

    private static final int MAX_PAGE = 100;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;

    // ── Suppliers ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDtos.SupplierSummary> searchSuppliers(Long actorId, String query,
                                                           String status, Integer limit) {
        require(actorId, Permissions.SUPPLIER_INSPECT);

        String like = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        List<AdminDtos.SupplierSummary> results = new ArrayList<>();

        jdbc.query("""
                select o.id, o.legal_name, o.display_name, o.gstin, o.lifecycle_status,
                       o.verification_status, o.created_at,
                       (select count(*) from supplier_store s
                         where s.supplier_organization_id = o.id)                  as stores,
                       (select count(*) from supplier_sku k
                          join supplier_store s2 on s2.id = k.supplier_store_id
                         where s2.supplier_organization_id = o.id
                           and k.status = 'ACTIVE')                                as skus
                  from supplier_organization o
                 where (? is null or o.legal_name like ? or o.display_name like ?
                        or o.gstin like ?)
                   and (? is null or o.lifecycle_status = ?)
                 order by o.id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.SupplierSummary(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(8), rs.getInt(9),
                            rs.getTimestamp(7).toInstant()));
                },
                like, like, like, like, status, status, page(limit));

        return results;
    }

    @Transactional(readOnly = true)
    public AdminDtos.SupplierDetail supplier(Long actorId, Long supplierId) {
        require(actorId, Permissions.SUPPLIER_INSPECT);

        var summaries = searchSupplierById(supplierId);
        if (summaries == null) {
            throw new NotFoundException("Supplier", supplierId);
        }

        List<AdminDtos.StoreSummary> stores = new ArrayList<>();
        jdbc.query("""
                select s.id, s.name, s.city, s.status, s.response_sla_seconds,
                       (select count(*) from supplier_sku k
                         where k.supplier_store_id = s.id and k.status = 'ACTIVE')  as skus,
                       (select avg(r.overall_rating) from rating r
                         where r.supplier_store_id = s.id
                           and r.moderation_status = 'PUBLISHED')                   as avg_rating,
                       (select count(*) from rating r2
                         where r2.supplier_store_id = s.id
                           and r2.moderation_status = 'PUBLISHED')                  as ratings
                  from supplier_store s
                 where s.supplier_organization_id = ?
                 order by s.id
                """,
                rs -> {
                    stores.add(new AdminDtos.StoreSummary(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            (Integer) rs.getObject(5), rs.getInt(6),
                            // Null where nobody has rated. Doc 07 §4 again: an
                            // absent signal is absent, not a middling one.
                            rs.getBigDecimal(7), rs.getInt(8)));
                },
                supplierId);

        return new AdminDtos.SupplierDetail(summaries, stores);
    }

    private AdminDtos.SupplierSummary searchSupplierById(Long supplierId) {
        var rows = jdbc.query("""
                select o.id, o.legal_name, o.display_name, o.gstin, o.lifecycle_status,
                       o.verification_status, o.created_at,
                       (select count(*) from supplier_store s
                         where s.supplier_organization_id = o.id)                  as stores,
                       (select count(*) from supplier_sku k
                          join supplier_store s2 on s2.id = k.supplier_store_id
                         where s2.supplier_organization_id = o.id
                           and k.status = 'ACTIVE')                                as skus
                  from supplier_organization o where o.id = ?
                """,
                (rs, row) -> new AdminDtos.SupplierSummary(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(8), rs.getInt(9),
                        rs.getTimestamp(7).toInstant()),
                supplierId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Orders ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDtos.OrderSummary> searchOrders(Long actorId, String orderNumber,
                                                     String status, Long outletId,
                                                     Long supplierStoreId, Integer limit) {
        require(actorId, Permissions.ORDER_INSPECT);

        String like = orderNumber == null || orderNumber.isBlank()
                ? null : "%" + orderNumber.trim() + "%";
        List<AdminDtos.OrderSummary> results = new ArrayList<>();

        jdbc.query(orderSelect() + """
                 where (? is null or so.order_number like ?)
                   and (? is null or so.status = ?)
                   and (? is null or so.outlet_id = ?)
                   and (? is null or so.supplier_store_id = ?)
                 order by so.id desc
                 limit ?
                """,
                rs -> {
                    results.add(mapOrder(rs));
                },
                like, like, status, status, outletId, outletId,
                supplierStoreId, supplierStoreId, page(limit));

        return results;
    }

    /**
     * One order, and everything that happened to it, from every module.
     *
     * <p>Assembled here rather than by a client making five calls: the value is in
     * the <em>ordering</em> — the payment that authorised late, the courier that
     * cancelled, the acceptance that raced a timeout — and a client merging five
     * responses by timestamp would get that subtly wrong in a different way each
     * time.
     */
    @Transactional(readOnly = true)
    public AdminDtos.OrderTimeline orderTimeline(Long actorId, Long orderId) {
        require(actorId, Permissions.ORDER_INSPECT);

        var orders = jdbc.query(orderSelect() + " where so.id = ?",
                (rs, row) -> mapOrder(rs), orderId);
        if (orders.isEmpty()) {
            throw new NotFoundException("SupplierOrder", orderId);
        }

        List<AdminDtos.TimelineEntry> entries = new ArrayList<>();

        // Audit carries the state changes and who made them.
        jdbc.query("""
                select created_at, action, reason, actor_id, old_state, new_state
                  from audit_log
                 where (entity_type = 'SUPPLIER_ORDER' and entity_id = ?)
                    or (entity_type = 'DELIVERY' and entity_id in
                        (select id from delivery where supplier_order_id = ?))
                    or (entity_type = 'PAYMENT' and entity_id in
                        (select id from payment where supplier_order_id = ?))
                 order by created_at
                """,
                rs -> {
                    String from = rs.getString(5);
                    String to = rs.getString(6);
                    String detail = from == null && to == null ? rs.getString(3)
                            : "%s → %s%s".formatted(from, to,
                                    rs.getString(3) == null ? "" : " (" + rs.getString(3) + ")");
                    entries.add(new AdminDtos.TimelineEntry(
                            rs.getTimestamp(1).toInstant(), "AUDIT", rs.getString(2),
                            detail, (Long) rs.getObject(4)));
                },
                orderId, orderId, orderId);

        // Delivery events carry what the courier reported, including the ones that
        // changed nothing — an operator asking "why is it stuck" usually needs the
        // out-of-order event that was correctly ignored.
        jdbc.query("""
                select e.occurred_at, e.event_type, e.description, e.disposition
                  from delivery_event e
                  join delivery d on d.id = e.delivery_id
                 where d.supplier_order_id = ?
                 order by e.occurred_at
                """,
                rs -> {
                    String disposition = rs.getString(4);
                    String detail = "APPLIED".equals(disposition) ? rs.getString(3)
                            : "%s [%s]".formatted(rs.getString(3), disposition);
                    entries.add(new AdminDtos.TimelineEntry(
                            rs.getTimestamp(1).toInstant(), "DELIVERY",
                            rs.getString(2), detail, null));
                },
                orderId);

        jdbc.query("""
                select created_at, dispute_number, category, status
                  from dispute where supplier_order_id = ? order by created_at
                """,
                rs -> {
                    entries.add(new AdminDtos.TimelineEntry(
                            rs.getTimestamp(1).toInstant(), "DISPUTE", "DisputeRaised",
                            "%s %s (%s)".formatted(rs.getString(2), rs.getString(3),
                                    rs.getString(4)), null));
                },
                orderId);

        entries.sort(Comparator.comparing(AdminDtos.TimelineEntry::at));

        return new AdminDtos.OrderTimeline(orders.get(0),
                paymentForOrder(orderId), deliveryForOrder(orderId), entries);
    }

    private String orderSelect() {
        return """
                select so.id, so.order_number, so.status, so.outlet_id, o.name, r.name,
                       so.supplier_store_id, sorg.display_name, so.total_amount,
                       so.accepted_amount, so.payment_method, so.payment_status,
                       so.acceptance_deadline, so.created_at
                  from supplier_order so
                  join outlet o on o.id = so.outlet_id
                  join restaurant r on r.id = o.restaurant_id
                  join supplier_store ss on ss.id = so.supplier_store_id
                  join supplier_organization sorg on sorg.id = ss.supplier_organization_id
                """;
    }

    private AdminDtos.OrderSummary mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        var deadline = rs.getTimestamp(13);
        return new AdminDtos.OrderSummary(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8),
                rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11),
                rs.getString(12), deadline == null ? null : deadline.toInstant(),
                rs.getTimestamp(14).toInstant());
    }

    // ── Payments ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminDtos.PaymentDetail payment(Long actorId, Long orderId) {
        require(actorId, Permissions.PAYMENT_INSPECT);
        var payment = paymentForOrder(orderId);
        if (payment == null) {
            throw new NotFoundException("Payment", orderId);
        }
        return payment;
    }

    private AdminDtos.PaymentDetail paymentForOrder(Long orderId) {
        var payments = jdbc.query("""
                select id, supplier_order_id, status, provider, provider_payment_id,
                       authorized_amount, captured_amount, refunded_amount, released_amount,
                       failure_code, failure_reason, reconciled_at
                  from payment where supplier_order_id = ?
                """,
                (rs, row) -> {
                    var reconciled = rs.getTimestamp(12);
                    return new AdminDtos.PaymentDetail(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10),
                            rs.getString(11), reconciled == null ? null : reconciled.toInstant(),
                            List.of(), List.of());
                },
                orderId);

        if (payments.isEmpty()) {
            return null;
        }
        var payment = payments.get(0);

        List<AdminDtos.TransactionSummary> transactions = new ArrayList<>();
        jdbc.query("""
                select transaction_type, amount, status, provider_reference, created_at
                  from payment_transaction where payment_id = ? order by id
                """,
                rs -> {
                    transactions.add(new AdminDtos.TransactionSummary(
                            rs.getString(1), rs.getBigDecimal(2), rs.getString(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant()));
                },
                payment.id());

        List<AdminDtos.RefundSummary> refunds = new ArrayList<>();
        jdbc.query("""
                select id, amount, reason, status, created_at
                  from refund where payment_id = ? order by id
                """,
                rs -> {
                    refunds.add(new AdminDtos.RefundSummary(
                            rs.getLong(1), rs.getBigDecimal(2), rs.getString(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant()));
                },
                payment.id());

        return new AdminDtos.PaymentDetail(payment.id(), payment.supplierOrderId(),
                payment.status(), payment.provider(), payment.providerPaymentId(),
                payment.authorizedAmount(), payment.capturedAmount(), payment.refundedAmount(),
                payment.releasedAmount(), payment.failureCode(), payment.failureReason(),
                payment.reconciledAt(), transactions, refunds);
    }

    // ── Delivery ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminDtos.DeliveryDetail delivery(Long actorId, Long orderId) {
        require(actorId, Permissions.DELIVERY_INSPECT);
        var delivery = deliveryForOrder(orderId);
        if (delivery == null) {
            throw new NotFoundException("Delivery", orderId);
        }
        return delivery;
    }

    private AdminDtos.DeliveryDetail deliveryForOrder(Long orderId) {
        var deliveries = jdbc.query("""
                select id, supplier_order_id, mode, status, provider_code, provider_delivery_id,
                       fee, attempt_count, failure_code, failure_reason, requested_at, delivered_at
                  from delivery where supplier_order_id = ?
                """,
                (rs, row) -> {
                    var delivered = rs.getTimestamp(12);
                    return new AdminDtos.DeliveryDetail(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getInt(8), rs.getString(9), rs.getString(10),
                            rs.getTimestamp(11).toInstant(),
                            delivered == null ? null : delivered.toInstant(),
                            List.of(), List.of());
                },
                orderId);

        if (deliveries.isEmpty()) {
            return null;
        }
        var delivery = deliveries.get(0);

        List<AdminDtos.DeliveryAttemptSummary> attempts = new ArrayList<>();
        jdbc.query("""
                select attempt_number, provider_code, attempt_type, outcome, failure_reason,
                       started_at, ended_at
                  from delivery_provider_attempt where delivery_id = ? order by attempt_number
                """,
                rs -> {
                    var ended = rs.getTimestamp(7);
                    attempts.add(new AdminDtos.DeliveryAttemptSummary(
                            rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getTimestamp(6).toInstant(),
                            ended == null ? null : ended.toInstant()));
                },
                delivery.id());

        List<AdminDtos.DeliveryQuoteSummary> quotes = new ArrayList<>();
        jdbc.query("""
                select provider_code, status, amount, eta_minutes, selected, failure_reason
                  from delivery_quote where delivery_id = ? order by id
                """,
                rs -> {
                    quotes.add(new AdminDtos.DeliveryQuoteSummary(
                            rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                            (Integer) rs.getObject(4), rs.getBoolean(5), rs.getString(6)));
                },
                delivery.id());

        return new AdminDtos.DeliveryDetail(delivery.id(), delivery.supplierOrderId(),
                delivery.mode(), delivery.status(), delivery.providerCode(),
                delivery.providerDeliveryId(), delivery.fee(), delivery.attemptCount(),
                delivery.failureCode(), delivery.failureReason(), delivery.requestedAt(),
                delivery.deliveredAt(), attempts, quotes);
    }

    // ── Credit ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDtos.CreditExposureSummary> creditExposure(Long actorId, String status,
                                                                Integer limit) {
        require(actorId, Permissions.CREDIT_AUDIT);

        List<AdminDtos.CreditExposureSummary> results = new ArrayList<>();
        jdbc.query("""
                select a.id, a.outlet_id, o.name, a.supplier_store_id, sorg.display_name,
                       a.status, a.approved_limit, a.reserved_amount, a.utilized_amount,
                       a.approved_limit - a.reserved_amount - a.utilized_amount   as available,
                       coalesce((select sum(i.amount - i.paid_amount) from credit_invoice i
                                  where i.credit_agreement_id = a.id
                                    and i.status not in ('PAID','WRITTEN_OFF')), 0) as due,
                       coalesce((select sum(i2.amount - i2.paid_amount) from credit_invoice i2
                                  where i2.credit_agreement_id = a.id
                                    and i2.status = 'OVERDUE'), 0)                  as overdue
                  from credit_agreement a
                  join outlet o on o.id = a.outlet_id
                  join supplier_store s on s.id = a.supplier_store_id
                  join supplier_organization sorg on sorg.id = s.supplier_organization_id
                 where (? is null or a.status = ?)
                 order by overdue desc, a.id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.CreditExposureSummary(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                            rs.getBigDecimal(11), rs.getBigDecimal(12)));
                },
                status, status, page(limit));

        return results;
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDtos.DisputeSummary> searchDisputes(Long actorId, String status,
                                                         String category, Integer limit) {
        require(actorId, Permissions.DISPUTE_INSPECT);

        List<AdminDtos.DisputeSummary> results = new ArrayList<>();
        jdbc.query("""
                select d.id, d.dispute_number, d.supplier_order_id, so.order_number,
                       d.category, d.status, d.claimed_amount, o.name, sorg.display_name,
                       d.created_at
                  from dispute d
                  join supplier_order so on so.id = d.supplier_order_id
                  join outlet o on o.id = d.outlet_id
                  join supplier_store s on s.id = d.supplier_store_id
                  join supplier_organization sorg on sorg.id = s.supplier_organization_id
                 where (? is null or d.status = ?)
                   and (? is null or d.category = ?)
                 order by d.id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.DisputeSummary(
                            rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getString(8), rs.getString(9),
                            rs.getTimestamp(10).toInstant()));
                },
                status, status, category, category, page(limit));

        return results;
    }

    // ── Audit ────────────────────────────────────────────────────────────

    /**
     * Audit search. Doc 09 §7, §12.
     *
     * <p>Filtered by actor, entity, action and date — the four questions an
     * investigation actually asks. Note what is <b>not</b> returned:
     * {@code before_json} and {@code after_json}. Those hold entity snapshots that
     * may contain personal data, and doc 09 §6 says access to it is
     * permission-controlled; a general audit read is not that permission.
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.AuditEntry> searchAudit(Long actorId, Long subjectActorId,
                                                  String entityType, Long entityId,
                                                  String action, Integer limit) {
        require(actorId, Permissions.AUDIT_VIEW);

        List<AdminDtos.AuditEntry> results = new ArrayList<>();
        jdbc.query("""
                select id, actor_id, action, entity_type, entity_id, old_state, new_state,
                       reason, request_id, source, created_at
                  from audit_log
                 where (? is null or actor_id = ?)
                   and (? is null or entity_type = ?)
                   and (? is null or entity_id = ?)
                   and (? is null or action = ?)
                 order by id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.AuditEntry(
                            rs.getLong(1), (Long) rs.getObject(2), rs.getString(3),
                            rs.getString(4), (Long) rs.getObject(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getTimestamp(11).toInstant()));
                },
                subjectActorId, subjectActorId, entityType, entityType,
                entityId, entityId, action, action, page(limit));

        return results;
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Internal permission, platform scope, every time.
     *
     * <p>{@code require} rather than {@code requireScoped}: there is no tenant
     * resource to hide here, and a 404 for "you are not an operator" would be a
     * confusing answer to a question nobody outside operations should be asking in
     * the first place.
     */
    private void require(Long actorId, String permission) {
        accessControl.require(actorId, permission, ScopeType.PLATFORM, null);
    }

    private int page(Integer limit) {
        return limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_PAGE);
    }
}
