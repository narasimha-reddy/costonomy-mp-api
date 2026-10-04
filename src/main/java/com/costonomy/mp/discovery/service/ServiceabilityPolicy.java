package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.domain.Serviceability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Single serviceability evaluator shared across Storefront, Recommendations,
 * and Popular suppliers (D-138).
 *
 * <p>Evaluation order:
 * <ol>
 *   <li>The store's declared pincode list wins if it has one (non-null and non-empty).</li>
 *   <li>Otherwise the store's own declared max delivery radius (if configured).</li>
 *   <li>Otherwise the configured default radius ({@code serviceability.defaultRadiusKm}, fallback 25 km).</li>
 * </ol>
 *
 * <p><b>Missing coordinates:</b> if either outlet or store coordinates are missing (distanceKm is null),
 * the store is treated as serviceable (do not hide a supplier because data is missing).
 *
 * <p>Opening-hours filters do not belong here.
 */
@Component
@RequiredArgsConstructor
public class ServiceabilityPolicy {

    public static final BigDecimal FALLBACK_DEFAULT_RADIUS_KM = BigDecimal.valueOf(25);

    private final AppConfigService config;

    /**
     * Evaluates serviceability using the configured default radius from {@link AppConfigService}.
     */
    public boolean serves(Collection<String> serviceablePincodes,
                          BigDecimal maxDeliveryRadiusKm,
                          String outletPincode,
                          Double distanceKm) {
        BigDecimal defaultRadius = config != null
                ? config.getDecimal("serviceability.defaultRadiusKm", FALLBACK_DEFAULT_RADIUS_KM)
                : FALLBACK_DEFAULT_RADIUS_KM;
        return serves(serviceablePincodes, maxDeliveryRadiusKm, outletPincode, distanceKm, defaultRadius);
    }

    /**
     * Evaluates serviceability with an explicit default radius.
     */
    public boolean serves(Collection<String> serviceablePincodes,
                          BigDecimal maxDeliveryRadiusKm,
                          String outletPincode,
                          Double distanceKm,
                          BigDecimal defaultRadius) {
        if (serviceablePincodes != null && !serviceablePincodes.isEmpty()) {
            return outletPincode != null && serviceablePincodes.contains(outletPincode);
        }
        if (distanceKm == null) {
            return true;
        }
        BigDecimal effectiveRadius = maxDeliveryRadiusKm != null ? maxDeliveryRadiusKm : defaultRadius;
        return BigDecimal.valueOf(distanceKm).compareTo(effectiveRadius) <= 0;
    }

    /**
     * Convenience method for evaluating serviceability against {@link DiscoveryDirectory.StoreInfo}.
     */
    public boolean serves(DiscoveryDirectory.StoreInfo store, String outletPincode, Double distanceKm) {
        if (store == null) {
            return true;
        }
        return serves(store.serviceablePincodes(), store.maxDeliveryRadiusKm(), outletPincode, distanceKm);
    }
}
