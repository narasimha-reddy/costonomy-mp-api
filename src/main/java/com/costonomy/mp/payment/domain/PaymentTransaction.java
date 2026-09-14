package com.costonomy.mp.payment.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement of money. Append-only.
 *
 * <p>The payment row holds current balances; this holds how they got there. A
 * settlement or a dispute months later is reconstructed from these, so nothing is
 * ever updated — a correction is another row. No {@code version} column for the
 * same reason.
 */
@Entity
@Table(name = "payment_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    /** AUTHORIZE, CAPTURE, RELEASE or REFUND. */
    @Column(name = "transaction_type", nullable = false, length = 32)
    private String transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    /** The key sent to the provider, so their side is idempotent too. */
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
