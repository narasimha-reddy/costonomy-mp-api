package com.costonomy.mp.wallet.invoice.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 'No bill needed' on one wallet payment (D-116). Removed on undo and when a bill is added. */
@Entity
@Table(name = "wallet_entry_invoice_waiver")
@Getter
@Setter
@NoArgsConstructor
public class WalletEntryInvoiceWaiver extends BaseEntity {

    @Column(name = "wallet_transaction_id", nullable = false, updatable = false)
    private Long walletTransactionId;

    @Column(name = "outlet_id", nullable = false, updatable = false)
    private Long outletId;

    @Column(name = "waived_by")
    private Long waivedBy;

    @Column(name = "waived_at", nullable = false)
    private Instant waivedAt;
}
