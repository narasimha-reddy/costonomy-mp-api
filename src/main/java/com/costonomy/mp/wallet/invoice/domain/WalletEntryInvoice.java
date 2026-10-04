package com.costonomy.mp.wallet.invoice.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** The shop's bill for one wallet payment (D-113). */
@Entity
@Table(name = "wallet_entry_invoice")
@Getter
@Setter
@NoArgsConstructor
public class WalletEntryInvoice extends BaseEntity {

    @Column(name = "wallet_transaction_id", nullable = false, updatable = false)
    private Long walletTransactionId;

    @Column(name = "outlet_id", nullable = false, updatable = false)
    private Long outletId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private InvoiceStatus status = InvoiceStatus.READING;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "vendor_name", length = 500)
    private String vendorName;

    @Column(name = "invoice_number", length = 500)
    private String invoiceNumber;

    @Column(name = "invoice_date_text", length = 500)
    private String invoiceDateText;

    @Column(name = "subtotal", precision = 19, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "tax", precision = 19, scale = 4)
    private BigDecimal tax;

    @Column(name = "delivery", precision = 19, scale = 4)
    private BigDecimal delivery;

    @Column(name = "total", precision = 19, scale = 4)
    private BigDecimal total;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "reading_json", columnDefinition = "json")
    private String readingJson;

    @Column(name = "error_text", length = 500)
    private String errorText;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** The user's review (D-114), as the server computed it; null until saved. The reading is never overwritten. */
    @Column(name = "review_json", columnDefinition = "json")
    private String reviewJson;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    /** Calls to the cost app's extraction for this bill (D-115); never more than max-attempts. */
    @Column(name = "extract_calls", nullable = false)
    private int extractCalls;

    /** Tries that found the cost app unavailable (D-115); they use no attempt. */
    @Column(name = "unavailable_count", nullable = false)
    private int unavailableCount;

    /** When the next try is due after the cost app was unavailable; null otherwise (D-115). */
    @Column(name = "next_try_at")
    private Instant nextTryAt;

    /** Earlier reviews' totals, newest last, at most 20 (D-115). */
    @Column(name = "review_history_json", columnDefinition = "json")
    private String reviewHistoryJson;

    /** The last Idempotency-Key a review was saved with, its body hash and the version it produced (D-115). */
    @Column(name = "review_idem_key", length = 128)
    private String reviewIdemKey;

    @Column(name = "review_idem_hash", columnDefinition = "char(64)")
    private String reviewIdemHash;

    @Column(name = "review_idem_version")
    private Long reviewIdemVersion;
}
