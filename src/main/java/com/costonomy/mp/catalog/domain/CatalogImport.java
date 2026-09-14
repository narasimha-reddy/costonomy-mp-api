package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One bulk catalog upload. Doc 25, doc 40, §23A.41. */
@Entity
@Table(name = "catalog_import")
@Getter
@Setter
@NoArgsConstructor
public class CatalogImport extends BaseEntity {

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_type", nullable = false, length = 16)
    private String fileType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.PARSING;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows = 0;

    @Column(name = "valid_rows", nullable = false)
    private Integer validRows = 0;

    @Column(name = "invalid_rows", nullable = false)
    private Integer invalidRows = 0;

    @Column(name = "imported_rows", nullable = false)
    private Integer importedRows = 0;

    @Column(name = "created_skus", nullable = false)
    private Integer createdSkus = 0;

    @Column(name = "updated_skus", nullable = false)
    private Integer updatedSkus = 0;

    /** How the file's columns were interpreted, kept so a later summary still explains itself. */
    @Column(name = "column_mapping_json", columnDefinition = "json")
    private String columnMappingJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Import lifecycle.
     *
     * <p>{@code VALIDATED} is the pause the whole design exists for: rows are
     * parsed and checked, nothing is written to the catalog, and the supplier sees
     * exactly what will happen before confirming (§23A.41's Preview step). Doc 25
     * forbids silent partial imports, and a preview the user must accept is what
     * makes the outcome not silent.
     */
    public enum Status {
        PARSING,
        /** Parsed and checked. Nothing written yet. Awaiting confirmation. */
        VALIDATED,
        /** The file could not be read at all — wrong format, no recognisable header. */
        FAILED,
        IMPORTING,
        COMPLETED,
        CANCELLED,
    }
}
