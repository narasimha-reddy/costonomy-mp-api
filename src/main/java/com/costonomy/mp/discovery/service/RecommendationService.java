package com.costonomy.mp.discovery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.discovery.domain.RankingWeights;
import com.costonomy.mp.discovery.domain.ScoredOffer;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.discovery.domain.SupplierPerformance;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Turns "we need 20 kg of paneer at this outlet" into a ranked list of offers.
 *
 * <p>Implements doc 07 §3's pipeline in order: resolve the product, find active
 * SKUs, drop unavailable ones, drop inactive or offline stores, drop stores that
 * cannot deliver here, compute the commercial value, estimate an ETA, score
 * performance, rank.
 *
 * <p>The filters run before scoring and are absolute. An offer that survives is
 * one a restaurant could actually buy right now — doc 03 §15 only permits a
 * supplier order against an ACTIVE store, so ranking something unbuyable would
 * produce a recommendation that fails at checkout.
 *
 * <p>Two things this does <b>not</b> do. It does not consider commission
 * (guardrail 9). And it does not invent performance data — see
 * {@link NoHistoryPerformanceProvider}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private final CanonicalProductRepository products;
    private final SupplierOfferRepository offers;
    private final SupplierSkuRepository skus;
    private final SupplierPerformanceProvider performanceProvider;
    private final BestValueScorer scorer;
    private final DiscoveryDirectory directory;
    private final AccessControlService accessControl;
    private final AppConfigService config;
    private final ServiceabilityPolicy serviceabilityPolicy;

    /**
     * Ranked offers for a product, for a specific outlet.
     *
     * <p>The outlet is required rather than optional: serviceability, distance and
     * therefore ETA all depend on where the goods are going, and a ranking that
     * ignores that would recommend a supplier who cannot deliver.
     */
    @Transactional(readOnly = true)
    public DiscoveryDtos.ProductRecommendation recommendForProduct(
            Long actorId, Long productId, Long outletId, BigDecimal requestedQuantity) {
        return recommendForProduct(actorId, productId, outletId, requestedQuantity, null, null, null, null);
    }

    /**
     * The same, with the choices a buyer makes on the comparison (D-149).
     *
     * <p>Filters and sort apply <b>after</b> scoring: Best Value is relative to the suppliers compared, so scoring
     * only the ones left would change every supplier's score and rank each time a filter was touched. A filter
     * removes a card; it does not re-rank the rest.
     *
     * @param sort           {@code best_value} (default, the ranking), {@code price} (lowest per unit first),
     *                       {@code nearest} or {@code rating}; anything else is a 422
     * @param coversQuantity only suppliers with at least the quantity asked for
     * @param openNow        only suppliers open right now
     * @param radiusKm       only suppliers within this distance (an unknown distance is kept)
     */
    @Transactional(readOnly = true)
    public DiscoveryDtos.ProductRecommendation recommendForProduct(
            Long actorId, Long productId, Long outletId, BigDecimal requestedQuantity,
            String sort, Boolean coversQuantity, Boolean openNow, BigDecimal radiusKm) {

        String effectiveSort = requireSort(sort);
        if (radiusKm != null && radiusKm.signum() <= 0) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR, "Distance must be more than zero.");
        }

        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        var product = products.findById(productId)
                .orElseThrow(() -> new NotFoundException("CanonicalProduct", productId));

        var outlet = directory.outlet(outletId)
                .orElseThrow(() -> new NotFoundException("Outlet", outletId));

        BigDecimal quantity = requestedQuantity == null || requestedQuantity.signum() <= 0
                ? BigDecimal.ONE : requestedQuantity;

        // 1–3: purchasable offers for this product, with their SKUs.
        var purchasable = offers.findPurchasableForProduct(productId);
        if (purchasable.isEmpty()) {
            return unserved(product.getId(), product.getName(), quantity, product.getBaseUnit(),
                    "No supplier currently lists this product as available.");
        }

        Map<Long, SupplierSku> skuById = new HashMap<>();
        skus.findAllById(purchasable.stream().map(SupplierOffer::getSupplierSkuId).toList())
                .forEach(sku -> skuById.put(sku.getId(), sku));

        var storeIds = purchasable.stream()
                .map(SupplierOffer::getSupplierStoreId).distinct().toList();
        var stores = directory.stores(storeIds);

        // 4–5: drop what cannot actually be bought and delivered here.
        var weights = loadWeights();
        var thresholds = loadThresholds();
        BigDecimal speed = config.getDecimal("eta.averageSpeedKmph", new BigDecimal("20"));
        int dispatchOverhead = config.getInt("eta.dispatchOverheadMinutes", 15);
        BigDecimal defaultRadius = config.getDecimal("serviceability.defaultRadiusKm",
                new BigDecimal("25"));

        var performance = performanceProvider.forStores(storeIds);
        List<BestValueScorer.Candidate> candidates = new ArrayList<>();
        boolean anyStoreFound = false;
        boolean anyServiceable = false;

        for (SupplierOffer offer : purchasable) {
            SupplierSku sku = skuById.get(offer.getSupplierSkuId());
            if (sku == null || !"ACTIVE".equals(sku.getStatus())) {
                continue;
            }
            var store = stores.get(offer.getSupplierStoreId());
            if (store == null || !store.tradeable()) {
                continue;
            }
            anyStoreFound = true;

            Double distance = Serviceability.distanceKm(
                    outlet.latitude(), outlet.longitude(), store.latitude(), store.longitude());

            if (!servesOutlet(store, outlet, distance, defaultRadius)) {
                continue;
            }
            anyServiceable = true;

            // 6: commercial value. Line total then GST on it — the same order the
            // server will use when the order is actually priced, so a
            // recommendation and a cart agree.
            BigDecimal itemTotal = offer.getSellingPrice().multiply(quantity);
            BigDecimal gstAmount = itemTotal.multiply(offer.getGstRate())
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal effectiveTotal = itemTotal.add(gstAmount);

            // 7: ETA. Null when we cannot compute it — the scorer treats that as
            // absent rather than slow.
            Integer eta = Serviceability.estimateMinutes(
                    store.preparationMinutes(), distance, speed, dispatchOverhead);

            candidates.add(new BestValueScorer.Candidate(
                    offer.getId(), sku.getId(), offer.getSupplierStoreId(),
                    effectiveTotal, eta, Serviceability.round(distance),
                    offer.getAvailableQuantity(), quantity,
                    performance.get(offer.getSupplierStoreId())));
        }

        if (candidates.isEmpty()) {
            // §23A.15: an unmet item is shown and explained, never silently
            // dropped. The reason has to be specific enough to act on — "nobody
            // delivers here" and "everyone is closed" call for different responses.
            String reason = !anyStoreFound
                    ? "Suppliers listing this product aren't currently accepting orders."
                    : !anyServiceable
                    ? "No supplier of this product delivers to this outlet yet."
                    : "No supplier can currently fulfil this requirement.";
            return unserved(product.getId(), product.getName(), quantity,
                    product.getBaseUnit(), reason);
        }

        // 8–9: score and rank.
        List<ScoredOffer> ranked = scorer.score(candidates, weights, thresholds);

        var brandNames = directory.brandNames(skuById.values().stream()
                .map(SupplierSku::getBrandId).filter(Objects::nonNull).toList());
        Map<Long, SupplierOffer> offerById = new HashMap<>();
        purchasable.forEach(offer -> offerById.put(offer.getId(), offer));

        Map<Long, List<DiscoveryDtos.BrandOption>> brandOptionsByStore = new HashMap<>();
        for (SupplierOffer offer : purchasable) {
            SupplierSku sku = skuById.get(offer.getSupplierSkuId());
            if (sku == null || !"ACTIVE".equals(sku.getStatus())) {
                continue;
            }
            var store = stores.get(offer.getSupplierStoreId());
            if (store == null || !store.tradeable()) {
                continue;
            }
            BigDecimal mrp = offer.getMrp() != null ? offer.getMrp() : sku.getMrp();
            BigDecimal discountAmount = Pricing.discountAmount(mrp, offer.getSellingPrice());
            Integer discountPercent = Pricing.discountPercent(mrp, offer.getSellingPrice());
            brandOptionsByStore.computeIfAbsent(offer.getSupplierStoreId(), k -> new ArrayList<>())
                    .add(new DiscoveryDtos.BrandOption(
                            sku.getId(),
                            offer.getId(),
                            sku.getName(),
                            sku.getBrandId() == null ? null : brandNames.get(sku.getBrandId()),
                            sku.getGrade(),
                            sku.getPackSize(),
                            sku.getPackUnit(),
                            mrp,
                            offer.getSellingPrice(),
                            discountAmount,
                            discountPercent,
                            offer.getGstRate(),
                            packInclusiveOfGst(offer),
                            blankToNull(sku.getImageUrl()) != null ? sku.getImageUrl() : blankToNull(product.getImageUrl()),
                            offer.getAvailability(),
                            offer.getAvailableQuantity(),
                            sku.getMeasureValue(),
                            sku.getMeasureUnit()
                    ));
        }

        // Lowest priced brand option first
        for (List<DiscoveryDtos.BrandOption> options : brandOptionsByStore.values()) {
            options.sort(Comparator.comparing(DiscoveryDtos.BrandOption::sellingPrice,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }

        /*
         * One card per supplier — their best pack. D-096.
         *
         * A supplier may now list several packs of the same product, and every
         * one of them scoring into this list turns a comparison of suppliers
         * into a comparison of one supplier's shelf: a deep range fills the
         * screen and pushes the others below the fold, on a screen whose entire
         * job is to put them side by side.
         *
         * The ranking still decides which pack wins, so this keeps whichever
         * the scorer put first. The rest are reachable from that pack's detail
         * page, which says how many there are.
         */
        var seen = new HashSet<Long>();
        var packsPerStore = new HashMap<Long, Integer>();
        ranked.forEach(scored -> packsPerStore.merge(scored.supplierStoreId(), 1, Integer::sum));

        // Filters, on the ranked list so that scores stay what they were. A supplier with several packs is kept if any
        // one of them passes, and its card is then its best pack that passes.
        boolean filtering = Boolean.TRUE.equals(coversQuantity) || Boolean.TRUE.equals(openNow) || radiusKm != null;
        long suppliersBefore = ranked.stream().map(ScoredOffer::supplierStoreId).distinct().count();
        if (filtering) {
            ranked = ranked.stream().filter(scored -> {
                if (Boolean.TRUE.equals(coversQuantity) && !scored.coversFullQuantity()) {
                    return false;
                }
                var store = stores.get(scored.supplierStoreId());
                if (Boolean.TRUE.equals(openNow) && store != null && !store.openNow()) {
                    return false;
                }
                return radiusKm == null || scored.distanceKm() == null
                        || BigDecimal.valueOf(scored.distanceKm().doubleValue()).compareTo(radiusKm) <= 0;
            }).toList();
        }
        int hidden = (int) (suppliersBefore - ranked.stream().map(ScoredOffer::supplierStoreId).distinct().count());

        var results = ranked.stream()
                .filter(scored -> seen.add(scored.supplierStoreId()))
                .map(scored -> toResponse(scored, offerById.get(scored.offerId()),
                        skuById.get(scored.supplierSkuId()),
                        stores.get(scored.supplierStoreId()), brandNames, quantity,
                        product.getImageUrl(), performance.get(scored.supplierStoreId()),
                        product.getBaseUnit(),
                        // What this card is standing in front of.
                        packsPerStore.getOrDefault(scored.supplierStoreId(), 1) - 1,
                        brandOptionsByStore.getOrDefault(scored.supplierStoreId(), List.of())))
                .toList();

        return new DiscoveryDtos.ProductRecommendation(
                product.getId(), product.getName(), quantity, product.getBaseUnit(),
                sorted(results, effectiveSort), null, hidden);
    }

    /**
     * Whether a store delivers to an outlet. Doc 07 §13, doc 41. D-138.
     *
     * <p>Delegates to shared {@link ServiceabilityPolicy}.
     */
    private boolean servesOutlet(DiscoveryDirectory.StoreInfo store,
                                 DiscoveryDirectory.OutletInfo outlet,
                                 Double distanceKm, BigDecimal defaultRadius) {
        return serviceabilityPolicy.serves(
                store.serviceablePincodes(), store.maxDeliveryRadiusKm(),
                outlet.pincode(), distanceKm, defaultRadius);
    }

    private DiscoveryDtos.RecommendedOffer toResponse(
            ScoredOffer scored, SupplierOffer offer, SupplierSku sku,
            DiscoveryDirectory.StoreInfo store, Map<Long, String> brandNames, BigDecimal quantity,
            String canonicalImageUrl, SupplierPerformance storePerformance, String baseUnit,
            int otherPackCount, List<DiscoveryDtos.BrandOption> brandOptions) {

        BigDecimal itemTotal = offer.getSellingPrice().multiply(quantity);
        BigDecimal gstAmount = itemTotal.multiply(offer.getGstRate())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal mrp = offer.getMrp() != null ? offer.getMrp() : sku.getMrp();
        BigDecimal discountAmount = Pricing.discountAmount(mrp, offer.getSellingPrice());
        Integer discountPercent = Pricing.discountPercent(mrp, offer.getSellingPrice());

        return new DiscoveryDtos.RecommendedOffer(
                scored.offerId(), sku.getId(), store.storeId(),
                store.supplierName(), store.storeName(),
                sku.getName(),
                sku.getBrandId() == null ? null : brandNames.get(sku.getBrandId()),
                sku.getGrade(),
                sku.getPackSize(), sku.getPackUnit(),
                mrp,
                offer.getSellingPrice(),
                discountAmount,
                discountPercent,
                offer.getGstRate(),
                itemTotal, gstAmount, scored.effectiveTotal(),
                offer.getAvailability(), offer.getAvailableQuantity(),
                scored.coversFullQuantity(),
                scored.etaMinutes(), scored.distanceKm(), store.responseSlaSeconds(),
                scored.explanations(), scored.score(), scored.componentScores(),
                // The supplier's own photograph of their pack when they uploaded
                // one — it is what will arrive — and the platform's picture of the
                // product otherwise. Blank is not a URL.
                blankToNull(sku.getImageUrl()) != null
                        ? sku.getImageUrl() : blankToNull(canonicalImageUrl),
                storePerformance == null ? null : storePerformance.averageRating().orElse(null),
                storePerformance == null ? 0 : storePerformance.ratingCount(),
                packInclusiveOfGst(offer),
                pricePerBaseUnit(packInclusiveOfGst(offer), sku.getPackSize(),
                        sku.getPackUnit(), baseUnit),
                otherPackCount,
                brandOptions);
    }

    /**
     * One pack with its GST, through the same arithmetic the cart uses.
     *
     * <p>Not {@code sellingPrice × 1.05} computed here: {@link Pricing} rounds the
     * line value before applying the rate, and a second implementation of that
     * would differ from the invoice in the last paisa — which is precisely the
     * divergence `Pricing` exists to prevent.
     */
    private static BigDecimal packInclusiveOfGst(SupplierOffer offer) {
        BigDecimal value = Pricing.lineItemValue(offer.getSellingPrice(), BigDecimal.ONE);
        return Pricing.lineTotal(value, Pricing.lineGst(value, offer.getGstRate()));
    }

    /**
     * What one base unit costs, so a 1 kg pack and a 25 kg sack can be compared.
     *
     * <p>Only when the pack is measured in the product's own unit. "₹410 per PKT"
     * where the product is sold by the kilo is not a unit price, and printing one
     * anyway would make two incomparable offers look comparable.
     */
    private static BigDecimal pricePerBaseUnit(BigDecimal packPrice, BigDecimal packSize,
                                               String packUnit, String baseUnit) {
        if (packPrice == null || packSize == null || packSize.signum() <= 0
                || packUnit == null || !packUnit.equalsIgnoreCase(baseUnit)) {
            return null;
        }
        return packPrice.divide(packSize, 2, RoundingMode.HALF_UP);
    }

    private static String requireSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "best_value";
        }
        String normalized = sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("best_value", "price", "nearest", "rating").contains(normalized)) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                    "Sort must be best_value, price, nearest or rating.");
        }
        return normalized;
    }

    /**
     * Orders the one-card-per-supplier list. {@code best_value} is the ranking as scored. The others break ties by the
     * score, so equal prices or distances keep the Best Value order, and a missing value (an unknown distance, an
     * unrated supplier, a pack not measured in the product's unit) goes last rather than first.
     */
    private static List<DiscoveryDtos.RecommendedOffer> sorted(
            List<DiscoveryDtos.RecommendedOffer> offers, String sort) {
        Comparator<DiscoveryDtos.RecommendedOffer> byScore = Comparator.comparing(
                DiscoveryDtos.RecommendedOffer::score, Comparator.nullsLast(Comparator.reverseOrder()));
        return switch (sort) {
            case "price" -> offers.stream().sorted(Comparator
                    .comparing(DiscoveryDtos.RecommendedOffer::pricePerBaseUnit,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(DiscoveryDtos.RecommendedOffer::effectiveTotal,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byScore)).toList();
            case "nearest" -> offers.stream().sorted(Comparator
                    .comparing(DiscoveryDtos.RecommendedOffer::distanceKm,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byScore)).toList();
            case "rating" -> offers.stream().sorted(Comparator
                    .comparing(DiscoveryDtos.RecommendedOffer::averageRating,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DiscoveryDtos.RecommendedOffer::distanceKm,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byScore)).toList();
            default -> offers;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static DiscoveryDtos.ProductRecommendation unserved(
            Long productId, String name, BigDecimal quantity, String unit, String reason) {
        return new DiscoveryDtos.ProductRecommendation(
                productId, name, quantity, unit, List.of(), reason);
    }

    private RankingWeights loadWeights() {
        var configured = config.getDecimalsUnder("ranking.weight.");
        if (configured.isEmpty()) {
            log.warn("No ranking weights configured — using defaults");
            return RankingWeights.defaults();
        }
        var defaults = RankingWeights.defaults();
        return new RankingWeights(
                configured.getOrDefault("price", defaults.price()),
                configured.getOrDefault("eta", defaults.eta()),
                configured.getOrDefault("availability", defaults.availability()),
                configured.getOrDefault("fillRate", defaults.fillRate()),
                configured.getOrDefault("onTime", defaults.onTime()),
                configured.getOrDefault("rating", defaults.rating()),
                configured.getOrDefault("reliability", defaults.reliability()));
    }

    private BestValueScorer.ExplanationThresholds loadThresholds() {
        var defaults = BestValueScorer.ExplanationThresholds.defaults();
        return new BestValueScorer.ExplanationThresholds(
                config.getDecimal("ranking.explain.fillRateThreshold", defaults.fillRateThreshold()),
                config.getDecimal("ranking.explain.onTimeThreshold", defaults.onTimeThreshold()),
                config.getInt("ranking.explain.minOrdersForTrust", defaults.minOrdersForTrust()));
    }
}
