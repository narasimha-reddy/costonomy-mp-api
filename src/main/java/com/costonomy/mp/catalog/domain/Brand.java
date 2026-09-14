package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "brand")
@Getter
@Setter
@NoArgsConstructor
public class Brand extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Uniquely indexed, so "Amul", "AMUL" and "amul " are one brand. */
    @Column(name = "normalized_name", nullable = false, length = 200)
    private String normalizedName;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
