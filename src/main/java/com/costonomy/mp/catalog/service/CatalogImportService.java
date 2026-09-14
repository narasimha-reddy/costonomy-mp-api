package com.costonomy.mp.catalog.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.*;
import static com.costonomy.mp.catalog.domain.ImportValues.normalizeAvailability;
import static com.costonomy.mp.catalog.domain.ImportValues.parseDecimal;
import com.costonomy.mp.catalog.repository.*;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Bulk catalog import. Doc 25, doc 40, §23A.41.
 *
 * <p>The spec's eight steps — Upload, Parse, Map, Validate, Preview, Confirm,
 * Import, Summary — are three API calls here. Upload parses, maps, validates and
 * returns the preview; the supplier confirms; the summary is a read. The stepper
 * §23A.41 describes is the client walking that data, not eight round trips.
 *
 * <p><b>Nothing is written to the catalog before confirmation.</b> That pause is
 * the design: doc 25 forbids a silent partial import, and the way to make an
 * outcome not silent is to show it in full and require a person to accept it.
 *
 * <p>Invalid rows are skipped, never guessed at, and every one is reported with
 * its line number, field and reason (doc 40). Valid rows import in a single
 * transaction, so the catalog is never left half-written.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogImportService {

    private final CatalogImportRepository imports;
    private final CatalogImportRowRepository importRows;
    private final CanonicalProductRepository products;
    private final CanonicalProductAliasRepository aliases;
    private final SupplierSkuRepository skus;
    private final SupplierCatalogService supplierCatalog;
    private final CatalogQueryService catalogQuery;
    private final CatalogFileParser parser;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final ObjectMapper json;

    // ── Upload → Parse → Map → Validate → Preview ────────────────────────

    @Transactional
    public CatalogDtos.ImportSummaryResponse upload(Long actorId, Long storeId, MultipartFile file) {
        accessControl.requireScoped(actorId, Permissions.CATALOG_IMPORT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose a file to upload.");
        }

        var parsed = parser.parse(file);

        var record = new CatalogImport();
        record.setSupplierStoreId(storeId);
        record.setFileName(Objects.requireNonNullElse(file.getOriginalFilename(), "upload"));
        record.setFileType(fileType(file.getOriginalFilename()));
        record.setUploadedBy(actorId);
        record.setColumnMappingJson(writeJson(parsed.headerMapping()));
        record.setTotalRows(parsed.rows().size());
        record.setStatus(CatalogImport.Status.PARSING);
        imports.saveAndFlush(record);

        validate(record, parsed.rows(), storeId);

        auditService.record(actorId, null, "CATALOG_IMPORT_UPLOADED", "CATALOG_IMPORT",
                record.getId(), null, record.getStatus().name(),
                "%d rows, %d valid".formatted(record.getTotalRows(), record.getValidRows()), "API");

        return summary(actorId, record.getId());
    }

    private void validate(CatalogImport record, List<Map<String, String>> rows, Long storeId) {
        // Both loaded once. A per-row lookup across a few thousand rows is a few
        // thousand round trips.
        Map<String, Long> productsByName = productLookup();
        Map<String, SupplierSku> existingByCode = supplierCatalog.skusByCode(storeId);

        // Tracks codes seen *within this file*, so two rows claiming the same SKU
        // code are caught. The unique index would catch it at import time, but as
        // a failed row rather than a preview the supplier can act on.
        Set<String> codesInFile = new HashSet<>();

        int valid = 0;
        int invalid = 0;
        List<CatalogImportRow> persisted = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> raw = rows.get(i);
            List<CatalogDtos.RowError> errors = new ArrayList<>();

            String productName = raw.get("productName");
            if (isBlank(productName)) {
                errors.add(error("productName", "REQUIRED", "Product name is required"));
            }

            Long canonicalProductId = resolveProduct(raw, productsByName);
            if (canonicalProductId == null) {
                // Doc 40 names "invalid product mapping" explicitly. We do not
                // create a canonical product to make the row fit — doc 01 §7 keeps
                // those platform-owned, and inventing one per supplier is exactly
                // how comparison stops working.
                errors.add(error("canonicalProduct", "UNMAPPED",
                        "No Mandi product matches this. Add a \"Mandi Product\" column, "
                                + "or ask support to add the product."));
            }

            BigDecimal price = parseDecimal(raw.get("sellingPrice"));
            if (raw.get("sellingPrice") == null) {
                errors.add(error("sellingPrice", "REQUIRED", "Price is required"));
            } else if (price == null) {
                errors.add(error("sellingPrice", "INVALID", "Price isn't a number"));
            } else if (price.signum() < 0) {
                errors.add(error("sellingPrice", "INVALID", "Price can't be negative"));
            }

            BigDecimal gst = parseDecimal(raw.get("gstRate"));
            if (raw.get("gstRate") == null) {
                errors.add(error("gstRate", "REQUIRED", "GST rate is required"));
            } else if (gst == null) {
                errors.add(error("gstRate", "INVALID", "GST rate isn't a number"));
            } else if (gst.signum() < 0 || gst.compareTo(BigDecimal.valueOf(100)) > 0) {
                errors.add(error("gstRate", "INVALID", "GST rate must be between 0 and 100"));
            }

            BigDecimal packSize = parseDecimal(raw.get("packSize"));
            if (raw.get("packSize") == null) {
                errors.add(error("packSize", "REQUIRED", "Pack size is required"));
            } else if (packSize == null || packSize.signum() <= 0) {
                errors.add(error("packSize", "INVALID", "Pack size must be greater than zero"));
            }

            if (isBlank(raw.get("packUnit"))) {
                errors.add(error("packUnit", "REQUIRED", "Pack unit is required (KG, L, PIECE…)"));
            }

            String availability = normalizeAvailability(raw.get("availability"));
            if (availability == null) {
                errors.add(error("availability", "INVALID",
                        "Availability must be AVAILABLE or OUT_OF_STOCK"));
            }

            String skuCode = raw.get("skuCode");
            if (!isBlank(skuCode) && !codesInFile.add(skuCode)) {
                errors.add(error("skuCode", "DUPLICATE_IN_FILE",
                        "This SKU code appears more than once in the file"));
            }

            var row = new CatalogImportRow();
            row.setCatalogImportId(record.getId());
            // 1-based and excluding the header, so it matches what the supplier
            // sees in their spreadsheet.
            row.setRowNumber(i + 1);
            row.setRawJson(writeJson(raw));
            row.setResolvedCanonicalProductId(canonicalProductId);
            if (!isBlank(skuCode) && existingByCode.containsKey(skuCode)) {
                // Not an error: re-uploading an updated price list is the normal
                // way to use this. The row updates rather than inserts.
                row.setResolvedSkuId(existingByCode.get(skuCode).getId());
            }

            if (errors.isEmpty()) {
                row.setStatus(CatalogImportRow.Status.VALID);
                valid++;
            } else {
                row.setStatus(CatalogImportRow.Status.INVALID);
                row.setErrorsJson(writeJson(errors));
                invalid++;
            }
            persisted.add(row);
        }

        importRows.saveAll(persisted);

        record.setValidRows(valid);
        record.setInvalidRows(invalid);
        record.setStatus(CatalogImport.Status.VALIDATED);
        imports.save(record);
    }

    // ── Confirm → Import → Summary ───────────────────────────────────────

    /**
     * Apply the valid rows.
     *
     * <p>One transaction: either every valid row lands or none does, so a failure
     * part-way through cannot leave the catalog half-updated (doc 40). Invalid
     * rows were never going to be written and are reported unchanged.
     */
    @Transactional
    public CatalogDtos.ImportSummaryResponse confirm(Long actorId, Long importId) {
        var record = imports.findById(importId)
                .orElseThrow(() -> new NotFoundException("CatalogImport", importId));

        accessControl.requireScoped(actorId, Permissions.CATALOG_IMPORT,
                ScopeType.SUPPLIER_STORE, record.getSupplierStoreId(), "CatalogImport");

        if (record.getStatus() != CatalogImport.Status.VALIDATED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This import has already been %s."
                            .formatted(record.getStatus().name().toLowerCase()));
        }

        record.setStatus(CatalogImport.Status.IMPORTING);
        imports.saveAndFlush(record);

        var rows = importRows.findByCatalogImportIdAndStatusOrderByRowNumberAsc(
                importId, CatalogImportRow.Status.VALID);

        int created = 0;
        int updated = 0;

        for (CatalogImportRow row : rows) {
            Map<String, String> raw = readRaw(row.getRawJson());

            if (row.getResolvedSkuId() != null) {
                supplierCatalog.updateSku(actorId, row.getResolvedSkuId(),
                        new CatalogDtos.UpdateSkuRequest(
                                row.getResolvedCanonicalProductId(),
                                raw.get("skuCode"),
                                raw.get("productName"),
                                raw.get("brand"),
                                parseDecimal(raw.get("packSize")),
                                raw.get("packUnit"),
                                raw.get("imageUrl"),
                                null,
                                parseDecimal(raw.get("sellingPrice")),
                                parseDecimal(raw.get("gstRate")),
                                normalizeAvailability(raw.get("availability")),
                                parseDecimal(raw.get("availableQuantity"))));
                updated++;
            } else {
                var createdSku = supplierCatalog.createSku(actorId, record.getSupplierStoreId(),
                        new CatalogDtos.CreateSkuRequest(
                                row.getResolvedCanonicalProductId(),
                                raw.get("skuCode"),
                                raw.get("productName"),
                                raw.get("brand"),
                                parseDecimal(raw.get("packSize")),
                                raw.get("packUnit"),
                                raw.get("imageUrl"),
                                parseDecimal(raw.get("sellingPrice")),
                                parseDecimal(raw.get("gstRate")),
                                normalizeAvailability(raw.get("availability")),
                                parseDecimal(raw.get("availableQuantity"))));
                row.setResolvedSkuId(createdSku.id());
                created++;
            }

            row.setStatus(CatalogImportRow.Status.IMPORTED);
        }

        importRows.saveAll(rows);

        record.setImportedRows(rows.size());
        record.setCreatedSkus(created);
        record.setUpdatedSkus(updated);
        record.setConfirmedBy(actorId);
        record.setConfirmedAt(Instant.now());
        record.setCompletedAt(Instant.now());
        record.setStatus(CatalogImport.Status.COMPLETED);
        imports.save(record);

        auditService.record(actorId, null, "CATALOG_IMPORT_COMPLETED", "CATALOG_IMPORT",
                importId, CatalogImport.Status.VALIDATED.name(),
                CatalogImport.Status.COMPLETED.name(),
                "%d created, %d updated, %d skipped"
                        .formatted(created, updated, record.getInvalidRows()), "API");

        return summary(actorId, importId);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ImportSummaryResponse summary(Long actorId, Long importId) {
        var record = imports.findById(importId)
                .orElseThrow(() -> new NotFoundException("CatalogImport", importId));

        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, record.getSupplierStoreId(), "CatalogImport");

        var rows = importRows.findByCatalogImportIdOrderByRowNumberAsc(importId).stream()
                .map(row -> {
                    Map<String, String> raw = readRaw(row.getRawJson());
                    return new CatalogDtos.ImportRowResponse(
                            row.getRowNumber(),
                            row.getStatus().name(),
                            raw.get("skuCode"),
                            raw.get("productName"),
                            parseDecimal(raw.get("sellingPrice")),
                            readErrors(row.getErrorsJson()));
                })
                .toList();

        return new CatalogDtos.ImportSummaryResponse(
                record.getId(), record.getFileName(), record.getStatus().name(),
                record.getTotalRows(), record.getValidRows(), record.getInvalidRows(),
                record.getImportedRows(), record.getCreatedSkus(), record.getUpdatedSkus(),
                record.getErrorMessage(), record.getCompletedAt(), rows);
    }

    @Transactional
    public void cancel(Long actorId, Long importId) {
        var record = imports.findById(importId)
                .orElseThrow(() -> new NotFoundException("CatalogImport", importId));

        accessControl.requireScoped(actorId, Permissions.CATALOG_IMPORT,
                ScopeType.SUPPLIER_STORE, record.getSupplierStoreId(), "CatalogImport");

        if (record.getStatus() == CatalogImport.Status.COMPLETED) {
            // Cancelling a completed import would suggest the SKUs could be
            // rolled back, which they cannot — they are live catalog now.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This import has already been applied.");
        }
        record.setStatus(CatalogImport.Status.CANCELLED);
        imports.save(record);
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ImportSummaryResponse> history(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        return imports.findBySupplierStoreIdOrderByCreatedAtDesc(storeId).stream()
                .map(record -> new CatalogDtos.ImportSummaryResponse(
                        record.getId(), record.getFileName(), record.getStatus().name(),
                        record.getTotalRows(), record.getValidRows(), record.getInvalidRows(),
                        record.getImportedRows(), record.getCreatedSkus(), record.getUpdatedSkus(),
                        record.getErrorMessage(), record.getCompletedAt(), List.of()))
                .toList();
    }

    // ── Product resolution ───────────────────────────────────────────────

    /**
     * Match a row to a canonical product by explicit column, then product name,
     * then alias.
     *
     * <p>Exact matching on the normalised form only. Doc 07 §2 forbids inventing
     * semantic mappings without a configured alias, and a fuzzy match here would
     * be worse than no match: it silently files a supplier's tomato ketchup under
     * fresh tomatoes, and nobody finds out until a restaurant orders 20 kg.
     */
    private Long resolveProduct(Map<String, String> raw, Map<String, Long> lookup) {
        for (String candidate : new String[] {raw.get("canonicalProduct"), raw.get("productName")}) {
            if (isBlank(candidate)) {
                continue;
            }
            Long id = lookup.get(Normalization.normalize(candidate));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** Normalised name and alias → canonical product id, in one pass. */
    private Map<String, Long> productLookup() {
        Map<String, Long> lookup = new HashMap<>();
        products.findAll().forEach(product -> {
            if ("ACTIVE".equals(product.getStatus())) {
                lookup.put(product.getNormalizedName(), product.getId());
            }
        });
        // Aliases do not overwrite a product's own name: "Cheese" is an alias of
        // Processed Cheese, and must not shadow a future product literally named
        // "Cheese".
        aliases.findAll().forEach(alias ->
                lookup.putIfAbsent(alias.getNormalizedAlias(), alias.getCanonicalProductId()));
        return lookup;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static CatalogDtos.RowError error(String field, String code, String message) {
        return new CatalogDtos.RowError(field, code, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String fileType(String fileName) {
        if (fileName == null) {
            return "CSV";
        }
        return fileName.toLowerCase().endsWith(".csv") ? "CSV" : "XLSX";
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Map<String, String> readRaw(String raw) {
        try {
            return json.readValue(raw, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<CatalogDtos.RowError> readErrors(String raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<CatalogDtos.RowError>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
