package com.costonomy.mp.intent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Which order came from which intent, and against which acceptance.
 *
 * <p>A link row rather than a column on either side, because it records three
 * things at once: an order traces back to what was wanted <em>and</em> to the
 * exact commercial statement it was created against.
 *
 * <p><b>The unique key on {@code intent_id} is the one-to-one boundary.</b> It is
 * a constraint rather than a service check on purpose: two simultaneous order
 * creations from one intent are settled by the database, and the loser is told
 * the order already exists instead of both succeeding.
 *
 * <p>No {@code @Version} and no updates — a link is written once and never
 * changes, so there is nothing to lock against.
 */
@Entity
@Table(name = "intent_order_link")
@Getter
@Setter
@NoArgsConstructor
public class IntentOrderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id", nullable = false)
    private Long intentId;

    @Column(name = "intent_acceptance_id", nullable = false)
    private Long intentAcceptanceId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false)
    private Instant createdAt;
}
