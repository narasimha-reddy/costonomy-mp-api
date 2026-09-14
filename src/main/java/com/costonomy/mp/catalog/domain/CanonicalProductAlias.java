package com.costonomy.mp.catalog.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * An alternative name for a canonical product. Doc 07 §2.
 *
 * <p>Append-only reference data, so no {@code version} or {@code updated_at}: an
 * alias is added or removed, never edited into a different alias.
 *
 * <p>This table is the entire vocabulary search understands. Doc 07 §2 forbids
 * inventing semantic mappings, so "dahi" finds curd because somebody configured
 * it here — not because a matcher guessed.
 */
@Entity
@Table(name = "canonical_product_alias")
@Getter
@Setter
@NoArgsConstructor
public class CanonicalProductAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    @Column(name = "alias", nullable = false, length = 250)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, length = 250)
    private String normalizedAlias;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
