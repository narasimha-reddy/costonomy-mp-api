package com.costonomy.mp.catalog.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.repository.*;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A supplier store's own catalog.
 *
 * <p>The rule this class exists to enforce: <b>a price is never edited, only
 * superseded.</b> Changing price, GST or availability closes the current offer
 * and opens a new one. Doc 02 §4 requires historical commercial values to survive
 * when a transaction references them, and doc 01 §26 wants the price history for
 * pricing intelligence — an in-place update destroys both, and does so silently.
 *
 * <p>Every operation is scoped to the store (doc 03 §16). A supplier editing
 * another supplier's prices would be the most damaging scope failure in the
 * system, so the checks here are not optional and the tests assert them directly.
 */
@Service
@RequiredArgsConstructor
public class SupplierCatalogService {

    private final SupplierSkuRepository skus;
    private final SupplierOfferRepository offers;
    private final CanonicalProductRepository products;
    private final CatalogQueryService catalogQuery;
    private final CatalogDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CatalogDtos.SkuResponse> listSkus(Long actorId, Long storeId, String status,
                                                  int page, int size) {
        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var pageable = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        var found = status == null
                ? skus.findBySupplierStoreId(storeId, pageable)
                : skus.findBySupplierStoreIdAndStatus(storeId, status, pageable);

        return found.getContent().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CatalogDtos.SkuResponse createSku(
            Long actorId, Long storeId, CatalogDtos.CreateSkuRequest request) {

        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        // Suppliers map onto canonical products; they do not invent them
        // (doc 01 §7). If they could, two suppliers would each create their own
        // "Paneer 1kg" and their offers would never appear side by side.
        if (!products.existsById(request.canonicalProductId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That product isn't in the Mandi catalog. Search for it, or ask support to add it.");
        }

        var sku = new SupplierSku();
        sku.setSupplierStoreId(storeId);
        sku.setCanonicalProductId(request.canonicalProductId());
        sku.setSkuCode(blankToNull(request.skuCode()));
        sku.setName(request.name());
        sku.setBrandId(catalogQuery.resolveBrandId(request.brandName()));
        sku.setPackSize(request.packSize());
        sku.setPackUnit(request.packUnit());
        sku.setImageUrl(blankToNull(request.imageUrl()));

        try {
            skus.saveAndFlush(sku);
        } catch (DataIntegrityViolationException ex) {
            // uk_sku_store_code. Doc 40 lists duplicate SKU as a named validation
            // failure; this is the backstop behind that check.
            throw new BusinessException(ErrorCode.DUPLICATE_SKU_CODE);
        }

        openOffer(sku, request.sellingPrice(), request.gstRate(),
                request.availability() == null ? SupplierOffer.Availability.AVAILABLE : request.availability(),
                request.availableQuantity(), actorId);

        auditService.record(actorId, null, "SKU_CREATED", "SUPPLIER_SKU",
                sku.getId(), null, "ACTIVE", null, "API");

        return toResponse(sku);
    }

    @Transactional
    public CatalogDtos.SkuResponse updateSku(
            Long actorId, Long skuId, CatalogDtos.UpdateSkuRequest request) {

        var sku = skus.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SupplierSku", skuId));

        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT,
                ScopeType.SUPPLIER_STORE, sku.getSupplierStoreId(), "SupplierSku");

        if (request.canonicalProductId() != null
                && !request.canonicalProductId().equals(sku.getCanonicalProductId())) {
            if (!products.existsById(request.canonicalProductId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That product isn't in the Mandi catalog.");
            }
            sku.setCanonicalProductId(request.canonicalProductId());
        }
        if (request.skuCode() != null) sku.setSkuCode(blankToNull(request.skuCode()));
        if (request.name() != null) sku.setName(request.name());
        if (request.brandName() != null) sku.setBrandId(catalogQuery.resolveBrandId(request.brandName()));
        if (request.packSize() != null) sku.setPackSize(request.packSize());
        if (request.packUnit() != null) sku.setPackUnit(request.packUnit());
        // blankToNull, as for skuCode: an empty string is how a supplier takes
        // their own photo back down, and it has to store as absent. Stored as ""
        // the field is present-but-empty, and every client falling back with
        // `sku.imageUrl ?? canonical` would render nothing at all.
        if (request.imageUrl() != null) sku.setImageUrl(blankToNull(request.imageUrl()));
        if (request.status() != null) sku.setStatus(request.status());

        try {
            skus.saveAndFlush(sku);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_SKU_CODE);
        }

        // A commercial change supersedes; an identity-only change leaves the
        // current offer alone, so renaming a SKU does not create a spurious price
        // history entry.
        if (request.sellingPrice() != null || request.gstRate() != null
                || request.availability() != null || request.availableQuantity() != null) {
            supersedeOffer(sku, request, actorId);
        }

        auditService.record(actorId, null, "SKU_UPDATED", "SUPPLIER_SKU",
                sku.getId(), null, sku.getStatus(), null, "API");

        return toResponse(sku);
    }

    /** Price and availability history for a SKU, newest first. */
    @Transactional(readOnly = true)
    public List<CatalogDtos.PriceHistoryEntry> priceHistory(Long actorId, Long skuId) {
        var sku = skus.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SupplierSku", skuId));

        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, sku.getSupplierStoreId(), "SupplierSku");

        return offers.findBySupplierSkuIdOrderByEffectiveFromDesc(skuId).stream()
                .map(o -> new CatalogDtos.PriceHistoryEntry(
                        o.getSellingPrice(), o.getGstRate(), o.getAvailability(),
                        o.getEffectiveFrom(), o.getEffectiveTo()))
                .toList();
    }

