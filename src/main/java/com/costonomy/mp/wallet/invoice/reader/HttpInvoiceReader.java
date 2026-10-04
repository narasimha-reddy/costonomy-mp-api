package com.costonomy.mp.wallet.invoice.reader;

import com.costonomy.mp.wallet.invoice.costapi.CostApiSession;
import com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Reads a bill by calling the existing Costonomy cost-app API (D-113, D-114):
 * {@link CostApiSession.Call#EXTRACT} ({@code POST {base}/item-purchase/invoice/extract?pageName=WalletBill&outlet=..
 * &userId=..}), one multipart part named {@code file} per page, with the access token of our service sign-in
 * ({@link CostApiSession}). The extraction prompt lives in that service; none is kept here.
 *
 * <p>D-115:
 * <ul>
 *   <li>One call per reading, never an immediate second one: after a timeout the cost app may still be reading, so
 *       the retry job decides when to try again. The timeout grows with the pages (60 s, plus 45 s per extra page, at
 *       most 5 minutes; all settings).</li>
 *   <li>403, 408, 429 and 5xx mean the cost app is unavailable ({@link CostApiUnavailableException}): no attempt of
 *       the bill is used. A 401 is handled by the session (refresh, then sign in again). Other 4xx, a timeout, and a
 *       200 that cannot be used are the bill's failure.</li>
 *   <li>The cost outlet is the one the bill's marketplace outlet is mapped to. A bill whose outlet has none is still
 *       read, with the reading account's own outlet, but the cost app's supplier and SKU matches are dropped, so
 *       nothing of another business crosses over.</li>
 * </ul>
 * The answer is an array of invoices; the first without {@code error} is taken (how many there were is kept as
 * {@code invoiceCount}), and only the allowed fields are kept ({@link CostAppInvoice}).
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.reader.provider", havingValue = "HTTP")
@Slf4j
public class HttpInvoiceReader implements InvoiceReader {

    private final CostApiSession session;
    private final String readerOutlet;
    private final String userId;
    private final Duration timeout;
    private final Duration perExtraPage;
    private final Duration maxTimeout;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    public HttpInvoiceReader(
            CostApiSession session,
            @Value("${costonomy.mp.invoices.reader.outlet:0}") String readerOutlet,
            @Value("${costonomy.mp.invoices.reader.user-id:0}") String userId,
            @Value("${costonomy.mp.invoices.reader.timeout:PT60S}") Duration timeout,
            @Value("${costonomy.mp.invoices.reader.timeout-per-extra-page:PT45S}") Duration perExtraPage,
            @Value("${costonomy.mp.invoices.reader.timeout-max:PT5M}") Duration maxTimeout) {
        this.session = session;
        this.readerOutlet = readerOutlet;
        this.userId = userId;
        this.timeout = timeout;
        this.perExtraPage = perExtraPage;
        this.maxTimeout = maxTimeout;
    }

    /** Base timeout and nothing per page or above it: for tests. */
    public HttpInvoiceReader(CostApiSession session, String readerOutlet, String userId, Duration timeout) {
        this(session, readerOutlet, userId, timeout, Duration.ofSeconds(45), Duration.ofMinutes(5));
    }

    /** How long one extraction may take for this many pages. */
    Duration timeoutFor(int pages) {
        Duration t = timeout.plus(perExtraPage.multipliedBy(Math.max(0, pages - 1)));
        return t.compareTo(maxTimeout) > 0 ? maxTimeout : t;
    }

