package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.CanonicalProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CanonicalProductAliasRepository extends JpaRepository<CanonicalProductAlias, Long> {
    List<CanonicalProductAlias> findByCanonicalProductId(Long canonicalProductId);
    List<CanonicalProductAlias> findByNormalizedAlias(String normalizedAlias);

    /**
     * Aliases starting with a normalised prefix, served by {@code ix_alias_normalized} (D-182). The typeahead used to
     * load the whole table and filter in Java on every keystroke. A normalised prefix holds only letters, digits and
     * spaces, so it contains no LIKE wildcard.
     */
    @Query("select a from CanonicalProductAlias a where a.normalizedAlias like concat(:prefix, '%') "
            + "order by a.normalizedAlias, a.id")
    List<CanonicalProductAlias> findByPrefix(@Param("prefix") String prefix, Pageable pageable);
}
