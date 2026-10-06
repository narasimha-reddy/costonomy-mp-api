package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentClaimRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The supplier's daily credit digest (B14, D-148): one notification per store, built from what the server counts, sent
 * from 08:30 India time (never after 20:00) to the store's people who may see credit, in-app and push, never SMS.
 *
 * <p>Looked at once per store per India day: {@code credit_digest_log} has a unique (store, day), so a restart or a
 * second node cannot send it twice. A store whose figures are all zero is logged with {@code sent = 0} and nothing is
 * sent; if something arrives later the same day it waits for tomorrow's digest.
 */
@Service
@Slf4j
public class CreditDigestService {

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID,
            CreditInvoiceStatus.WRITTEN_OFF);
    /** "Due this week": today and the six days after it, as on the receivables screen. */
    private static final int WEEK_DAYS = 7;

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditPaymentClaimRepository claims;
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final LocalTime digestTime;

    public CreditDigestService(CreditAgreementRepository agreements, CreditInvoiceRepository invoices,
                               CreditPaymentClaimRepository claims, JdbcTemplate jdbc, OutboxService outbox,
                               TransactionTemplate txTemplate, @Qualifier("creditClock") Clock clock,
                               @Value("${costonomy.mp.credit.digest-time:08:30}") String digestTime) {
        this.agreements = agreements;
        this.invoices = invoices;
        this.claims = claims;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.digestTime = LocalTime.parse(digestTime);
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.credit.digest-interval:PT10M}")
    @SchedulerLock(name = "credit-digest", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void runDigest() {
        int sent = sendDigests();
        if (sent > 0) {
            log.info("Sent {} credit digests", sent);
        }
    }

    /** Sends today's digest to every store that has not had it looked at yet. Returns how many were sent. */
    public int sendDigests() {
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(digestTime) || now.getHour() >= CreditReminderService.WINDOW_CLOSES) {
            return 0;
        }
        LocalDate today = LocalDate.now(clock);
        int sent = 0;
        for (Long storeId : jdbc.queryForList("select distinct supplier_store_id from credit_agreement order by 1",
                Long.class)) {
            try {
                if (Boolean.TRUE.equals(txTemplate.execute(status -> digestFor(storeId, today)))) {
                    sent++;
                }
            } catch (DuplicateKeyException twice) {
                // Another node looked at this store first.
                log.debug("Credit digest for store {} was already handled today", storeId);
            } catch (RuntimeException ex) {
                log.error("Could not build the credit digest for store {}", storeId, ex);
            }
        }
        return sent;
    }

    private boolean digestFor(Long storeId, LocalDate today) {
        Integer done = jdbc.queryForObject(
                "select count(*) from credit_digest_log where supplier_store_id = ? and digest_date = ?",
                Integer.class, storeId, today);
        if (done != null && done > 0) {
            return false;
        }

        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal dueThisWeek = BigDecimal.ZERO;
        var overdueLines = new java.util.HashSet<Long>();
        for (CreditInvoice invoice : invoices.findBySupplierStoreIdAndStatusNotIn(storeId, SETTLED)) {
            var state = CreditDueState.of(invoice.getStatus(), invoice.getDueDate(), invoice.getOverdueAfter(), today);
            if (state == CreditDueState.OVERDUE) {
                overdue = overdue.add(invoice.outstanding());
                overdueLines.add(invoice.getCreditAgreementId());
            } else if ((state == CreditDueState.DUE_TODAY || state == CreditDueState.DUE_SOON
                    || state == CreditDueState.DUE_LATER)
                    && ChronoUnit.DAYS.between(today, invoice.getDueDate()) < WEEK_DAYS) {
                dueThisWeek = dueThisWeek.add(invoice.outstanding());
            }
        }

        var waiting = claims.findBySupplierStoreIdAndStatusOrderByIdDesc(storeId, CreditClaimStatus.SUBMITTED);
        int stale = (int) waiting.stream().filter(c -> CreditClaimAge.isStale(c.getStatus(),
                CreditClaimAge.ageDays(c.getCreatedAt(), today, clock.getZone()))).count();
        int requests = (int) agreements.findBySupplierStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(a -> a.getStatus() == CreditAgreementStatus.REQUESTED).count();
        Integer payouts = jdbc.queryForObject(
                "select count(*) from credit_repayment_payout where supplier_store_id = ? and status = 'PENDING'",
                Integer.class, storeId);

        String message = CreditReminderText.digest(waiting.size(), stale, overdue, overdueLines.size(), dueThisWeek,
                requests, payouts == null ? 0 : payouts);
        boolean send = !message.isEmpty();

        jdbc.update("""
                insert into credit_digest_log (supplier_store_id, digest_date, sent, claims_waiting, claims_stale,
                    overdue_amount, overdue_restaurants, due_week_amount, requests_pending, payouts_pending, message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, storeId, today, send, waiting.size(), stale, overdue, overdueLines.size(), dueThisWeek, requests,
                payouts == null ? 0 : payouts, send ? message : null);
        if (!send) {
            return false;
        }

        var payload = new HashMap<String, Object>();
        payload.put("supplierStoreId", storeId);
        payload.put("digestDate", today.toString());
        payload.put("message", message);
        outbox.publish(CreditEvents.SUPPLIER_DIGEST, "SUPPLIER_STORE", storeId, payload, null);
        return true;
    }
}
