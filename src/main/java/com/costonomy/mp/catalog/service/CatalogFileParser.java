package com.costonomy.mp.catalog.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a CSV or XLSX upload into rows of {@code header → value}. Doc 25, doc 40.
 *
 * <p>Parsing only. It has no opinion about whether a value is a valid price or a
 * known product — that is {@link CatalogImportService}'s validation step, and
 * keeping the two apart is what lets a malformed cell become a row-level error
 * rather than a failed upload.
 *
 * <p>Headers are normalised so a supplier's spreadsheet does not have to match a
 * template exactly: {@code "Selling Price"}, {@code "selling_price"} and
 * {@code "SELLING PRICE "} all resolve to the same field.
 */
@Component
public class CatalogFileParser {

    /** Guards against a spreadsheet nobody meant to upload. */
    private static final int MAX_ROWS = 20_000;

    /** Recognised headers per field. First match wins. */
    private static final Map<String, List<String>> FIELD_ALIASES = new LinkedHashMap<>();

    static {
        FIELD_ALIASES.put("skuCode", List.of("sku code", "sku", "code", "item code", "product code"));
        FIELD_ALIASES.put("productName", List.of("product name", "name", "item name", "description"));
        FIELD_ALIASES.put("canonicalProduct",
                List.of("mandi product", "canonical product", "catalog product", "maps to"));
        FIELD_ALIASES.put("brand", List.of("brand", "brand name", "make"));
        FIELD_ALIASES.put("packSize", List.of("pack size", "pack", "size", "quantity per pack"));
        FIELD_ALIASES.put("packUnit", List.of("pack unit", "unit", "uom"));
        FIELD_ALIASES.put("sellingPrice",
                List.of("selling price", "price", "rate", "unit price", "mrp"));
        FIELD_ALIASES.put("gstRate", List.of("gst rate", "gst", "gst %", "tax", "tax rate"));
        FIELD_ALIASES.put("availability", List.of("availability", "available", "in stock", "status"));
        FIELD_ALIASES.put("availableQuantity", List.of("available quantity", "stock", "qty", "on hand"));
        FIELD_ALIASES.put("imageUrl", List.of("image url", "image", "photo"));
    }

    /**
     * @param headerMapping field name → the source column it was read from, kept
     *                      so a summary read weeks later still explains how the
     *                      file was interpreted
     */
    public record ParsedFile(List<Map<String, String>> rows, Map<String, String> headerMapping) {
    }

    public ParsedFile parse(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return parseSpreadsheet(in);
            }
            if (name.endsWith(".csv")) {
                return parseCsv(in);
            }
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Upload a .csv or .xlsx file.");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // The underlying message can quote file contents; it is not returned.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We couldn't read that file. Check it opens correctly and try again.");
        }
    }

    private ParsedFile parseCsv(InputStream in) throws Exception {
        var format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                // A supplier's export often has a trailing blank line; that is not
                // a malformed row worth reporting.
                .setIgnoreEmptyLines(true)
                .build();

        try (var parser = CSVParser.parse(
                new InputStreamReader(in, StandardCharsets.UTF_8), format)) {

            var mapping = resolveHeaders(parser.getHeaderNames());
            List<Map<String, String>> rows = new ArrayList<>();

            for (var record : parser) {
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "That file has more than " + MAX_ROWS + " rows. Split it and try again.");
                }
                Map<String, String> row = new LinkedHashMap<>();
                mapping.forEach((field, header) -> row.put(field, trimToNull(record.get(header))));
                if (row.values().stream().anyMatch(java.util.Objects::nonNull)) {
                    rows.add(row);
                }
            }
            return new ParsedFile(rows, mapping);
        }
    }

    private ParsedFile parseSpreadsheet(InputStream in) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That file has no header row.");
            }

            List<String> headers = new ArrayList<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                headers.add(cellText(headerRow.getCell(i)));
            }
            var mapping = resolveHeaders(headers);

            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row sheetRow = sheet.getRow(r);
                if (sheetRow == null) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "That file has more than " + MAX_ROWS + " rows. Split it and try again.");
                }

                Map<String, String> row = new LinkedHashMap<>();
                mapping.forEach((field, header) -> {
                    int index = headers.indexOf(header);
                    row.put(field, index < 0 ? null : trimToNull(cellText(sheetRow.getCell(index))));
                });
                if (row.values().stream().anyMatch(java.util.Objects::nonNull)) {
                    rows.add(row);
                }
            }
            return new ParsedFile(rows, mapping);
        }
    }

    /** Match the file's headers to our fields, tolerating case, spacing and punctuation. */
    private Map<String, String> resolveHeaders(List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();

        for (var entry : FIELD_ALIASES.entrySet()) {
            for (String header : headers) {
                if (header == null) {
                    continue;
                }
                String normalized = header.toLowerCase().replaceAll("[^a-z0-9%]+", " ").trim();
                if (entry.getValue().contains(normalized)) {
                    mapping.put(entry.getKey(), header);
                    break;
                }
            }
        }

        // Without a product name there is nothing to import and no way to give
        // useful row-level errors, so this is a file-level failure rather than
        // thousands of identical row errors.
        if (!mapping.containsKey("productName")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We couldn't find a product name column. Expected a header like "
                            + "\"Product Name\".");
        }
        return mapping;
    }

    /**
     * A cell as text.
     *
     * <p>Numeric cells are read through BigDecimal rather than
     * {@code String.valueOf(double)}, which would turn a price of 1450 into
     * "1450.0" and a GST rate of 5 into "5.0". Harmless to parse, but it reaches
     * the preview the supplier reads, and a catalog tool that cannot display
     * their own prices correctly does not inspire confidence.
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (IllegalStateException ex) {
                    yield BigDecimal.valueOf(cell.getNumericCellValue())
                            .stripTrailingZeros().toPlainString();
                }
            }
            default -> null;
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
