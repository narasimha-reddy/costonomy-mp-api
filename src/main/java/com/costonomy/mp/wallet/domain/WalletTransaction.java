package com.costonomy.mp.wallet.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/** One movement of an outlet's balance. Append-only. */
@Entity
@Table(name = "wallet_transaction")
@Getter
@Setter
@NoArgsConstructor
public class WalletTransaction extends BaseEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** The order this paid for, or null for a top-up. */
    @Column(name = "supplier_order_id")
    private Long supplierOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "direction", nullable = false, length = 16)
    private WalletDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "reason", length = 200)
    private String reason;
}
