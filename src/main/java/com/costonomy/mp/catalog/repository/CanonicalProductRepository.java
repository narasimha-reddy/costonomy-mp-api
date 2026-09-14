package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.CanonicalProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CanonicalProductRepository extends JpaRepository<CanonicalProduct, Long> {

    Optional<CanonicalProduct> findByNormalizedName(String normalizedName);

    Page<CanonicalProduct> findByStatus(String status, Pageable pageable);

    Page<CanonicalProduct> findByStatusAndCategoryId(String status, Long categoryId, Pageable pageable);

    /**
     * Name-and-alias search against the normalised columns.
     *
     * <p>A {@code LIKE 'term%'} prefix match, which uses
     * {@code ix_canonical_normalized}. A leading wildcard would not, and this runs
     * on the discovery path.
     *
     * <p>This is the MySQL implementation of the search port (D-006). Doc 07 §12
     * allows a dedicated search index later; MySQL stays the transactional
     * authority regardless, and checkout revalidates against it.
     */
    @Query("""
            select distinct p from CanonicalProduct p
            where p.status = 'ACTIVE'
              and (p.normalizedName like concat(:term, '%')
                   or exists (
                       select 1 from CanonicalProductAlias a
                       where a.canonicalProductId = p.id
                         and a.normalizedAlias like concat(:term, '%')))
            order by p.name asc
            """)
    List<CanonicalProduct> searchByPrefix(@Param("term") String normalizedTerm, Pageable pageable);
}
