package com.costonomy.mp.credit.web;

import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.service.CreditExportService;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** CSV exports of credit (B13, D-172). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditExportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final CreditExportService exports;

    @GetMapping("/credit/agreements/{id}/statement.csv")
    @Operation(summary = "A credit statement as a CSV file",
            description = """
                    The same statement as `GET /statement` (same access: the restaurant's people or the supplier's,
                    anyone else a 404; same `from`/`to`, default last 90 days, at most 366 days), as a file: a few
                    lines naming the restaurant, outlet, supplier and period with `Opening owed` and `Closing owed`,
                    a blank line, then one row per statement line, newest first, with plain decimals (two places,
                    signed), India time with offset, UTF-8 without a byte order mark. Cells that start with `= + - @`
                    carry a leading `'`. `Content-Disposition` names it `statement-<outlet>-<from>-<to>.csv`. More
                    than 20,000 rows is 413 `CREDIT_EXPORT_TOO_LARGE`. Writes a `CREDIT_EXPORT` audit row.
                    """)
    public ResponseEntity<byte[]> statement(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return file(exports.statement(ActorContext.requireUserId(), id, from, to));
    }

    @GetMapping("/supplier-stores/{storeId}/credit/collections.csv")
    @Operation(summary = "The store's collections as a CSV file",
            description = """
                    Every payment received on the store's credit invoices, newest first, as in `/credit/payments`
                    (same access, same `from`, `to` and `source`): payment id, paid at (India time with offset),
                    restaurant, outlet, invoice, amount, source, method, reference. Names only, no phone numbers.
                    More than 20,000 rows is 413 `CREDIT_EXPORT_TOO_LARGE`. Writes a `CREDIT_EXPORT` audit row.
                    """)
    public ResponseEntity<byte[]> collections(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) CreditPaymentSource source) {
        return file(exports.collections(ActorContext.requireUserId(), storeId, from, to, source));
    }

    private static ResponseEntity<byte[]> file(CreditExportService.CsvFile file) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.body());
    }
}
