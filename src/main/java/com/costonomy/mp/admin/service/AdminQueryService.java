package com.costonomy.mp.admin.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient.AppOrg;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import com.costonomy.security.client.CommonApiClient.OrgPage;
import com.costonomy.security.client.CommonApiClient.OrgQuery;
import com.costonomy.security.client.CommonApiClient.OutletQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
 * <p><b>Names come from common.</b> D-208: businesses, outlets and stores are
 * costonomy-common-api's, so the SQL selects their ids and the {@link Directory}
 * fills in names and standing, one batch per page.
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

    /** How many suppliers to ask common for at a time when filtering by lifecycle. */
    private static final int STATUS_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    private final Directory directory;

    // ── Suppliers ────────────────────────────────────────────────────────

    /**
     * Suppliers by name or GSTIN, newest first.
     *
     * <p>D-208: the businesses are common's, searched there; the lifecycle is derived
     * ({@link SupplierStanding}), not stored, so a {@code status} filter runs here
     * after each batch from common, and the page is cut after the filter.
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.SupplierSummary> searchSuppliers(Long actorId, String query,
                                                           String status, Integer limit) {
        require(actorId, Permissions.SUPPLIER_INSPECT);

        String q = query == null || query.isBlank() ? null : query.trim();
        int wanted = page(limit);
        List<AppOrg> found = new ArrayList<>();

        if (status == null || status.isBlank()) {
            found.addAll(directory.orgs(new OrgQuery(null, "SUPPLIER", q, wanted, 0)).items());
        } else {
            // Ask in batches until the page is full or common has no more.
            int offset = 0;
            while (found.size() < wanted) {
                OrgPage batch = directory.orgs(new OrgQuery(null, "SUPPLIER", q, STATUS_BATCH, offset));
                for (AppOrg org : batch.items()) {
                    if (found.size() < wanted && status.equals(SupplierStanding.of(org).name())) {
                        found.add(org);
                    }
                }
                offset += batch.items().size();
                if (batch.items().size() < STATUS_BATCH || offset >= batch.total()) {
                    break;
                }
            }
        }

        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AppOutlet>> storesByOrg = storesOf(found.stream().map(AppOrg::id).toList());
        Map<Long, Integer> skus = activeSkuCounts(storesByOrg.values().stream()
                .flatMap(List::stream).map(AppOutlet::id).toList());

        return found.stream()
                .map(org -> summary(org, storesByOrg.getOrDefault(org.id(), List.of()), skus))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminDtos.SupplierDetail supplier(Long actorId, Long supplierId) {
        require(actorId, Permissions.SUPPLIER_INSPECT);

        AppOrg org = directory.org(supplierId)
                .filter(o -> "SUPPLIER".equals(o.orgType()))
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));

        List<AppOutlet> stores = directory.outletsOf(supplierId).stream()
                .filter(s -> "STORE".equals(s.kind()))
                .sorted(Comparator.comparingLong(AppOutlet::id))
                .toList();
        List<Long> storeIds = stores.stream().map(AppOutlet::id).toList();
        Map<Long, Integer> skus = activeSkuCounts(storeIds);

        Map<Long, BigDecimal> averages = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        if (!storeIds.isEmpty()) {
            jdbc.query("""
                    select r.supplier_store_id, avg(r.overall_rating), count(*)
                      from rating r
                     where r.moderation_status = 'PUBLISHED'
                       and r.supplier_store_id in (%s)
                     group by r.supplier_store_id
                    """.formatted(marks(storeIds.size())),
                    rs -> {
                        averages.put(rs.getLong(1), rs.getBigDecimal(2));
                        counts.put(rs.getLong(1), rs.getInt(3));
                    },
                    storeIds.toArray());
        }

        List<AdminDtos.StoreSummary> summaries = stores.stream()
                .map(s -> new AdminDtos.StoreSummary(
                        s.id(), s.name(), s.city(), SupplierStanding.storeStatus(s),
                        SupplierStanding.responseSlaSeconds(s), skus.getOrDefault(s.id(), 0),
                        // Null where nobody has rated. Doc 07 §4 again: an
                        // absent signal is absent, not a middling one.
                        averages.get(s.id()), counts.getOrDefault(s.id(), 0)))
                .toList();

        return new AdminDtos.SupplierDetail(summary(org, stores, skus), summaries);
    }

    private AdminDtos.SupplierSummary summary(AppOrg org, List<AppOutlet> stores,
                                              Map<Long, Integer> skus) {
        int activeSkus = stores.stream().mapToInt(s -> skus.getOrDefault(s.id(), 0)).sum();
        return new AdminDtos.SupplierSummary(
                org.id(), org.legalName(), org.displayName(), org.gstin(),
                SupplierStanding.of(org).name(),
                SupplierStanding.verification(org.verificationStatus()).name(),
                stores.size(), activeSkus, instant(org.createdAt()));
    }

    /** The stores of each business, in one call to common. */
    private Map<Long, List<AppOutlet>> storesOf(List<Long> orgIds) {
        Map<Long, List<AppOutlet>> byOrg = new HashMap<>();
        directory.query(new OutletQuery(null, orgIds, List.of("STORE"), null, null, null, null))
                .forEach(s -> byOrg.computeIfAbsent(s.orgId(), k -> new ArrayList<>()).add(s));
        return byOrg;
    }

    /** ACTIVE SKUs per store; stores with none are absent. */
    private Map<Long, Integer> activeSkuCounts(List<Long> storeIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (storeIds.isEmpty()) {
            return counts;
        }
        jdbc.query("""
                select k.supplier_store_id, count(*) from supplier_sku k
                 where k.status = 'ACTIVE' and k.supplier_store_id in (%s)
                 group by k.supplier_store_id
                """.formatted(marks(storeIds.size())),
                rs -> {
                    counts.put(rs.getLong(1), rs.getInt(2));
                },
                storeIds.toArray());
        return counts;
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

        return named(results);
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

        return new AdminDtos.OrderTimeline(named(orders).get(0),
                paymentForOrder(orderId), deliveryForOrder(orderId), entries);
    }

    /** Ids only: the outlet, restaurant and supplier names come from common ({@link #named}). D-208. */
    private String orderSelect() {
        return """
                select so.id, so.order_number, so.status, so.outlet_id, so.supplier_store_id,
                       so.total_amount, so.accepted_amount, so.payment_method, so.payment_status,
                       so.acceptance_deadline, so.created_at
                  from supplier_order so
                """;
    }

    private AdminDtos.OrderSummary mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        var deadline = rs.getTimestamp(10);
        return new AdminDtos.OrderSummary(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                null, null, rs.getLong(5), null,
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8),
                rs.getString(9), deadline == null ? null : deadline.toInstant(),
                rs.getTimestamp(11).toInstant());
    }

    /**
     * Fills in the outlet, restaurant and supplier names, one batch for the page.
     *
     * <p>An outlet or store common does not know keeps its row with the name left
     * null: an operator looking for an order needs to see it, broken reference and all.
     */
    private List<AdminDtos.OrderSummary> named(List<AdminDtos.OrderSummary> orders) {
        Map<Long, AppOutlet> places = places(orders.stream()
                .flatMap(o -> Stream.of(o.outletId(), o.supplierStoreId())).toList());
        return orders.stream().map(o -> {
            AppOutlet outlet = places.get(o.outletId());
            AppOutlet store = places.get(o.supplierStoreId());
            return new AdminDtos.OrderSummary(o.id(), o.orderNumber(), o.status(), o.outletId(),
                    outlet == null ? null : outlet.name(), outlet == null ? null : outlet.orgName(),
                    o.supplierStoreId(), store == null ? null : store.orgName(),
                    o.totalAmount(), o.acceptedAmount(), o.paymentMethod(), o.paymentStatus(),
                    o.acceptanceDeadline(), o.createdAt());
        }).toList();
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
                       failure_code, failure_reason, reconciled_at,
                       provider_method, release_reason, provider_fee, review_required_at, review_reason
                  from payment where supplier_order_id = ?
                """,
                (rs, row) -> {
                    var reconciled = rs.getTimestamp(12);
                    var review = rs.getTimestamp(16);
                    return new AdminDtos.PaymentDetail(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10),
                            rs.getString(11), reconciled == null ? null : reconciled.toInstant(),
                            rs.getString(13), rs.getString(14), rs.getBigDecimal(15),
                            review == null ? null : review.toInstant(), rs.getString(17),
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
                payment.reconciledAt(), payment.providerMethod(), payment.releaseReason(),
                payment.providerFee(), payment.reviewRequiredAt(), payment.reviewReason(),
                transactions, refunds);
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
                select a.id, a.outlet_id, a.supplier_store_id, a.status, a.approved_limit, a.reserved_amount, a.utilized_amount,
                       a.approved_limit - a.reserved_amount - a.utilized_amount   as available,
                       coalesce((select sum(i.amount - i.paid_amount - i.credited_amount) from credit_invoice i
                                  where i.credit_agreement_id = a.id
                                    and i.status not in ('PAID','WRITTEN_OFF')), 0) as due,
                       coalesce((select sum(i2.amount - i2.paid_amount - i2.credited_amount) from credit_invoice i2
                                  where i2.credit_agreement_id = a.id
                                    and i2.status = 'OVERDUE'), 0)                  as overdue
                  from credit_agreement a
                 where (? is null or a.status = ?)
                 order by overdue desc, a.id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.CreditExposureSummary(
                            rs.getLong(1), rs.getLong(2), null, rs.getLong(3),
                            null, rs.getString(4), rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                            rs.getBigDecimal(9), rs.getBigDecimal(10)));
                },
                status, status, page(limit));

        // Names from common, one batch (D-208).
        Map<Long, AppOutlet> places = places(results.stream()
                .flatMap(a -> Stream.of(a.outletId(), a.supplierStoreId())).toList());
        return results.stream().map(a -> new AdminDtos.CreditExposureSummary(
                a.agreementId(), a.outletId(), nameOf(places.get(a.outletId())),
                a.supplierStoreId(), orgNameOf(places.get(a.supplierStoreId())), a.status(),
                a.approvedLimit(), a.reserved(), a.utilized(), a.available(), a.due(),
                a.overdue())).toList();
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDtos.DisputeSummary> searchDisputes(Long actorId, String status,
                                                         String category, Integer limit) {
        require(actorId, Permissions.DISPUTE_INSPECT);

        List<AdminDtos.DisputeSummary> results = new ArrayList<>();
        List<Long[]> parties = new ArrayList<>();
        jdbc.query("""
                select d.id, d.dispute_number, d.supplier_order_id, so.order_number,
                       d.category, d.status, d.claimed_amount, d.outlet_id, d.supplier_store_id,
                       d.created_at
                  from dispute d
                  join supplier_order so on so.id = d.supplier_order_id
                 where (? is null or d.status = ?)
                   and (? is null or d.category = ?)
                 order by d.id desc
                 limit ?
                """,
                rs -> {
                    results.add(new AdminDtos.DisputeSummary(
                            rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                            null, null, rs.getTimestamp(10).toInstant()));
                    parties.add(new Long[] {rs.getLong(8), rs.getLong(9)});
                },
                status, status, category, category, page(limit));

        // Names from common, one batch (D-208).
        Map<Long, AppOutlet> places = places(parties.stream().flatMap(Stream::of).toList());
        List<AdminDtos.DisputeSummary> named = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            var d = results.get(i);
            named.add(new AdminDtos.DisputeSummary(d.id(), d.disputeNumber(), d.supplierOrderId(),
                    d.orderNumber(), d.category(), d.status(), d.claimedAmount(),
                    nameOf(places.get(parties.get(i)[0])),
                    orgNameOf(places.get(parties.get(i)[1])), d.createdAt()));
        }
        return named;
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

    private Map<Long, AppOutlet> places(List<Long> ids) {
        return ids.isEmpty() ? Map.of() : directory.outlets(ids);
    }

    private static String nameOf(AppOutlet outlet) {
        return outlet == null ? null : outlet.name();
    }

    /** The display name of the supplier a store belongs to. */
    private static String orgNameOf(AppOutlet store) {
        return store == null ? null : store.orgName();
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String marks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private int page(Integer limit) {
        return limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_PAGE);
    }
}
