package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.CanonicalProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CanonicalProductAliasRepository extends JpaRepository<CanonicalProductAlias, Long> {
    List<CanonicalProductAlias> findByCanonicalProductId(Long canonicalProductId);
    List<CanonicalProductAlias> findByNormalizedAlias(String normalizedAlias);
}
