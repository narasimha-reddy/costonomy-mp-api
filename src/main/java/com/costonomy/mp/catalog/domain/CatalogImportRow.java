package com.costonomy.mp.catalog.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One row of an uploaded file, and what we made of it.
 *
 * <p>Append-only: a row is written once when the file is validated and read back
 * for the preview and the summary. Re-uploading produces a new import, not an
 * edit of this one.
 */
@Entity
@Table(name = "catalog_import_row")
@Getter
@Setter
@NoArgsConstructor
public class CatalogImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalog_import_id", nullable = false)
    private Long catalogImportId;

    /**
     * 1-based line in the source file, excluding the header, so the supplier can
     * find it in their own spreadsheet.
     *
     * <p>The column is {@code line_number} rather than {@code row_number} because
     * ROW_NUMBER is reserved in MySQL 8.
     */
    @Column(name = "line_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "raw_json", nullable = false, columnDefinition = "json")
    private String rawJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    /** Array of {field, code, message}. Row- and field-level, per doc 40. */
    @Column(name = "errors_json", columnDefinition = "json")
    private String errorsJson;

    @Column(name = "resolved_canonical_product_id")
    private Long resolvedCanonicalProductId;

    @Column(name = "resolved_sku_id")
    private Long resolvedSkuId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Status {
        /** Passed validation; will be imported on confirmation. */
        VALID,
        /** Failed validation; will be skipped, and is reported in the summary. */
        INVALID,
        IMPORTED,
        /** Valid at preview but rejected at import — e.g. a SKU code that appeared meanwhile. */
        FAILED,
    }
}
