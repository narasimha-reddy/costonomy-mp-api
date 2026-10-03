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
            String note,
            /**
             * D-116: Pending, Reading, Added, Reviewed, Unreadable or No bill needed, by the History's rules; empty
             * when the entry needs no bill (or is before the tracking start without one).
             */
            String bill,
            /** D-116: the shop on the bill (the review's, else as read), else empty. Text from paper: untrusted. */
            String shop,
            /** D-116: the bill's number (the review's, else as read), else empty. Text from paper: untrusted. */
            String billNumber) {

        /** A row with no bill columns. */
        public Line(Instant at, String description, String reference, WalletDirection direction, BigDecimal amount,
                    BigDecimal balanceAfter, String note) {
            this(at, description, reference, direction, amount, balanceAfter, note, "", "", "");
        }
    }
}
