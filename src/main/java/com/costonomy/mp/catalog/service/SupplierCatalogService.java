package com.costonomy.mp.catalog.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.domain.SupplierSkuImage;
import com.costonomy.mp.catalog.domain.Unit;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.costonomy.mp.procurement.domain.Pricing;

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
    private final SupplierSkuImageRepository skuImages;
    private final CanonicalProductRepository products;
    private final ProductCategoryRepository productCategories;
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
        sku.setGrade(blankToNull(request.grade()));
        sku.setCatchWeight(Boolean.TRUE.equals(request.isCatchWeight()));
        sku.setRequiresColdChain(Boolean.TRUE.equals(request.requiresColdChain()));
        sku.setPackSize(request.packSize());

        var pack = Unit.parse(request.packUnit(), "Pack unit");
        sku.setPackUnit(pack.name());
        applyMeasure(sku, pack, request.measureValue(), request.measureUnit(), true);
        sku.setMrp(zeroToNull(request.mrp()));

        sku.setImageUrl(blankToNull(request.imageUrl()));
        applyDetail(sku, request.description(), request.lengthCm(), request.widthCm(),
                request.heightCm(), request.weightGrams(), request.youtubeUrl());

        try {
            skus.saveAndFlush(sku);
        } catch (DataIntegrityViolationException ex) {
            // uk_sku_store_code. Doc 40 lists duplicate SKU as a named validation
            // failure; this is the backstop behind that check.
            throw new BusinessException(ErrorCode.DUPLICATE_SKU_CODE);
        }

        openOffer(sku, request.sellingPrice(), zeroToNull(request.mrp()), request.gstRate(),
                request.availability() == null ? SupplierOffer.Availability.AVAILABLE : request.availability(),
                request.availableQuantity(), actorId);

        applyImages(sku.getId(), request.images());

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
        if (request.grade() != null) sku.setGrade(blankToNull(request.grade()));
        if (request.isCatchWeight() != null) sku.setCatchWeight(request.isCatchWeight());
        if (request.requiresColdChain() != null) sku.setRequiresColdChain(request.requiresColdChain());
        if (request.packSize() != null) sku.setPackSize(request.packSize());
        // The pack unit and its measure are validated together even when only one
        // of them was sent: changing KG to PKT without saying what is in the
        // packet leaves a SKU that states a bundle and never an amount.
        if (request.packUnit() != null || request.measureValue() != null
                || request.measureUnit() != null) {
            var pack = Unit.parse(
                    request.packUnit() != null ? request.packUnit() : sku.getPackUnit(),
                    "Pack unit");
            sku.setPackUnit(pack.name());

            boolean supplied = request.measureValue() != null || request.measureUnit() != null;
            applyMeasure(sku, pack,
                    request.measureValue() != null ? request.measureValue() : sku.getMeasureValue(),
                    request.measureUnit() != null ? request.measureUnit() : sku.getMeasureUnit(),
                    supplied);
        }
        if (request.mrp() != null) sku.setMrp(zeroToNull(request.mrp()));
        // blankToNull, as for skuCode: an empty string is how a supplier takes
        // their own photo back down, and it has to store as absent. Stored as ""
        // the field is present-but-empty, and every client falling back with
        // `sku.imageUrl ?? canonical` would render nothing at all.
        if (request.imageUrl() != null) sku.setImageUrl(blankToNull(request.imageUrl()));
        applyDetail(sku, request.description(), request.lengthCm(), request.widthCm(),
                request.heightCm(), request.weightGrams(), request.youtubeUrl());
        if (request.status() != null) sku.setStatus(request.status());

        try {
            skus.saveAndFlush(sku);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_SKU_CODE);
        }

        // A commercial change supersedes; an identity-only change leaves the
        // current offer alone, so renaming a SKU does not create a spurious price
        // history entry.
        applyImages(sku.getId(), request.images());

        if (request.sellingPrice() != null || request.mrp() != null || request.gstRate() != null
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
        BigDecimal mrp = request.mrp() != null ? zeroToNull(request.mrp())
                : current.map(SupplierOffer::getMrp).orElse(sku.getMrp());
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
        if (current.isPresent() && unchanged(current.get(), price, mrp, gst, availability, quantity)) {
            return current.get();
        }

        Instant now = Instant.now();
        current.ifPresent(offer -> {
            offer.setStatus("SUPERSEDED");
            offer.setEffectiveTo(now);
            offers.save(offer);
        });

        return openOffer(sku, price, mrp, gst, availability, quantity, actorId);
    }

    private static boolean unchanged(SupplierOffer offer, BigDecimal price, BigDecimal mrp,
                                     BigDecimal gst, String availability, BigDecimal quantity) {
        // compareTo, not equals: BigDecimal("410.00").equals(new BigDecimal("410.0000"))
        // is false, and a supplier re-uploading the same file must not generate a
        // price-change event for every row.
        return offer.getSellingPrice().compareTo(price) == 0
                && java.util.Objects.compare(offer.getMrp(), mrp,
                        java.util.Comparator.nullsFirst(BigDecimal::compareTo)) == 0
                && offer.getGstRate().compareTo(gst) == 0
                && offer.getAvailability().equals(availability)
                && java.util.Objects.compare(offer.getAvailableQuantity(), quantity,
                        java.util.Comparator.nullsFirst(BigDecimal::compareTo)) == 0;
    }

    private SupplierOffer openOffer(SupplierSku sku, BigDecimal price, BigDecimal mrp,
                                    BigDecimal gst, String availability, BigDecimal quantity,
                                    Long actorId) {

        if (!SupplierOffer.Availability.isValid(availability)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Availability must be AVAILABLE or OUT_OF_STOCK.");
        }

        var offer = new SupplierOffer();
        offer.setSupplierSkuId(sku.getId());
        offer.setSupplierStoreId(sku.getSupplierStoreId());
        offer.setCanonicalProductId(sku.getCanonicalProductId());
        offer.setSellingPrice(price);
        offer.setMrp(mrp);
        offer.setGstRate(gst);
        offer.setAvailability(availability);
        offer.setAvailableQuantity(quantity);
        offer.setEffectiveFrom(Instant.now());
        offer.setStatus("ACTIVE");
        offer.setCreatedBy(actorId);
        return offers.save(offer);
    }

    /**
     * Item-Centric Variant Management: Get all brand and grade variants for an item
     * with top-selling combination presets first.
     */
    @Transactional(readOnly = true)
    public CatalogDtos.ItemVariantGroupResponse getItemVariants(
            Long actorId, Long storeId, Long canonicalProductId) {

        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var product = products.findById(canonicalProductId)
                .orElseThrow(() -> new NotFoundException("CanonicalProduct", canonicalProductId));

        var variants = skus.findBySupplierStoreIdAndCanonicalProductId(storeId, canonicalProductId).stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(
                        (CatalogDtos.SkuResponse s) -> s.sellingPrice() != null ? s.sellingPrice() : BigDecimal.valueOf(Long.MAX_VALUE)))
                .toList();

        var presets = generatePresetsForProduct(product);
        String categoryName = null;
        if (product.getCategoryId() != null) {
            categoryName = productCategories.findById(product.getCategoryId())
                    .map(com.costonomy.mp.catalog.domain.ProductCategory::getName).orElse(null);
        }

        return new CatalogDtos.ItemVariantGroupResponse(
                product.getId(),
                product.getName(),
                product.getCategoryId(),
                categoryName,
                product.getImageUrl(),
                product.getBaseUnit(),
                variants,
                presets);
    }

    /**
     * Batch save / update variants under an item in one unified screen action.
     */
    @Transactional
    public CatalogDtos.ItemVariantGroupResponse batchUpdateVariants(
            Long actorId, Long storeId, CatalogDtos.BatchUpdateVariantsRequest request) {

        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var product = products.findById(request.canonicalProductId())
                .orElseThrow(() -> new NotFoundException("CanonicalProduct", request.canonicalProductId()));

        for (var entry : request.variants()) {
            if (entry.skuId() != null) {
                // Update existing variant
                updateSku(actorId, entry.skuId(), new CatalogDtos.UpdateSkuRequest(
                        request.canonicalProductId(),
                        entry.skuCode(),
                        entry.name(),
                        entry.brandName(),
                        entry.grade(),
                        entry.packSize(),
                        entry.packUnit(),
                        null,
                        null,
                        entry.mrp(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        entry.sellingPrice(),
                        entry.gstRate() != null ? entry.gstRate() : BigDecimal.valueOf(5),
                        entry.availability() != null ? entry.availability() : SupplierOffer.Availability.AVAILABLE,
                        entry.availableQuantity()
                ));
            } else {
                // Create new variant
                String skuName = entry.name();
                if (skuName == null || skuName.isBlank()) {
                    String brandPart = (entry.brandName() != null && !entry.brandName().isBlank())
                            ? entry.brandName() + " " : "";
                    String gradePart = (entry.grade() != null && !entry.grade().isBlank())
                            ? " (" + entry.grade() + ")" : "";
                    skuName = (brandPart + product.getName() + gradePart).trim();
                }

                BigDecimal packSize = entry.packSize() != null ? entry.packSize()
                        : (product.getBasePackSize() != null ? product.getBasePackSize() : BigDecimal.ONE);
                String packUnit = entry.packUnit() != null ? entry.packUnit()
                        : (product.getBaseUnit() != null ? product.getBaseUnit() : "KG");

                createSku(actorId, storeId, new CatalogDtos.CreateSkuRequest(
                        request.canonicalProductId(),
                        entry.skuCode(),
                        skuName,
                        entry.brandName(),
                        entry.grade(),
                        packSize,
                        packUnit,
                        null,
                        null,
                        entry.mrp(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        entry.sellingPrice(),
                        entry.gstRate() != null ? entry.gstRate() : BigDecimal.valueOf(5),
                        entry.availability() != null ? entry.availability() : SupplierOffer.Availability.AVAILABLE,
                        entry.availableQuantity()
                ));
            }
        }

        return getItemVariants(actorId, storeId, request.canonicalProductId());
    }

    private List<CatalogDtos.VariantPreset> generatePresetsForProduct(com.costonomy.mp.catalog.domain.CanonicalProduct product) {
        String name = product.getName().toLowerCase();
        List<CatalogDtos.VariantPreset> presets = new ArrayList<>();

        if (name.contains("paneer")) {
            presets.add(new CatalogDtos.VariantPreset("Amul", "Grade A", BigDecimal.ONE, "KG", new BigDecimal("450.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Nandini", "Grade A", BigDecimal.ONE, "KG", new BigDecimal("420.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Mother Dairy", "Grade A", BigDecimal.ONE, "KG", new BigDecimal("440.00"), false));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A", BigDecimal.ONE, "KG", new BigDecimal("380.00"), true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B", BigDecimal.ONE, "KG", new BigDecimal("340.00"), false));
        } else if (name.contains("rice") || name.contains("biryani")) {
            BigDecimal size = product.getBasePackSize() != null ? product.getBasePackSize() : new BigDecimal("25.00");
            presets.add(new CatalogDtos.VariantPreset("India Gate", "Grade A (Classic)", size, "KG", new BigDecimal("3200.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Daawat", "Grade A (Biryani)", size, "KG", new BigDecimal("3100.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Fortune", "Grade A (Special)", size, "KG", new BigDecimal("2800.00"), false));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A", size, "KG", new BigDecimal("2600.00"), true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B", size, "KG", new BigDecimal("2300.00"), false));
        } else if (name.contains("cleaner") || name.contains("floor") || name.contains("phenyl")) {
            presets.add(new CatalogDtos.VariantPreset("Lizol", "Standard", new BigDecimal("5.00"), "LTR", new BigDecimal("850.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Colin", "Standard", new BigDecimal("5.00"), "LTR", new BigDecimal("790.00"), false));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A (Concentrate)", new BigDecimal("5.00"), "LTR", new BigDecimal("550.00"), true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B (Standard)", new BigDecimal("5.00"), "LTR", new BigDecimal("420.00"), true));
        } else if (name.contains("spice") || name.contains("elachi") || name.contains("cardamom") || name.contains("pepper") || name.contains("jeera")) {
            presets.add(new CatalogDtos.VariantPreset("Everest", "Grade A", new BigDecimal("500.00"), "GM", new BigDecimal("1600.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Catch", "Grade A", new BigDecimal("500.00"), "GM", new BigDecimal("1650.00"), false));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A (Bold)", BigDecimal.ONE, "KG", new BigDecimal("2800.00"), true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B (Medium)", BigDecimal.ONE, "KG", new BigDecimal("2400.00"), true));
        } else if (name.contains("milk") || name.contains("dairy")) {
            presets.add(new CatalogDtos.VariantPreset("Amul", "Grade A (Taaza)", BigDecimal.ONE, "LTR", new BigDecimal("56.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Nandini", "Grade A (Special)", BigDecimal.ONE, "LTR", new BigDecimal("52.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Mother Dairy", "Grade A (Toned)", BigDecimal.ONE, "LTR", new BigDecimal("54.00"), false));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A (Bulk Cow Milk)", new BigDecimal("10.00"), "LTR", new BigDecimal("480.00"), true));
        } else if (name.contains("meat") || name.contains("chicken") || name.contains("mutton")) {
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A (Fresh Tender)", BigDecimal.ONE, "KG", new BigDecimal("260.00"), true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B (Standard)", BigDecimal.ONE, "KG", new BigDecimal("220.00"), true));
            presets.add(new CatalogDtos.VariantPreset("Zorabian", "Grade A (Curry Cut)", BigDecimal.ONE, "KG", new BigDecimal("320.00"), false));
            presets.add(new CatalogDtos.VariantPreset("Godrej Real Good", "Grade A", BigDecimal.ONE, "KG", new BigDecimal("310.00"), false));
        } else {
            BigDecimal size = product.getBasePackSize() != null ? product.getBasePackSize() : BigDecimal.ONE;
            String unit = product.getBaseUnit() != null ? product.getBaseUnit() : "KG";
            presets.add(new CatalogDtos.VariantPreset(null, "Grade A", size, unit, null, true));
            presets.add(new CatalogDtos.VariantPreset(null, "Grade B", size, unit, null, true));
            presets.add(new CatalogDtos.VariantPreset("Popular Brand", "Grade A", size, unit, null, false));
        }
        return presets;
    }

    /**
     * The optional detail fields. D-096.
     *
     * <p>Null means "not sent" and is left alone; blank means "take it down"
     * and stores as absent — the same rule `imageUrl` follows, and for the same
     * reason: a field stored as an empty string is present-but-empty, and every
     * client checking `?? fallback` renders nothing.
     */
    private void applyDetail(SupplierSku sku, String description,
                             BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                             BigDecimal weightGrams, String youtubeUrl) {
        if (description != null) sku.setDescription(blankToNull(description));
        if (lengthCm != null) sku.setLengthCm(zeroToNull(lengthCm));
        if (widthCm != null) sku.setWidthCm(zeroToNull(widthCm));
        if (heightCm != null) sku.setHeightCm(zeroToNull(heightCm));
        if (weightGrams != null) sku.setWeightGrams(zeroToNull(weightGrams));
        if (youtubeUrl != null) sku.setYoutubeUrl(blankToNull(youtubeUrl));
    }

    /**
     * Replace the gallery with what was sent.
     *
     * <p>Whole rather than incremental: reordering four pictures is one
     * decision, and four calls for it leave the gallery half-applied when one
     * fails. Null means the caller did not mention images and the gallery
     * stands; an empty list means they removed them all.
     */
    private void applyImages(Long skuId, List<String> images) {
        if (images == null) {
            return;
        }
        skuImages.deleteBySupplierSkuId(skuId);
        int position = 0;
        for (String url : images) {
            if (url == null || url.isBlank()) {
                continue;
            }
            var image = new SupplierSkuImage();
            image.setSupplierSkuId(skuId);
            image.setUrl(url.trim());
            image.setPosition(position++);
            skuImages.save(image);
        }
    }

    private static BigDecimal zeroToNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
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

        BigDecimal mrp = offer.map(SupplierOffer::getMrp).filter(java.util.Objects::nonNull).orElse(sku.getMrp());
        BigDecimal sellingPrice = offer.map(SupplierOffer::getSellingPrice).orElse(null);
        BigDecimal discountAmount = Pricing.discountAmount(mrp, sellingPrice);
        Integer discountPercent = Pricing.discountPercent(mrp, sellingPrice);

        return new CatalogDtos.SkuResponse(
                sku.getId(), sku.getSupplierStoreId(), sku.getCanonicalProductId(), productName,
                categoryId, sku.getSkuCode(), sku.getName(), brandName, sku.getGrade(),
                sku.isCatchWeight(),
                sku.isRequiresColdChain(),
                sku.getPackSize(), sku.getPackUnit(),
                sku.getMeasureValue(), sku.getMeasureUnit(),
                sku.getImageUrl(), productImage,
                sku.getDescription(),
                sku.getLengthCm(), sku.getWidthCm(), sku.getHeightCm(), sku.getWeightGrams(),
                sku.getYoutubeUrl(),
                skuImages.findBySupplierSkuIdOrderByPositionAscIdAsc(sku.getId()).stream()
                        .map(SupplierSkuImage::getUrl).toList(),
                sku.getStatus(),
                mrp,
                sellingPrice,
                discountAmount,
                discountPercent,
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

    /**
     * Set what is inside a pack, or refuse to.
     *
     * <p>Both directions are enforced, and the second is the one easy to leave
     * out. A container with no measure is a SKU that says how the goods are
     * bundled and never how much a restaurant is buying. A measure on a unit that
     * is already an amount — "1 KG of 500 GM" — is two statements of one quantity,
     * which is two chances to disagree, and the disagreement would be discovered
     * by whoever received the wrong weight.
     */
    private void applyMeasure(SupplierSku sku, Unit pack,
                              BigDecimal measureValue, String measureUnit, boolean supplied) {

        if (!pack.requiresMeasure()) {
            // Only object when the caller actually asked for a measure. A SKU
            // moving from PKT to KG still *holds* 500 GM, and treating that
            // leftover as a contradiction would refuse an edit nobody got wrong —
            // the right answer is to clear it, because a stale 500 GM on a SKU now
            // sold by the kilo is worse than either.
            if (supplied && (measureValue != null
                    || (measureUnit != null && !measureUnit.isBlank()))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        ("A pack measured in %s already states its amount, so it cannot also "
                                + "carry pack contents.").formatted(pack));
            }
            sku.setMeasureValue(null);
            sku.setMeasureUnit(null);
            return;
        }

        if (measureValue == null || measureUnit == null || measureUnit.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Say what is inside one %s — for example 500 GM.".formatted(pack));
        }

        var measure = Unit.parse(measureUnit, "Pack contents unit");
        if (!measure.canMeasure()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "%s cannot measure what is inside a pack. Use one of: %s."
                            .formatted(measure, Unit.measureUnits()));
        }
        if (measure == pack) {
            // "1 PKT of 3 PKT" is a riddle, not a quantity.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A %s cannot be measured in %s.".formatted(pack, measure));
        }

        sku.setMeasureValue(measureValue);
        sku.setMeasureUnit(measure.name());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ── Morning Mandi Fast Rate Sheet (60-second Repricing Grid) ────────

    @Transactional(readOnly = true)
    public CatalogDtos.RateSheetResponse getRateSheet(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.CATALOG_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var storeSkus = skus.findBySupplierStoreIdAndStatus(storeId, "ACTIVE", org.springframework.data.domain.Pageable.unpaged()).getContent();
        if (storeSkus.isEmpty()) {
            storeSkus = skus.findBySupplierStoreId(storeId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        }

        var productMap = products.findAllById(storeSkus.stream().map(SupplierSku::getCanonicalProductId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(p -> p.getId(), p -> p.getName()));

        var brandIds = storeSkus.stream().map(SupplierSku::getBrandId).filter(java.util.Objects::nonNull).distinct().toList();
        var brandMap = directory.brandNames(brandIds);

        var offersBySku = offers.findBySupplierStoreIdAndStatus(storeId, "ACTIVE").stream()
                .collect(java.util.stream.Collectors.toMap(SupplierOffer::getSupplierSkuId, o -> o, (first, second) -> first));

        var rows = storeSkus.stream()
                .map(sku -> {
                    SupplierOffer offer = offersBySku.get(sku.getId());
                    BigDecimal sellingPrice = offer != null ? offer.getSellingPrice() : null;
                    BigDecimal mrp = offer != null && offer.getMrp() != null ? offer.getMrp() : sku.getMrp();
                    BigDecimal gstRate = offer != null ? offer.getGstRate() : BigDecimal.ZERO;
                    String availability = offer != null ? offer.getAvailability() : "AVAILABLE";
                    BigDecimal availableQty = offer != null ? offer.getAvailableQuantity() : null;
                    Instant updatedAt = offer != null ? offer.getEffectiveFrom() : sku.getUpdatedAt();

                    return new CatalogDtos.RateSheetRow(
                            sku.getId(),
                            sku.getCanonicalProductId(),
                            productMap.get(sku.getCanonicalProductId()),
                            sku.getName(),
                            sku.getBrandId() != null ? brandMap.get(sku.getBrandId()) : null,
                            sku.getGrade(),
                            sku.isCatchWeight(),
                            sku.isRequiresColdChain(),
                            sku.getPackSize(),
                            sku.getPackUnit(),
                            mrp,
                            sellingPrice,
                            gstRate,
                            availability,
                            availableQty,
                            updatedAt
                    );
                })
                .sorted(Comparator.comparing(CatalogDtos.RateSheetRow::productName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        return new CatalogDtos.RateSheetResponse(storeId, rows);
    }

    @Transactional
    public CatalogDtos.UpdateRateSheetResponse updateRateSheet(
            Long actorId, Long storeId, CatalogDtos.UpdateRateSheetRequest request) {

        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        int updatedCount = 0;
        for (CatalogDtos.UpdateRateSheetItem item : request.rows()) {
            var skuOpt = skus.findById(item.skuId());
            if (skuOpt.isEmpty() || !skuOpt.get().getSupplierStoreId().equals(storeId)) {
                continue;
            }
            SupplierSku sku = skuOpt.get();
            var currentOffer = offers.findBySupplierSkuIdAndStatus(sku.getId(), "ACTIVE");
            BigDecimal gstRate = currentOffer.map(SupplierOffer::getGstRate).orElse(BigDecimal.ZERO);

            CatalogDtos.UpdateSkuRequest updateReq = new CatalogDtos.UpdateSkuRequest(
                    sku.getCanonicalProductId(), sku.getSkuCode(), sku.getName(),
                    null, sku.getGrade(), sku.isCatchWeight(), sku.isRequiresColdChain(), sku.getPackSize(), sku.getPackUnit(),
                    sku.getMeasureValue(), sku.getMeasureUnit(), item.mrp() != null ? item.mrp() : sku.getMrp(),
                    sku.getImageUrl(), sku.getDescription(), sku.getLengthCm(), sku.getWidthCm(),
                    sku.getHeightCm(), sku.getWeightGrams(), sku.getYoutubeUrl(), List.of(),
                    sku.getStatus(), item.sellingPrice(), gstRate,
                    item.availability() != null ? item.availability() : "AVAILABLE",
                    item.availableQuantity()
            );

            supersedeOffer(sku, updateReq, actorId);
            updatedCount++;
        }

        auditService.record(actorId, null, "RATE_SHEET_UPDATED", "SUPPLIER_STORE",
                storeId, null, null, "Repriced " + updatedCount + " lines in morning rate sheet", "API");

        return new CatalogDtos.UpdateRateSheetResponse(updatedCount, getRateSheet(actorId, storeId).rows());
    }
}
