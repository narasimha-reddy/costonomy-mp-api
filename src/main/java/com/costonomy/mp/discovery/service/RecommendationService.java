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
import com.costonomy.mp.discovery.domain.Serviceability;
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

        var results = ranked.stream()
                .map(scored -> toResponse(scored, offerById.get(scored.offerId()),
                        skuById.get(scored.supplierSkuId()),
                        stores.get(scored.supplierStoreId()), brandNames, quantity))
                .toList();

        return new DiscoveryDtos.ProductRecommendation(
                product.getId(), product.getName(), quantity, product.getBaseUnit(),
                results, null);
    }

    /**
     * Whether a store delivers to an outlet. Doc 07 §13, doc 41.
     *
     * <p>An explicit pincode list overrides geography entirely: a supplier who has
     * said "these areas only" means it, and a distance calculation should not
     * second-guess them.
     *
     * <p>When coordinates are missing the store is treated as serviceable rather
     * than excluded. A supplier whose address has not been geocoded should not
     * become invisible — that is a data gap on our side, and the checkout-time
     * re-check (doc 41) is the place it gets caught.
     */
    private boolean servesOutlet(DiscoveryDirectory.StoreInfo store,
                                 DiscoveryDirectory.OutletInfo outlet,
                                 Double distanceKm, BigDecimal defaultRadius) {

        if (store.serviceablePincodes() != null && !store.serviceablePincodes().isEmpty()) {
            return outlet.pincode() != null && store.serviceablePincodes().contains(outlet.pincode());
        }
        if (distanceKm == null) {
            return true;
        }
        BigDecimal radius = store.maxDeliveryRadiusKm() == null
                ? defaultRadius : store.maxDeliveryRadiusKm();
        return BigDecimal.valueOf(distanceKm).compareTo(radius) <= 0;
    }

    private DiscoveryDtos.RecommendedOffer toResponse(
            ScoredOffer scored, SupplierOffer offer, SupplierSku sku,
            DiscoveryDirectory.StoreInfo store, Map<Long, String> brandNames, BigDecimal quantity) {

        BigDecimal itemTotal = offer.getSellingPrice().multiply(quantity);
        BigDecimal gstAmount = itemTotal.multiply(offer.getGstRate())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        return new DiscoveryDtos.RecommendedOffer(
                scored.offerId(), sku.getId(), store.storeId(),
                store.supplierName(), store.storeName(),
                sku.getName(),
                sku.getBrandId() == null ? null : brandNames.get(sku.getBrandId()),
                sku.getPackSize(), sku.getPackUnit(),
                offer.getSellingPrice(), offer.getGstRate(),
                itemTotal, gstAmount, scored.effectiveTotal(),
                offer.getAvailability(), offer.getAvailableQuantity(),
                scored.coversFullQuantity(),
                scored.etaMinutes(), scored.distanceKm(), store.responseSlaSeconds(),
                scored.explanations(), scored.score(), scored.componentScores());
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
