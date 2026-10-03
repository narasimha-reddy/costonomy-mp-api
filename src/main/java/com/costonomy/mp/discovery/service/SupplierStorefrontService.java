package com.costonomy.mp.discovery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The head of one supplier's shelf, for the restaurant standing in front of it.
 *
 * <p><b>Separate from {@link StorefrontService} because this one is scoped.</b>
 * That service is deliberately open — it returns the catalogue any signed-in
 * restaurant is meant to shop, and its {@code outletId} only supplies a
 * distance. This carries what a supplier has extended <em>this outlet</em> in
 * credit, which is that outlet's business and nobody else's, so the actor's
 * scope on the outlet is checked before a rupee of it is assembled.
 *
 * <p><b>One request, because it is one question.</b> "Should I shop here" is
 * answered by the branch, the distance, the likely wait, what other kitchens
 * thought, and whether this supplier has given them terms. Four round trips to
 * assemble one header is four chances for the screen to render half an answer.
 */
@Service
@RequiredArgsConstructor
public class SupplierStorefrontService {

    private final JdbcTemplate jdbc;
    private final DiscoveryDirectory directory;
    private final SupplierPerformanceProvider performance;
    private final CreditAgreementRepository creditAgreements;
    private final AccessControlService accessControl;
    private final AppConfigService config;

    @Transactional(readOnly = true)
    public DiscoveryDtos.StorefrontHeader header(Long actorId, Long storeId, Long outletId) {
        var store = directory.stores(List.of(storeId)).get(storeId);
        if (store == null) {
            throw new NotFoundException("SupplierStore", storeId);
        }

        // The outlet is optional — a restaurant can look at a shelf before
        // picking which kitchen is buying — but the moment one is named, it
        // decides what credit is shown, and that is not public.
        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);
        if (outletId != null) {
            accessControl.requireScoped(actorId, Permissions.CREDIT_VIEW,
                    ScopeType.OUTLET, outletId, "Outlet");
        }

        Double distance = outlet == null ? null : Serviceability.distanceKm(
                outlet.latitude(), outlet.longitude(), store.latitude(), store.longitude());

        // The same figures the product comparison ranks on, so a shelf and a
        // recommendation cannot disagree about how long this supplier takes.
        Integer eta = Serviceability.estimateMinutes(
                store.preparationMinutes(), distance,
                config.getDecimal("eta.averageSpeedKmph", new BigDecimal("20")),
                config.getInt("eta.dispatchOverheadMinutes", 15));

        var metrics = performance.forStores(List.of(storeId)).get(storeId);

        return new DiscoveryDtos.StorefrontHeader(
                storeId, store.storeName(), store.supplierName(), store.city(),
                Serviceability.round(distance),
                store.openNow(), store.opensAt(), eta,
                metrics == null ? null : metrics.averageRating().orElse(null),
                metrics == null ? 0 : metrics.ratingCount(),
                skuCount(storeId),
                store.directOrdersEnabled(),
                outletId == null ? null : credit(outletId, storeId),
                siblings(storeId, outlet));
    }

    /**
     * What this supplier has extended this outlet, or null.
     *
     * <p>Null covers both "never asked" and "asked and was turned down", which
     * the app shows the same way: an offer to ask. A rejected agreement is not a
     * standing refusal — terms are asked for again when the relationship or the
     * volume changes.
     */
    private DiscoveryDtos.StoreCredit credit(Long outletId, Long storeId) {
        return creditAgreements.findByOutletIdAndSupplierStoreId(outletId, storeId)
                .map(agreement -> new DiscoveryDtos.StoreCredit(
                        agreement.getId(),
                        agreement.getStatus().name(),
                        agreement.getApprovedLimit(),
                        agreement.getUtilizedAmount(),
                        agreement.getReservedAmount(),
                        // Never recomputed here, and never by a client: what is
                        // left to spend depends on reservations against orders
                        // already in flight. §23A.24.
                        agreement.available(),
                        agreement.getCreditPeriodDays(),
                        agreement.getStatus().canFund()))
                .orElse(null);
    }

    /** How much this store lists that could be bought today. */
    private int skuCount(Long storeId) {
        Integer count = jdbc.queryForObject("""
                select count(distinct f.id)
                  from supplier_offer f
                  join supplier_sku k on k.id = f.supplier_sku_id and k.status = 'ACTIVE'
                 where f.supplier_store_id = ?
                   and f.status = 'ACTIVE'
                   and (f.effective_to is null or f.effective_to > now())
                """, Integer.class, storeId);
        return count == null ? 0 : count;
    }

    /**
     * The supplier's other branches, nearest first.
     *
     * <p>Only trading ones. A branch that cannot take an order is not a branch a
     * kitchen should be offered as an alternative to the one they are looking at.
     */
    private List<DiscoveryDtos.SiblingStore> siblings(
            Long storeId, DiscoveryDirectory.OutletInfo outlet) {

        var ids = jdbc.queryForList("""
                select other.id
                  from supplier_store s
                  join supplier_store other
                    on other.supplier_organization_id = s.supplier_organization_id
                  join supplier_organization o on o.id = other.supplier_organization_id
                 where s.id = ?
                   and other.id <> s.id
                   and other.status = 'ACTIVE'
                   and o.lifecycle_status = 'ACTIVE'
                """, Long.class, storeId);
        if (ids.isEmpty()) {
            return List.of();
        }

        var stores = directory.stores(ids);
        var out = new ArrayList<DiscoveryDtos.SiblingStore>(ids.size());
        for (Long id : ids) {
            var sibling = stores.get(id);
            if (sibling == null) {
                continue;
            }
            Double distance = outlet == null ? null : Serviceability.distanceKm(
                    outlet.latitude(), outlet.longitude(),
                    sibling.latitude(), sibling.longitude());
            out.add(new DiscoveryDtos.SiblingStore(id, sibling.storeName(), sibling.city(),
                    Serviceability.round(distance), sibling.openNow()));
        }

        // Unlocated branches sort last rather than being dropped: they can still
        // be ordered from, and a list that hides one is wrong invisibly.
        out.sort(Comparator.comparing(DiscoveryDtos.SiblingStore::distanceKm,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }
}
