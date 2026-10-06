package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * CSV exports of credit (B13, D-146): one line's statement, either side, and a store's collections, supplier side.
 *
 * <p>The statement is the JSON statement rendered: it is read through {@link CreditReadService#statement}, the same
 * code, access rules and window as {@code GET /statement}, so the two cannot disagree on a row, the opening or the
 * closing figure. Every export writes a {@code CREDIT_EXPORT} audit row (who, which line or store, which range) and is
 * capped at {@value #MAX_ROWS} rows: beyond that it is refused with 413 {@code CREDIT_EXPORT_TOO_LARGE}, never cut off
 * silently. Names only: no phone numbers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditExportService {

    public static final int MAX_ROWS = 20_000;
    private static final int PAGE = CreditSupplierReadService.MAX_PAGE_SIZE;
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final CreditReadService reads;
    private final CreditSupplierReadService supplierReads;
    private final CreditAgreementRepository agreements;
    private final CreditDirectory directory;
    private final AuditService auditService;
    // Field-injected: Lombok's constructor would drop the @Qualifier, and there must be no unqualified Clock here.
    @Autowired
    @Qualifier("creditClock")
    private Clock clock;
    /** Overridable so a test can reach the limit with a handful of rows. */
    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.credit.export-max-rows:20000}")
    private int maxRows = MAX_ROWS;

    /** A finished file: name, and the bytes (UTF-8, no byte order mark). */
    public record CsvFile(String filename, byte[] body) {
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private String at(java.time.Instant instant) {
        return AT.format(instant.atZone(zone()));
    }

    // ── Statement ────────────────────────────────────────────────────────

    public CsvFile statement(Long actorId, Long agreementId, LocalDate from, LocalDate to) {
        // Access (404 for anyone but the two sides), the window and the 366-day limit are the JSON statement's.
        var statement = reads.statement(actorId, agreementId, from, to);
        if (statement.lines().size() > maxRows) {
            throw tooLarge(statement.lines().size());
        }
        var agreement = agreements.findById(agreementId).orElseThrow();
        var outlet = directory.outlet(agreement.getOutletId());
        var store = directory.store(agreement.getSupplierStoreId());

        var csv = new CreditCsv();
        csv.text("Credit statement", "Mandi credit reference, not a GST tax invoice");
        csv.text("Restaurant", outlet == null ? "" : outlet.restaurantName());
        csv.text("Outlet", outlet == null ? "" : outlet.outletName());
        csv.text("Supplier", store == null ? "" : store.supplierName());
        csv.text("Store", store == null ? "" : store.storeName());
        csv.text("From", statement.from().toString());
        csv.text("To", statement.to().toString());
        csv.row("Opening owed", statement.openingOwed());
        csv.row("Closing owed", statement.closingOwed());
        csv.blank();
        csv.text("At (IST)", "Type", "Description", "Amount", "Owed after", "Order", "Invoice", "Paid by", "Method",
                "Reference");
        for (CreditDtos.StatementLine line : statement.lines()) {
            csv.row(at(line.at()), line.type(), line.label(), line.amount(), line.owedAfter(),
                    line.orderNumber(), line.invoiceNumber(), line.source(), line.method(), line.reference());
        }

        auditService.record(actorId, null, "CREDIT_EXPORT", "CREDIT_AGREEMENT", agreementId, null,
                "STATEMENT_CSV rows=" + statement.lines().size(),
                "Statement " + statement.from() + " to " + statement.to(), "API");
        String name = "statement-%s-%s-%s.csv".formatted(slug(outlet == null ? null : outlet.outletName(),
                "outlet-" + agreement.getOutletId()), statement.from(), statement.to());
        return new CsvFile(name, csv.bytes());
    }

    // ── Collections ──────────────────────────────────────────────────────

    public CsvFile collections(Long actorId, Long storeId, LocalDate from, LocalDate to, CreditPaymentSource source) {
        // The same access and filters as the JSON feed; its first page also says how many rows there are.
        var first = supplierReads.storePayments(actorId, storeId, from, to, source, 0, PAGE);
        if (first.total() > maxRows) {
            throw tooLarge(first.total());
        }

        var csv = new CreditCsv();
        csv.text("Payment id", "Paid at (IST)", "Restaurant", "Outlet", "Invoice", "Amount", "Source", "Method",
                "Reference");
        var page = first;
        int number = 0;
        long rows = 0;
        while (true) {
            for (CreditDtos.PaymentFeedItem item : page.items()) {
                csv.row(item.id(), at(item.paidAt()), item.restaurantName(), item.outletName(), item.invoiceNumber(),
                        item.amount(), item.source(), item.method(), item.reference());
                rows++;
            }
            if (!page.hasNext()) {
                break;
            }
            page = supplierReads.storePayments(actorId, storeId, from, to, source, ++number, PAGE);
        }

        auditService.record(actorId, null, "CREDIT_EXPORT", "SUPPLIER_STORE", storeId, null,
                "COLLECTIONS_CSV rows=" + rows,
                "Collections " + (from == null ? "start" : from) + " to " + (to == null ? "today" : to), "API");
        String name = "collections-store-%d-%s-%s.csv".formatted(storeId, from == null ? "start" : from.toString(),
                to == null ? LocalDate.now(clock).toString() : to.toString());
        return new CsvFile(name, csv.bytes());
    }

    private BusinessException tooLarge(long rows) {
        return new BusinessException(ErrorCode.CREDIT_EXPORT_TOO_LARGE,
                "That is %d rows; one export holds at most %d. Choose a shorter period.".formatted(rows, maxRows),
                Map.of("max", maxRows, "rows", rows));
    }

    /** A name safe to put in a file name: lower case letters and digits, single dashes. */
    static String slug(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? fallback : slug;
    }
}
