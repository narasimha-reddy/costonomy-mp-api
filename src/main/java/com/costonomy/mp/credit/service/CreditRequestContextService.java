package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.web.dto.CreditLifecycleDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a supplier sees about the restaurant asking it for credit (D-168, decision 15).
 *
 * <p><b>Only this store's own data about this outlet.</b> Every query below is keyed on BOTH the outlet and this
 * store; there is no query that looks at the outlet alone. A restaurant's orders and credit with other suppliers are
 * those suppliers' business and the restaurant's, and showing them would hand one supplier the others' trading
 * data. The privacy test fails if any figure here ever includes another store.
 *
 * <p>Plain SQL across the module edge (orders), like {@link CreditDirectory}: read-only, no dependency on the order
 * module's internals.
 */
@Service
@RequiredArgsConstructor
public class CreditRequestContextService {

    /** The window for the recent-order figures. */
    static final int WINDOW_DAYS = 90;

    /** The audit actions that tell the story of a line, and the name each goes by in the response. */
    private static final Map<String, String> HISTORY_EVENTS = Map.of(
            "CREDIT_REQUESTED", "REQUESTED",
            "CREDIT_APPROVED", "APPROVED",
            "CREDIT_MODIFIED", "MODIFIED",
            "CREDIT_REJECTED", "REJECTED",
            "CREDIT_SUSPENDED", "SUSPENDED",
            "CREDIT_REINSTATED", "REINSTATED",
            "CREDIT_CLOSED", "CLOSED",
            "CREDIT_OFFER_EXPIRED", "EXPIRED");

    /** How a past line ended. */
    private static final List<String> ENDINGS = List.of("REJECTED", "CLOSED", "EXPIRED");

    /** Orders the supplier actually took on: neither a draft nor a cancelled one. */
    private static final String COUNTED = "status not in ('DRAFT', 'CANCELLED')";

    private final CreditAgreementRepository agreements;
    private final AccessControlService accessControl;
    private final CreditDirectory directory;
    private final CreditInvoiceService invoiceService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public CreditLifecycleDtos.RequestContextResponse context(Long actorId, Long storeId, Long agreementId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_REQUEST_VIEW, ScopeType.SUPPLIER_STORE, storeId,
                "SupplierStore");
        var agreement = agreements.findById(agreementId).orElse(null);
        // An agreement of another store is "not found" here, whoever asks.
        if (agreement == null || !agreement.getSupplierStoreId().equals(storeId)) {
            throw new NotFoundException("CreditAgreement", agreementId);
        }
        Long outletId = agreement.getOutletId();
        var zone = invoiceService.zone();
        LocalDate today = invoiceService.today();
        Timestamp since = Timestamp.from(today.minusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant());

        // Orders in the window, this store and this outlet only.
        var recent = jdbc.queryForMap("""
                select count(*) as n,
                       coalesce(sum(case when accepted_amount > 0 then accepted_amount else total_amount end), 0) as v
                  from supplier_order
                 where outlet_id = ? and supplier_store_id = ? and %s and created_at >= ?
                """.formatted(COUNTED), outletId, storeId, since);
        int count = ((Number) recent.get("n")).intValue();
        BigDecimal value = (BigDecimal) recent.get("v");
        BigDecimal average = count == 0 ? null : value.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

        int cancelled = jdbc.queryForObject("""
                select count(*) from supplier_order
                 where outlet_id = ? and supplier_store_id = ? and status = 'CANCELLED' and created_at >= ?
                """, Integer.class, outletId, storeId, since);

        var span = jdbc.queryForMap("""
                select min(created_at) as first_at, max(created_at) as last_at from supplier_order
                 where outlet_id = ? and supplier_store_id = ? and %s
                """.formatted(COUNTED), outletId, storeId);

        // Invoices of this store to this outlet that were ever late, paid since or not.
        int overdue = jdbc.queryForObject("""
                select count(*) from credit_invoice
                 where outlet_id = ? and supplier_store_id = ?
                   and (marked_overdue_at is not null or status = 'OVERDUE')
                """, Integer.class, outletId, storeId);

        // The story of this line, from the audit trail of THIS agreement (one row per outlet and store).
        var history = new ArrayList<CreditLifecycleDtos.HistoryEvent>();
        String pastStatus = null;
        Instant pastEndedAt = null;
        var rows = jdbc.queryForList("""
                select action, reason, created_at from audit_log
                 where entity_type = 'CREDIT_AGREEMENT' and entity_id = ? and action in (%s)
                 order by id desc limit 100
                """.formatted(String.join(",", java.util.Collections.nCopies(HISTORY_EVENTS.size(), "?"))),
                concat(agreementId, HISTORY_EVENTS.keySet().toArray()));
        for (var row : rows) {
            String event = HISTORY_EVENTS.get((String) row.get("action"));
            Instant at = ((Timestamp) row.get("created_at")).toInstant();
            history.add(new CreditLifecycleDtos.HistoryEvent(at, event, (String) row.get("reason")));
            if (pastStatus == null && ENDINGS.contains(event)) {
                pastStatus = event;
                pastEndedAt = at;
            }
        }

        var outlet = directory.outlet(outletId);
        return new CreditLifecycleDtos.RequestContextResponse(
                agreementId, agreement.getStatus(), outletId,
                outlet == null ? null : outlet.outletName(),
                outlet == null ? null : outlet.restaurantName(),
                today, WINDOW_DAYS, count, value, average, cancelled,
                dayOf(span.get("first_at"), zone), dayOf(span.get("last_at"), zone),
                overdue, pastStatus, pastEndedAt, history);
    }

    private static LocalDate dayOf(Object timestamp, java.time.ZoneId zone) {
        return timestamp == null ? null : LocalDate.ofInstant(((Timestamp) timestamp).toInstant(), zone);
    }

    private static Object[] concat(Object first, Object[] rest) {
        var all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
