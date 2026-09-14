package com.costonomy.mp.restaurant.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A restaurant business. Owns one or more outlets. Doc 02 §4. */
@Entity
@Table(name = "restaurant")
@Getter
@Setter
@NoArgsConstructor
public class Restaurant extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "legal_name", length = 250)
    private String legalName;

    @Column(name = "gstin", length = 32)
    private String gstin;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;
}
