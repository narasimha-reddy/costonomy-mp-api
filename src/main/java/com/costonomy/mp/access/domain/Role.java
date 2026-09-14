package com.costonomy.mp.access.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "role")
@Getter
@Setter
@NoArgsConstructor
public class Role extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Which world the role belongs to: RESTAURANT, SUPPLIER or INTERNAL. */
    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
