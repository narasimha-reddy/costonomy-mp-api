package com.costonomy.mp.catalog.repository;

import com.costonomy.mp.catalog.domain.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    List<ProductCategory> findByStatusOrderByDisplayOrderAscNameAsc(String status);
    Optional<ProductCategory> findBySlug(String slug);
}
