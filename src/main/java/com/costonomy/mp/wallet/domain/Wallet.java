package com.costonomy.mp.wallet.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * An outlet's prepaid balance.
 *
 * <p><b>The balance is a column, not a sum of the ledger.</b> Funding an order
 * has to be one conditional statement — "take this much, but only if it is
 * there" — and a decision made by summing rows first is the read-then-write that
 * lets two orders spend the same rupee. {@code wallet_transaction} explains this
 * figure; it does not define it.
 */
@Entity
@Table(name = "wallet")
@Getter
@Setter
@NoArgsConstructor
public class Wallet extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    public boolean isUsable() {
        return "ACTIVE".equals(status);
    }
}
