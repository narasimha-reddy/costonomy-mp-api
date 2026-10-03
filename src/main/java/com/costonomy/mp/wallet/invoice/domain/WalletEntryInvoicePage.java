package com.costonomy.mp.wallet.invoice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** One stored page of a bill. The storage key is ours and is never returned to a client. */
@Entity
@Table(name = "wallet_entry_invoice_page")
@Getter
@Setter
@NoArgsConstructor
public class WalletEntryInvoicePage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "page_no", nullable = false, updatable = false)
    private int pageNo;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 255)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String sha256;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
