package com.costonomy.mp.storage.invoice;

import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoicePage;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoicePageRepository;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a bill page from local storage, only to a holder of a signed, unexpired token (D-113). The token
 * names a page of an outlet; the page is looked up by id and must belong to that outlet. A bad signature,
 * an expired token, a page of another outlet and a missing file are all the same plain 404. Not registered
 * under S3, where pages are presigned links to the bucket.
 */
@RestController
@RequestMapping("/invoice-files")
@ConditionalOnProperty(name = "costonomy.mp.invoices.storage.provider", havingValue = "LOCAL", matchIfMissing = true)
@RequiredArgsConstructor
public class InvoiceFileController {

    private final InvoiceLinkSigner signer;
    private final InvoiceStorage storage;
    private final WalletEntryInvoicePageRepository pages;
    private final WalletEntryInvoiceRepository invoices;

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> page(@PathVariable String token) {
        var claims = signer.verify(token);
        if (claims.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var c = claims.get();
        var page = pages.findById(c.pageId()).orElse(null);
        if (page == null || !belongsTo(page, c.outletId())) {
            return ResponseEntity.notFound().build();
        }
        return storage.read(page.getStorageKey())
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(page.getContentType()))
                        .cacheControl(CacheControl.noStore().cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Disposition", "inline")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean belongsTo(WalletEntryInvoicePage page, long outletId) {
        return invoices.findById(page.getInvoiceId()).map(i -> i.getOutletId() == outletId).orElse(false);
    }
}
