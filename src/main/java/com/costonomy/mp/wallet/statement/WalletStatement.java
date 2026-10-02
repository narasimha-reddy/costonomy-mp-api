package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.wallet.domain.WalletDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything a statement file says, worked out once and checked, so the CSV and the PDF
 * cannot disagree with each other about a figure (D-108). Rupee amounts, exact.
 */
public record WalletStatement(
        String outletName,
        StatementPeriod period,
        Instant generatedAt,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalAdded,
        BigDecimal totalSpent,
        List<Line> lines) {

    /** One ledger row, oldest first in {@link #lines}. */
    public record Line(
            Instant at,
            String description,
            /** The order number if the movement was about an order, else empty. */
            String reference,
            WalletDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            /** The ledger's own note; free text, so a file must treat it as untrusted. */
            String note) {
    }
}