    @Override
    public ReadResult read(List<InvoiceFile> pages, ReadContext ctx) {
        String boundary = "----costonomy" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(pages, boundary);
        boolean mapped = ctx.costOutletId() != null;
        var query = new LinkedHashMap<String, String>();
        query.put("pageName", "WalletBill");
        query.put("outlet", mapped ? Long.toString(ctx.costOutletId()) : readerOutlet);
        query.put("userId", userId);
        Duration limit = timeoutFor(pages.size());
        long started = System.nanoTime();
        try {
            var response = session.call(CostApiSession.Call.EXTRACT, query,
                    new CostApiSession.Body("multipart/form-data; boundary=" + boundary, body), limit);
            int status = response.statusCode();
            log.info("Cost app extraction for invoice {} answered {} in {} ms", ctx.invoiceId(), status,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            if (status == 403 || status == 429) {
                throw new CostApiUnavailableException("The invoice reader refused the request for now (status "
                        + status + ").", false);
            }
            if (status == 408 || status >= 500) {
                throw new CostApiUnavailableException("The invoice reader answered with status " + status + ".",
                        true);
            }
            if (status / 100 != 2) {
                throw new InvoiceReadException("The invoice reader refused the request (status " + status + ").",
                        null);
            }
            return map(response.body(), mapped);
        } catch (java.net.http.HttpTimeoutException e) {
            log.info("Cost app extraction for invoice {} took longer than {} s", ctx.invoiceId(), limit.toSeconds());
            throw new InvoiceReadException("The invoice reader did not answer in time.", e);
        } catch (java.net.ConnectException e) {
            throw new CostApiUnavailableException("The invoice reader could not be reached.", false);
        } catch (IOException e) {
            throw new CostApiUnavailableException("The invoice reader could not be reached.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvoiceReadException("Reading was interrupted.", e);
        }
    }

    ReadResult map(byte[] responseBody) {
        return map(responseBody, true);
    }

    /**
     * The first invoice in the answer without an error, as plain fields; no bill when there is none. Without
     * {@code keepMatches} the cost app's supplier and SKU matches are dropped.
     */
    ReadResult map(byte[] responseBody, boolean keepMatches) {
        JsonNode root;
        try {
            root = json.readTree(responseBody);
        } catch (IOException e) {
            throw new InvoiceReadException("The invoice reader's answer could not be understood.", e);
        }
        var candidates = new ArrayList<CostAppInvoice>();
        try {
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    candidates.add(json.treeToValue(node, CostAppInvoice.class));
                }
            } else if (root != null && root.isObject()) {
                candidates.add(json.treeToValue(root, CostAppInvoice.class));
            }
        } catch (IOException e) {
            throw new InvoiceReadException("The invoice reader's answer could not be understood.", e);
        }
        var found = firstWithoutError(candidates, 0);
        if (found == null) {
            return ReadResult.noBill("The reader found no bill in the pages.");
        }
        InvoiceReading reading = found.toReading(countWithoutError(candidates, 0));
        return ReadResult.of(keepMatches ? reading : reading.withoutMatches());
    }

    /** How many invoices the answer holds, looking through wrappers as {@link #firstWithoutError} does. */
    private static int countWithoutError(List<CostAppInvoice> list, int depth) {
        if (list == null || depth > 2) {
            return 0;
        }
        int n = 0;
        for (CostAppInvoice invoice : list) {
            if (invoice == null || invoice.failed()) {
                continue;
            }
            if (invoice.totalAmount() == null && invoice.items() == null && invoice.invoices() != null) {
                n += countWithoutError(invoice.invoices(), depth + 1);
            } else {
                n++;
            }
        }
        return n;
    }

    private static CostAppInvoice firstWithoutError(List<CostAppInvoice> list, int depth) {
        if (list == null || depth > 2) {
            return null;
        }
        for (CostAppInvoice invoice : list) {
            if (invoice == null || invoice.failed()) {
                continue;
            }
            // A wrapper that only carries nested invoices is looked through.
            if (invoice.totalAmount() == null && invoice.items() == null && invoice.invoices() != null) {
                var nested = firstWithoutError(invoice.invoices(), depth + 1);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            return invoice;
        }
        return null;
    }

    private static byte[] multipart(List<InvoiceFile> pages, String boundary) {
        var out = new ByteArrayOutputStream();
        try {
            for (InvoiceFile page : pages) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + page.filename() + "\"\r\nContent-Type: " + page.contentType() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(page.content());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }


    @Override
    public String toString() {
        return "HttpInvoiceReader[" + session + "]";
    }
}
