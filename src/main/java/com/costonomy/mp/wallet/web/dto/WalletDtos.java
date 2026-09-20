package com.costonomy.mp.wallet.web.dto;

import com.costonomy.mp.wallet.domain.WalletDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WalletDtos {

    private WalletDtos() {
    }

    /** What an outlet has to spend, and why. */
    public record WalletResponse(
            Long outletId,
            BigDecimal balance,
            String currency,
            String status,
            List<EntryResponse> recent) {
    }

    public record EntryResponse(
            Long id,
            WalletDirection direction,
            BigDecimal amount,
            /** What the balance became, so a statement reads without arithmetic. */
            BigDecimal balanceAfter,
            Long supplierOrderId,
            String reason,
            Instant at) {
    }

    public record TopUpRequest(
            @NotNull @Positive BigDecimal amount,
            @Size(max = 200) String reason) {
    }
}
