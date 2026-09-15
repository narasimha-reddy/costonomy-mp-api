package com.costonomy.mp.trust.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One message in a dispute. Append-only.
 *
 * <p>{@code internal} marks an operations note neither party sees — §23A.32's
 * "never expose internal moderation notes unnecessarily". One thread rather than
 * two so a moderator reads the conversation in order; filtering on the flag is
 * what keeps it private, and it is not optional at any read site.
 */
@Entity
@Table(name = "dispute_message")
@Getter
@Setter
@NoArgsConstructor
public class DisputeMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    /** RESTAURANT, SUPPLIER or OPERATIONS. */
    @Column(name = "author_side", nullable = false, length = 32)
    private String authorSide;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "internal", nullable = false)
    private Boolean internal = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
