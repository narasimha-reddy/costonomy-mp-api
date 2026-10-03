package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException;
import com.costonomy.mp.wallet.invoice.costapi.CostOutletMap;
import com.costonomy.mp.storage.invoice.InvoiceStorage;
import com.costonomy.mp.wallet.invoice.domain.InvoiceStatus;
import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoice;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReadException;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoicePageRepository;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoiceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * Reads a stored bill, off the upload's path (D-113). The upload commits first and returns; this runs
 * afterwards on a bounded executor. A failed reading keeps the status READING and counts an attempt; the
 * job retries it, and after the attempts allowed the bill is UNREADABLE. The stored pages are never
 * touched, so a bill that cannot be read can still be looked at.
 *
 * <p>No database transaction is held while the reader runs: the attempt is claimed in one short
 * transaction (a version check, so two workers cannot both take it) and its result written in another.
 *
 * <p>D-115:
 * <ul>
 *   <li>The claim counts the call ({@code extractCalls}) before the reader runs, in its own committed transaction,
 *       and refuses once max-attempts calls were made: one bill can never cause more extraction calls than that,
 *       whatever happens after. A call refused before any reading started (sign-in failure, 403, 429) is given
 *       back.</li>
 *   <li>The cost app unavailable is not the bill's fault: no attempt is used, the bill stays READING with the plain
 *       retry sentence, and the next try waits ({@code nextTryAt}, doubling up to 30 minutes). After
 *       max-unavailable such tries the bill is UNREADABLE.</li>
 *   <li>The reading is made to fit what is stored before it is written ({@link InvoiceReading#capped()}). If the
 *       write still fails, a failed attempt is counted in a fresh transaction, so the attempts always move on and
 *       the bill ends UNREADABLE rather than READING forever.</li>
 *   <li>A bill whose outlet has no cost outlet ({@link CostOutletMap}) is read without the cost app's matches.</li>
 * </ul>
 */
@Service
@Slf4j
public class InvoiceReadingService {

    public static final String UNREADABLE_SENTENCE = "We could not read this bill. You can still view the photo.";
    static final String RETRY_SENTENCE = "We could not read this bill yet. We will try again.";

    private final WalletEntryInvoiceRepository invoices;
    private final WalletEntryInvoicePageRepository pages;
    private final InvoiceStorage storage;
    private final InvoiceReader reader;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;
    private final TransactionTemplate freshTx;
    private final InvoiceProperties props;
    private final ObjectMapper json;
    private final CostOutletMap outletMap;

    public InvoiceReadingService(WalletEntryInvoiceRepository invoices, WalletEntryInvoicePageRepository pages,
                                 InvoiceStorage storage, InvoiceReader reader,
                                 @org.springframework.beans.factory.annotation.Qualifier("invoiceReadExecutor")
                                 ThreadPoolTaskExecutor executor,
                                 PlatformTransactionManager txManager, InvoiceProperties props, ObjectMapper json,
                                 CostOutletMap outletMap) {
        this.invoices = invoices;
        this.pages = pages;
        this.storage = storage;
        this.reader = reader;
        this.executor = executor;
        this.tx = new TransactionTemplate(txManager);
        this.freshTx = new TransactionTemplate(txManager);
        this.freshTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.props = props;
        this.json = InvoiceJson.storage(json);
        this.outletMap = outletMap;
    }

    /** Queues the first reading. A full queue is not an error: the job picks the bill up later. */
    public void schedule(long invoiceId) {
        try {
            executor.execute(() -> attempt(invoiceId, false));
        } catch (RejectedExecutionException e) {
            log.warn("Invoice reading queue is full; invoice {} will be picked up by the retry job", invoiceId);
        }
    }

    /** The retry job's run: bills still READING and due. */
    public int retryDue(int batch) {
        Instant now = Instant.now();
        var due = invoices.dueForReading(InvoiceStatus.READING, now.minus(props.retryMinAge), now,
                PageRequest.of(0, batch));
        for (Long id : due) {
            attempt(id, true);
        }
        return due.size();
    }

    /** One reading attempt. Never throws: a failure is recorded on the bill. */
    public void attempt(long invoiceId, boolean onlyIfDue) {
        try {
            doAttempt(invoiceId, onlyIfDue);
        } catch (RuntimeException e) {
            log.error("Reading invoice {} failed unexpectedly", invoiceId, e);
        }
    }

    private enum Outcome { READ, FAILED, UNAVAILABLE }

    private void doAttempt(long invoiceId, boolean onlyIfDue) {
        record Claim(long outletId, List<WalletPageRef> refs) {
        }
        Claim claim;
        try {
            claim = tx.execute(status -> {
                var invoice = invoices.findById(invoiceId).orElse(null);
                if (invoice == null || invoice.getStatus() != InvoiceStatus.READING) {
                    return null;
                }
                Instant now = Instant.now();
                if (onlyIfDue && !due(invoice, now)) {
                    return null;
                }
                if (invoice.getExtractCalls() >= props.maxAttempts
                        || invoice.getUnavailableCount() >= props.maxUnavailable) {
                    // The ceiling: no more calls for this bill, whatever went wrong before.
                    giveUp(invoice);
                    invoices.saveAndFlush(invoice);
                    return null;
                }
                invoice.setLastAttemptAt(now);
                invoice.setExtractCalls(invoice.getExtractCalls() + 1);
                invoices.saveAndFlush(invoice);
                var refs = new ArrayList<WalletPageRef>();
                pages.findByInvoiceIdOrderByPageNo(invoiceId)
                        .forEach(p -> refs.add(new WalletPageRef(p.getPageNo(), p.getStorageKey(), p.getContentType())));
                return new Claim(invoice.getOutletId(), refs);
            });
        } catch (OptimisticLockingFailureException e) {
            return; // another worker took this attempt
        }
        if (claim == null) {
            return;
        }

        Long costOutlet = outletMap.costOutletFor(claim.outletId()).orElse(null);
        InvoiceReading readingFound = null;
        Outcome outcome;
        boolean giveBackCall = false;
        try {
            var files = new ArrayList<InvoiceReader.InvoiceFile>();
            for (var ref : claim.refs()) {
                byte[] bytes = storage.read(ref.key()).orElseThrow(
                        () -> new InvoiceReadException("A stored page is missing.", null));
                files.add(new InvoiceReader.InvoiceFile("page-" + ref.page() + "." + ext(ref.contentType()),
                        ref.contentType(), bytes));
            }
            var result = reader.read(files, new InvoiceReader.ReadContext(claim.outletId(), invoiceId, costOutlet));
            if (result != null && result.reading() != null) {
                readingFound = result.reading().capped();
                if (costOutlet == null) {
                    readingFound = readingFound.withoutMatches();
                }
                outcome = Outcome.READ;
            } else {
                outcome = Outcome.FAILED;
            }
        } catch (CostApiUnavailableException e) {
            // Not the bill's fault (D-114, D-115): no attempt is used up, and the next try waits.
            outcome = Outcome.UNAVAILABLE;
            giveBackCall = !e.extractionMayHaveRun();
            log.warn("Reading invoice {} postponed: {}", invoiceId, e.getMessage());
        } catch (RuntimeException e) {
            // The cause can quote the reader's body or a URL; the class and our own message are enough.
            String problem = e instanceof InvoiceReadException ? e.getMessage() : e.getClass().getSimpleName();
            outcome = Outcome.FAILED;
            log.warn("Reading invoice {} failed: {}", invoiceId, problem);
        }

        final InvoiceReading found = readingFound;
        final Outcome result = outcome;
        final boolean giveBack = giveBackCall;
        try {
            tx.executeWithoutResult(status -> {
                var invoice = invoices.findById(invoiceId).orElse(null);
                if (invoice == null || invoice.getStatus() != InvoiceStatus.READING) {
                    return; // removed, or settled by someone else, while the reader worked
                }
                Instant now = Instant.now();
                if (result == Outcome.UNAVAILABLE) {
                    if (giveBack) {
                        invoice.setExtractCalls(Math.max(0, invoice.getExtractCalls() - 1));
                    }
                    invoice.setUnavailableCount(invoice.getUnavailableCount() + 1);
                    invoice.setNextTryAt(now.plus(props.backoff(invoice.getUnavailableCount())));
                    invoice.setErrorText(RETRY_SENTENCE);
                    if (invoice.getUnavailableCount() >= props.maxUnavailable
                            || invoice.getExtractCalls() >= props.maxAttempts && !giveBack) {
                        giveUp(invoice);
                    }
                    invoices.saveAndFlush(invoice);
                    return;
                }
                invoice.setAttempts(invoice.getAttempts() + 1);
                invoice.setNextTryAt(null);
                if (result == Outcome.READ) {
                    apply(invoice, found);
                } else if (invoice.getAttempts() >= props.maxAttempts || invoice.getExtractCalls() >= props.maxAttempts) {
                    giveUp(invoice);
                } else {
                    invoice.setErrorText(RETRY_SENTENCE);
                }
                invoices.saveAndFlush(invoice);
            });
        } catch (RuntimeException e) {
            // The result could not be written (a value the table refuses, a lost connection). Count a failed
            // attempt in a fresh transaction, so the bill moves on and is never read again and again for nothing.
            log.error("Writing the reading of invoice {} failed; counting a failed attempt", invoiceId, e);
            countFailedAttempt(invoiceId);
        }
    }

    private void countFailedAttempt(long invoiceId) {
        try {
            freshTx.executeWithoutResult(status -> {
                var invoice = invoices.findById(invoiceId).orElse(null);
                if (invoice == null || invoice.getStatus() != InvoiceStatus.READING) {
                    return;
                }
                invoice.setAttempts(invoice.getAttempts() + 1);
                invoice.setNextTryAt(null);
                if (invoice.getAttempts() >= props.maxAttempts || invoice.getExtractCalls() >= props.maxAttempts) {
                    giveUp(invoice);
                } else {
                    invoice.setErrorText(RETRY_SENTENCE);
                }
                invoices.saveAndFlush(invoice);
            });
        } catch (RuntimeException e) {
            // The claim already counted the call, so the ceiling still holds.
            log.error("Counting a failed attempt for invoice {} failed too", invoiceId, e);
        }
    }

    private boolean due(WalletEntryInvoice invoice, Instant now) {
        if (invoice.getNextTryAt() != null) {
            return !invoice.getNextTryAt().isAfter(now);
        }
        Instant last = invoice.getLastAttemptAt() != null ? invoice.getLastAttemptAt() : invoice.getCreatedAt();
        return !last.isAfter(now.minus(props.retryMinAge));
    }

    private static void giveUp(WalletEntryInvoice invoice) {
        invoice.setStatus(InvoiceStatus.UNREADABLE);
        invoice.setErrorText(UNREADABLE_SENTENCE);
        invoice.setNextTryAt(null);
    }

    private void apply(WalletEntryInvoice invoice, InvoiceReading reading) {
        try {
            invoice.setReadingJson(json.writeValueAsString(reading));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the reading", e);
        }
        invoice.setVendorName(reading.vendorName());
        invoice.setInvoiceNumber(reading.invoiceNumber());
        invoice.setInvoiceDateText(reading.invoiceDate());
        invoice.setSubtotal(reading.subtotal());
        invoice.setTax(reading.tax());
        invoice.setDelivery(reading.delivery());
        invoice.setTotal(reading.total());
        invoice.setCurrency(reading.currency());
        invoice.setStatus(InvoiceStatus.READ);
        invoice.setErrorText(null);
    }

    private static String ext(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "jpg";
        };
    }

    private record WalletPageRef(int page, String key, String contentType) {
    }
}
