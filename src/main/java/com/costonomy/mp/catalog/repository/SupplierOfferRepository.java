package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.SupplierOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierOfferRepository extends JpaRepository<SupplierOffer, Long> {

    /** The live offer for a SKU. At most one row is ACTIVE at a time. */
    Optional<SupplierOffer> findBySupplierSkuIdAndStatus(Long supplierSkuId, String status);

    /** Price history, newest first. */
    List<SupplierOffer> findBySupplierSkuIdOrderByEffectiveFromDesc(Long supplierSkuId);

    /**
     * The live offers for a set of SKUs, in one query.
     *
     * <p>For pricing a whole basket: a request holds no prices of its own, so
     * showing a kitchen what it is about to ask for means reading every line's
     * current offer, and doing that one at a time makes a ten-line basket cost
     * eleven queries to draw.
     */
    List<SupplierOffer> findBySupplierSkuIdInAndStatus(List<Long> supplierSkuIds, String status);

    List<SupplierOffer> findBySupplierStoreIdAndStatus(Long supplierStoreId, String status);

    /**
     * Purchasable offers for a canonical product, cheapest first.
     *
     * <p>The supplier comparison query (§23A.13). Ordered by price only as a
     * deterministic baseline — <b>this is not the ranking</b>. Best Value ranking
     * (doc 07 §4) weighs availability, ETA, fill rate, on-time performance and
     * rating alongside price, and lands in Phase 6. Commission is never an input
     * to either (guardrail 9).
     */
    @Query("""
            select o from SupplierOffer o
            where o.canonicalProductId = :productId
              and o.status = 'ACTIVE'
              and o.availability = 'AVAILABLE'
            order by o.sellingPrice asc, o.id asc
            """)
    List<SupplierOffer> findPurchasableForProduct(@Param("productId") Long canonicalProductId);

    /**
     * The same offers for a whole page of products, in one query.
     *
     * <p>A list of thirty products used to mean thirty of these, and the figures
     * they feed — "3 suppliers", "from ₹84.60" — are on every card.
     */
    @Query("""
            select o from SupplierOffer o
            where o.canonicalProductId in :productIds
              and o.status = 'ACTIVE'
              and o.availability = 'AVAILABLE'
            """)
    List<SupplierOffer> findPurchasableForProducts(@Param("productIds") List<Long> canonicalProductIds);
}
