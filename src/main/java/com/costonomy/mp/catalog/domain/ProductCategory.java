package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_category")
@Getter
@Setter
@NoArgsConstructor
public class ProductCategory extends BaseEntity {

    /** Null for a top-level category. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 150)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 100;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
