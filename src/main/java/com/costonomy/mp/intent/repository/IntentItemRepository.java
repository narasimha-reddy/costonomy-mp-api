package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IntentItemRepository extends JpaRepository<IntentItem, Long> {

    List<IntentItem> findByIntentIdOrderByIdAsc(Long intentId);

    List<IntentItem> findByIntentIdIn(List<Long> intentIds);

    Optional<IntentItem> findByIntentIdAndSupplierSkuId(Long intentId, Long supplierSkuId);

    long countByIntentId(Long intentId);

    // Locking reads, taken after the intent's lock (intent, then lines). A plain read under REPEATABLE READ may not
    // see what a writer we waited for has committed; these do (D-137).

    @Query("select i.intentId from IntentItem i where i.id = :id")
    Optional<Long> findIntentIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IntentItem i where i.id = :id")
    Optional<IntentItem> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IntentItem i where i.intentId = :intentId and i.supplierSkuId = :skuId")
    Optional<IntentItem> lockByIntentIdAndSupplierSkuId(@Param("intentId") Long intentId, @Param("skuId") Long skuId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IntentItem i where i.intentId = :intentId order by i.id")
    List<IntentItem> lockByIntentId(@Param("intentId") Long intentId);
}
