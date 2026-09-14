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
}
