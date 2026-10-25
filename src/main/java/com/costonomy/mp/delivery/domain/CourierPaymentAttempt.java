package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One try at paying a courier payable, with the key the rail was given (D-204). */
@Entity
@Table(name = "courier_payment_attempt")
@Getter
@Setter
@NoArgsConstructor
public class CourierPaymentAttempt extends BaseEntity {

    public static final String SENDING = "SENDING";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";
    public static final String AMBIGUOUS = "AMBIGUOUS";

    @Column(name = "payable_id", nullable = false)
    private Long payableId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "rail", nullable = false, length = 32)
    private String rail;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 16)
    private String status = SENDING;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;
}
