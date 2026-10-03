package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.storage.invoice.InvoiceFileSniffer;
import com.costonomy.mp.storage.invoice.InvoiceKeys;
import com.costonomy.mp.storage.invoice.InvoiceLinkSigner;
import com.costonomy.mp.storage.invoice.InvoiceStorage;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.invoice.domain.InvoiceStatus;
import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoice;
import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoicePage;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoicePageRepository;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoiceRepository;
import com.costonomy.mp.wallet.invoice.web.InvoiceDtos;
import com.costonomy.mp.wallet.service.WalletEntryDetailService;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The shop's bill on a wallet payment (D-113): upload, view, remove. One bill per wallet entry; only
 * for payments made from the wallet (an order, or a QuickScan payment). The entry is looked up in the
 * caller's own outlet wallet, so another outlet's entry is a 404, never a 403.
 */
@Service
@Slf4j
public class WalletInvoiceService {

    public static final int MAX_PAGES = 5;
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final java.util.Set<WalletEntryKind> BILLABLE =
            java.util.Set.of(WalletEntryKind.ORDER_PAYMENT, WalletEntryKind.QUICKSCAN_PAYMENT);

    private final EntityManager em;
    private final WalletService wallets;
    private final WalletEntryInvoiceRepository invoices;
    private final WalletEntryInvoicePageRepository pages;
    private final InvoiceStorage storage;
    private final InvoiceLinkSigner signer;
    private final InvoiceProperties props;
    private final InvoiceReadingService reading;
    private final AuditService audit;
    private final ObjectMapper json;
    private final ObjectMapper stored;
    private final TransactionTemplate tx;
    private final com.costonomy.mp.wallet.invoice.costapi.CostOutletMap outletMap;

    public static final int MAX_IDEMPOTENCY_KEY = 128;
    static final int HISTORY_KEPT = 20;

    public WalletInvoiceService(EntityManager em, WalletService wallets, WalletEntryInvoiceRepository invoices,
                                WalletEntryInvoicePageRepository pages, InvoiceStorage storage,
                                InvoiceLinkSigner signer, InvoiceProperties props, InvoiceReadingService reading,
                                AuditService audit, ObjectMapper json, PlatformTransactionManager txManager,
                                com.costonomy.mp.wallet.invoice.costapi.CostOutletMap outletMap) {
        this.em = em;
        this.wallets = wallets;
        this.invoices = invoices;
        this.pages = pages;
        this.storage = storage;
        this.signer = signer;
        this.props = props;
        this.reading = reading;
        this.audit = audit;
        this.json = json;
        this.stored = InvoiceJson.storage(json);
        this.tx = new TransactionTemplate(txManager);
        this.outletMap = outletMap;
    }

    // ── upload ───────────────────────────────────────────────────────────

