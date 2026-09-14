package com.costonomy.mp.common.idempotency;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One claimed idempotency key. See {@link IdempotencyService} for the protocol. */
@Entity
@Table(name = "idempotency_record")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord extends BaseEntity {

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "operation", nullable = false, length = 150)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /**
     * SHA-256 of the canonical request body. Detects key reuse with a different
     * payload.
     *
     * <p>{@code columnDefinition} is explicit because the column is {@code CHAR(64)},
     * not {@code VARCHAR(64)}: a hex SHA-256 is always exactly 64 characters, so
     * fixed width is both correct and marginally cheaper to index. Hibernate would
     * otherwise infer {@code varchar} and {@code ddl-auto=validate} would refuse
     * to start.
     */
    @Column(name = "request_hash", nullable = false, columnDefinition = "char(64)")
    private String requestHash;

    // @JdbcTypeCode(VARCHAR) is required on every enum column in this codebase.
    // Hibernate 6.4 maps @Enumerated(STRING) to a *native MySQL ENUM*, which
    // ddl-auto=validate then rejects against our VARCHAR(32) columns. Beyond
    // making the build pass, VARCHAR is what we want: doc 02 §6 specifies it for
    // every status column, and a native ENUM would turn "add a delivery failure
    // state" into an ALTER TABLE on a large table.
    // (The global `hibernate.type.preferred_enum_jdbc_type` setting that would
    // replace this per-field annotation only exists from Hibernate 6.5.)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 32)
    private State state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "json")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public enum State {
        /** Claimed; the operation is running. A concurrent retry must back off. */
        IN_PROGRESS,
        /** Finished; {@code responseBody} is replayable. */
        COMPLETED,
        /**
         * Finished by failing. The key is released so a genuine retry can run:
         * an authorisation that failed should be retryable with the same key,
         * otherwise the client has to invent a new one and loses the protection.
         */
        FAILED,
    }
}
