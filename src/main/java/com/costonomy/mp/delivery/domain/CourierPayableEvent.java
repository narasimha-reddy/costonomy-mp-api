package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Append-only history of a courier payable: what changed, from what, to what, by whom (D-204). */
@Entity
@Table(name = "courier_payable_event")
@Getter
@Setter
@NoArgsConstructor
public class CourierPayableEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payable_id", nullable = false)
    private Long payableId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "from_state", length = 16)
    private String fromState;

    @Column(name = "to_state", length = 16)
    private String toState;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