    public InvoiceDtos.Invoice upload(Long outletId, String entryKey, Long actorId, List<MultipartFile> files) {
        WalletTransaction entry = tx.execute(s -> entryOf(outletId, entryKey));
        if (!BILLABLE.contains(entry.getKind())) {
            throw new BusinessException(ErrorCode.INVOICE_NOT_ALLOWED);
        }
        if (files == null || files.isEmpty() || files.size() > MAX_PAGES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Add 1 to " + MAX_PAGES + " files: photos or a PDF of the bill.");
        }
        if (invoices.findByWalletTransactionId(entry.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.INVOICE_EXISTS);
        }
        Instant dayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        if (invoices.countByOutletIdAndCreatedAtGreaterThanEqual(outletId, dayStart) >= props.dailyCap) {
            throw new BusinessException(ErrorCode.INVOICE_LIMIT_REACHED);
        }

        // Look at every file before storing any.
        var contents = new ArrayList<byte[]>();
        var kinds = new ArrayList<InvoiceFileSniffer.Kind>();
        for (MultipartFile file : files) {
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "We could not read one of the files.");
            }
            kinds.add(InvoiceFileSniffer.identify(bytes));
            contents.add(bytes);
        }

        UUID batch = UUID.randomUUID();
        YearMonth month = YearMonth.now(IST);
        var storedKeys = new ArrayList<String>();
        try {
            for (int i = 0; i < contents.size(); i++) {
                String key = InvoiceKeys.forPage(outletId, month, batch, i + 1, kinds.get(i).extension());
                storage.put(key, contents.get(i), kinds.get(i).contentType());
                storedKeys.add(key);
            }
            WalletEntryInvoice saved = tx.execute(s -> {
                var invoice = new WalletEntryInvoice();
                invoice.setWalletTransactionId(entry.getId());
                invoice.setOutletId(outletId);
                invoice.setUploadedBy(actorId);
                invoice = invoices.saveAndFlush(invoice);
                for (int i = 0; i < contents.size(); i++) {
                    var page = new WalletEntryInvoicePage();
                    page.setInvoiceId(invoice.getId());
                    page.setPageNo(i + 1);
                    page.setStorageKey(storedKeys.get(i));
                    page.setContentType(kinds.get(i).contentType());
                    page.setSizeBytes(contents.get(i).length);
                    page.setSha256(sha256(contents.get(i)));
                    pages.save(page);
                }
                audit.record(actorId, null, "WALLET_INVOICE_ADDED", "WALLET_ENTRY_INVOICE", invoice.getId(),
                        null, InvoiceStatus.READING.name(), null, null);
                return invoice;
            });
            reading.schedule(saved.getId());
            return view(saved, entry.getAmount());
        } catch (DataIntegrityViolationException e) {
            // Two uploads for one entry at once: the unique key let one through.
            removeFiles(storedKeys);
            throw new BusinessException(ErrorCode.INVOICE_EXISTS);
        } catch (RuntimeException e) {
            removeFiles(storedKeys);
            throw e;
        }
    }

    // ── read ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public InvoiceDtos.Invoice get(Long outletId, String entryKey) {
        WalletTransaction entry = entryOf(outletId, entryKey);
        var invoice = invoices.findByWalletTransactionId(entry.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_NOT_FOUND));
        return view(invoice, entry.getAmount());
    }

    /** The summary for the transaction page, or empty when the entry has no bill. */
    @Transactional(readOnly = true)
    public Optional<InvoiceDtos.Summary> summaryFor(Long entryId) {
        return invoices.findByWalletTransactionId(entryId).map(invoice -> {
            var first = pages.findByInvoiceIdOrderByPageNo(invoice.getId()).stream().findFirst();
            String thumb = first.map(p -> linkFor(invoice.getOutletId(), p, Instant.now().plus(props.linkTtl)))
                    .orElse(null);
            var review = storedReview(invoice);
            String vendor = review != null && review.supplier() != null ? review.supplier().name() : invoice.getVendorName();
            return new InvoiceDtos.Summary(invoice.getStatus().name(), vendor,
                    InvoiceReviews.bestTotal(review, invoice.getTotal()), thumb);
        });
    }

    // ── remove ───────────────────────────────────────────────────────────

    public void delete(Long outletId, String entryKey, Long actorId) {
        var removed = tx.execute(s -> {
            WalletTransaction entry = entryOf(outletId, entryKey);
            var invoice = invoices.findByWalletTransactionId(entry.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_NOT_FOUND));
            var keys = pages.findByInvoiceIdOrderByPageNo(invoice.getId()).stream()
                    .map(WalletEntryInvoicePage::getStorageKey).toList();
            audit.record(actorId, null, "WALLET_INVOICE_REMOVED", "WALLET_ENTRY_INVOICE", invoice.getId(),
                    invoice.getStatus().name(), null, null, null);
            pages.deleteByInvoiceId(invoice.getId());
            invoices.delete(invoice);
            return keys;
        });
        // After the rows are gone: a failure here leaves an orphan file, never a bill that points at nothing.
        removeFiles(removed);
    }

    // ── review (D-114) ───────────────────────────────────────────────────

    /**
     * Saves the user's version of the bill: supplier, number, dates, payment status, lines, delivery and the bill-level
     * tax. The money is computed here; the reading is kept as it was. Only for a bill that was read, or could not be
     * read (then filled by hand); 409 while it is still being read, and 409 INVOICE_CHANGED when {@code version} is not
     * the bill's current one. Nothing is sent to the cost app.
     *
     * <p>D-115: {@code idempotencyKey} (optional) makes a retried save safe. The last key is kept with the hash of
     * what it carried: the same key with the same body answers the bill as it is now (200, no new version, no 409);
     * the same key with another body is 422 IDEMPOTENCY_KEY_REUSED. Each save keeps the earlier review's total in
     * the history and records the old and new totals and the version in the audit.
     */
    public InvoiceDtos.Invoice review(Long outletId, String entryKey, Long actorId,
                                      InvoiceReviewDtos.ReviewRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Send the reviewed bill.",
                    java.util.Map.of("fields", java.util.Map.of("body", "Send the reviewed bill.")));
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        if (key != null && key.length() > MAX_IDEMPOTENCY_KEY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    java.util.Map.of("fields", java.util.Map.of("Idempotency-Key",
                            "Use a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
        }
        String hash = key == null ? null : bodyHash(request);
        try {
            return tx.execute(s -> {
                WalletTransaction entry = entryOf(outletId, entryKey);
                var invoice = invoices.findByWalletTransactionId(entry.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_NOT_FOUND));
                if (key != null && key.equals(invoice.getReviewIdemKey())) {
                    if (hash.equals(invoice.getReviewIdemHash())) {
                        return view(invoice, entry.getAmount()); // a retry of a save that went through
                    }
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                if (invoice.getStatus() == InvoiceStatus.READING) {
                    throw new BusinessException(ErrorCode.INVOICE_STILL_READING);
                }
                if (request.version() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            ErrorCode.VALIDATION_ERROR.defaultMessage(),
                            java.util.Map.of("fields", java.util.Map.of("version", "Send the bill's version.")));
                }
                if (!request.version().equals(invoice.getVersion())) {
                    throw new BusinessException(ErrorCode.INVOICE_CHANGED);
                }
                InvoiceReading read = invoice.getStatus() == InvoiceStatus.READ ? storedReading(invoice) : null;
                Instant now = Instant.now();
                var review = InvoiceReviews.review(request, read, LocalDate.now(IST), now, actorId);
                var previous = storedReview(invoice);
                Long versionBefore = invoice.getVersion();
                try {
                    if (previous != null) {
                        invoice.setReviewHistoryJson(appendHistory(invoice.getReviewHistoryJson(), previous,
                                versionBefore));
                    }
                    invoice.setReviewJson(stored.writeValueAsString(review));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("Could not write the review", e);
                }
                invoice.setReviewedAt(now);
                invoice.setReviewedBy(actorId);
                invoice.setReviewIdemKey(key);
                invoice.setReviewIdemHash(hash);
                // The row always changes here (reviewedAt at least), so this save makes exactly the next version.
                invoice.setReviewIdemVersion(key == null ? null : versionBefore + 1);
                invoices.saveAndFlush(invoice);
                String reason = "total " + (previous == null ? "unreviewed (read " + money(invoice.getTotal()) + ")"
                        : money(previous.total())) + " -> " + money(review.total()) + ", version " + versionBefore
                        + " -> " + invoice.getVersion();
                audit.record(actorId, null, "WALLET_INVOICE_REVIEW", "WALLET_ENTRY_INVOICE", invoice.getId(),
                        previous == null ? "UNREVIEWED" : "REVIEWED", "REVIEWED", reason, null);
                return view(invoice, entry.getAmount());
            });
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // Saved, read or reviewed by someone else between our check and our write.
            throw new BusinessException(ErrorCode.INVOICE_CHANGED);
        }
    }

    /** The earlier reviews' totals, newest last, at most {@link #HISTORY_KEPT}. */
    private String appendHistory(String history, InvoiceReviewDtos.Review previous, Long version)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var list = new ArrayList<java.util.Map<String, Object>>();
        if (history != null) {
            try {
                list.addAll(stored.readValue(history,
                        new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {
                        }));
            } catch (IOException e) {
                log.warn("Review history could not be read; starting it again");
            }
        }
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("at", previous.reviewedAt() == null ? null : previous.reviewedAt().toString());
        entry.put("by", previous.reviewedBy());
        entry.put("total", previous.total() == null ? null : previous.total().toPlainString());
        entry.put("version", version);
        list.add(entry);
        while (list.size() > HISTORY_KEPT) {
            list.remove(0);
        }
        return stored.writeValueAsString(list);
    }

    private String bodyHash(InvoiceReviewDtos.ReviewRequest request) {
        try {
            return sha256(json.writeValueAsBytes(request));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not hash the review", e);
        }
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "none" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private InvoiceReading storedReading(WalletEntryInvoice invoice) {
        if (invoice.getReadingJson() == null) {
            return null;
        }
        try {
            return stored.readValue(invoice.getReadingJson(), InvoiceReading.class);
        } catch (IOException e) {
            log.warn("Stored reading of invoice {} could not be parsed", invoice.getId());
            return null;
        }
    }

    private InvoiceReviewDtos.Review storedReview(WalletEntryInvoice invoice) {
        if (invoice.getReviewJson() == null) {
            return null;
        }
        try {
            return stored.readValue(invoice.getReviewJson(), InvoiceReviewDtos.Review.class);
        } catch (IOException e) {
            log.warn("Stored review of invoice {} could not be parsed", invoice.getId());
            return null;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private WalletTransaction entryOf(Long outletId, String entryKey) {
        Long entryId = WalletEntryDetailService.parse(entryKey);
        var wallet = wallets.find(outletId).orElseThrow(WalletInvoiceService::entryNotFound);
        return em.createQuery("select t from WalletTransaction t where t.id = :id and t.walletId = :w",
                        WalletTransaction.class)
                .setParameter("id", entryId).setParameter("w", wallet.getId())
                .getResultStream().findFirst().orElseThrow(WalletInvoiceService::entryNotFound);
    }

    private static BusinessException entryNotFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "That wallet entry wasn't found.");
    }

    private InvoiceDtos.Invoice view(WalletEntryInvoice invoice, java.math.BigDecimal paid) {
        Instant expires = Instant.now().plus(props.linkTtl);
        var pageViews = new ArrayList<InvoiceDtos.Page>();
        for (var p : pages.findByInvoiceIdOrderByPageNo(invoice.getId())) {
            pageViews.add(new InvoiceDtos.Page(p.getPageNo(), p.getContentType(), p.getSizeBytes(),
                    linkFor(invoice.getOutletId(), p, expires), expires));
        }
        InvoiceReading read = storedReading(invoice);
        if (read != null && outletMap.costOutletFor(invoice.getOutletId()).isEmpty()) {
            read = read.withoutMatches(); // no cost outlet for this outlet (any more): no other business's data
        }
        var review = storedReview(invoice);
        InvoiceReviewDtos.Review draft = null;
        if (invoice.getStatus() != InvoiceStatus.READING) {
            // Always there once read (D-115), also next to a review: "start over" goes back to what was read.
            draft = InvoiceReviews.draft(invoice.getStatus() == InvoiceStatus.READ ? read : null, LocalDate.now(IST));
        }
        return new InvoiceDtos.Invoice(invoice.getStatus().name(), invoice.getCreatedAt(), invoice.getUploadedBy(),
                pageViews.size(), pageViews, read,
                InvoiceCheck.of(paid, InvoiceReviews.bestTotal(review, invoice.getTotal()), invoice.getTotal()),
                invoice.getErrorText(), invoice.getAttempts(), invoice.getUnavailableCount(), invoice.getNextTryAt(),
                invoice.getVersion(), draft, review);
    }

    private String linkFor(Long outletId, WalletEntryInvoicePage page, Instant expires) {
        var direct = storage.openOrPresign(page.getStorageKey(), props.linkTtl);
        if (direct.isPresent()) {
            return direct.get();
        }
        return props.linkBaseUrl + "/invoice-files/" + signer.sign(outletId, page.getId(), expires);
    }

    private void removeFiles(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.error("Could not remove stored invoice page {}", key, e);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
