package com.costonomy.mp.access.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "permission")
@Getter
@Setter
@NoArgsConstructor
public class Permission extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** RESTAURANT, SUPPLIER, INTERNAL, or SHARED where both worlds use it. */
    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Column(name = "description", length = 500)
    private String description;
}
