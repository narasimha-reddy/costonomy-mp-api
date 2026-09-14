package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findByNormalizedName(String normalizedName);
    List<Brand> findByStatusOrderByNameAsc(String status);
}