    // ── Offer lifecycle ──────────────────────────────────────────────────

    /**
     * Close the current offer and open a replacement.
     *
     * <p>Called from update and from bulk import, so there is exactly one code
     * path that changes a price. Fields the caller left null are carried forward
     * from the current offer, so changing only availability does not silently
     * reset the price to nothing.
     */
    SupplierOffer supersedeOffer(SupplierSku sku, CatalogDtos.UpdateSkuRequest request, Long actorId) {
        var current = offers.findBySupplierSkuIdAndStatus(sku.getId(), "ACTIVE");

        BigDecimal price = request.sellingPrice() != null ? request.sellingPrice()
                : current.map(SupplierOffer::getSellingPrice).orElse(null);
        BigDecimal gst = request.gstRate() != null ? request.gstRate()
                : current.map(SupplierOffer::getGstRate).orElse(null);
        String availability = request.availability() != null ? request.availability()
                : current.map(SupplierOffer::getAvailability)
                        .orElse(SupplierOffer.Availability.AVAILABLE);
        BigDecimal quantity = request.availableQuantity() != null ? request.availableQuantity()
                : current.map(SupplierOffer::getAvailableQuantity).orElse(null);

        if (price == null || gst == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This SKU has no price yet — provide a price and GST rate.");
        }

        // Nothing actually changed: don't manufacture a history entry.
        if (current.isPresent() && unchanged(current.get(), price, gst, availability, quantity)) {
            return current.get();
        }

        Instant now = Instant.now();
        current.ifPresent(offer -> {
            offer.setStatus("SUPERSEDED");
            offer.setEffectiveTo(now);
            offers.save(offer);
        });

        return openOffer(sku, price, gst, availability, quantity, actorId);
    }

    private static boolean unchanged(SupplierOffer offer, BigDecimal price, BigDecimal gst,
                                     String availability, BigDecimal quantity) {
        // compareTo, not equals: BigDecimal("410.00").equals(new BigDecimal("410.0000"))
        // is false, and a supplier re-uploading the same file must not generate a
        // price-change event for every row.
        return offer.getSellingPrice().compareTo(price) == 0
                && offer.getGstRate().compareTo(gst) == 0
                && offer.getAvailability().equals(availability)
                && java.util.Objects.compare(offer.getAvailableQuantity(), quantity,
                        java.util.Comparator.nullsFirst(BigDecimal::compareTo)) == 0;
    }

    private SupplierOffer openOffer(SupplierSku sku, BigDecimal price, BigDecimal gst,
                                    String availability, BigDecimal quantity, Long actorId) {

        if (!SupplierOffer.Availability.isValid(availability)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Availability must be AVAILABLE or OUT_OF_STOCK.");
        }

        var offer = new SupplierOffer();
        offer.setSupplierSkuId(sku.getId());
        offer.setSupplierStoreId(sku.getSupplierStoreId());
        offer.setCanonicalProductId(sku.getCanonicalProductId());
        offer.setSellingPrice(price);
        offer.setGstRate(gst);
        offer.setAvailability(availability);
        offer.setAvailableQuantity(quantity);
        offer.setEffectiveFrom(Instant.now());
        offer.setStatus("ACTIVE");
        offer.setCreatedBy(actorId);
        return offers.save(offer);
    }

    // ── internals ────────────────────────────────────────────────────────

    CatalogDtos.SkuResponse toResponse(SupplierSku sku) {
        Optional<SupplierOffer> offer = offers.findBySupplierSkuIdAndStatus(sku.getId(), "ACTIVE");

        var product = products.findById(sku.getCanonicalProductId()).orElse(null);
        String productName = product == null ? null : product.getName();
        Long categoryId = product == null ? null : product.getCategoryId();
        String productImage = product == null ? null : product.getImageUrl();
        String brandName = sku.getBrandId() == null ? null
                : directory.brandNames(List.of(sku.getBrandId())).get(sku.getBrandId());

        return new CatalogDtos.SkuResponse(
                sku.getId(), sku.getSupplierStoreId(), sku.getCanonicalProductId(), productName,
                categoryId, sku.getSkuCode(), sku.getName(), brandName,
                sku.getPackSize(), sku.getPackUnit(), sku.getImageUrl(), productImage,
                sku.getStatus(),
                offer.map(SupplierOffer::getSellingPrice).orElse(null),
                offer.map(SupplierOffer::getGstRate).orElse(null),
                offer.map(SupplierOffer::getAvailability).orElse(null),
                offer.map(SupplierOffer::getAvailableQuantity).orElse(null),
                offer.map(SupplierOffer::getEffectiveFrom).orElse(null));
    }

    /**
     * A store's SKUs keyed by their code, for the import's duplicate detection.
     *
     * <p>Loaded in one read rather than a lookup per row: a catalog import is
     * typically a few thousand rows, and per-row queries would turn that into a
     * few thousand round trips.
     */
    Map<String, SupplierSku> skusByCode(Long storeId) {
        var all = skus.findBySupplierStoreId(storeId, PageRequest.of(0, 10_000)).getContent();
        return all.stream()
                .filter(sku -> sku.getSkuCode() != null)
                .collect(java.util.stream.Collectors.toMap(
                        SupplierSku::getSkuCode, sku -> sku, (a, b) -> a));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
