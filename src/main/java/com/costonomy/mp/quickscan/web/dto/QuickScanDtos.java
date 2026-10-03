package com.costonomy.mp.quickscan.web.dto;

import com.costonomy.mp.quickscan.domain.QuickScanStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class QuickScanDtos {

    private QuickScanDtos() {
    }

    /** Pay a UPI merchant from the wallet. {@code method} must be {@code WALLET} today. */
    public record PayRequest(
            @NotBlank @Size(max = 320) String payeeVpa,
            @Size(max = 100) String payeeName,
            @NotNull BigDecimal amount,
            @Size(max = 200) String note,
            @NotBlank String method) {
    }

    public record PaymentResponse(
            Long id,
            Long outletId,
            String payeeVpa,
            String payeeName,
            String note,
            BigDecimal amount,
            BigDecimal fee,
            /** {@code amount} plus {@code fee} — what actually left the wallet. */
            BigDecimal total,
            String method,
            QuickScanStatus status,
            String failureReason,
            Instant createdAt,
            Instant paidAt) {
    }

    /** What the pay screen needs before showing the scanner. */
    public record ConfigResponse(
            boolean enabled,
            BigDecimal maxAmount,
            BigDecimal fee,
            BigDecimal walletBalance,
            List<MethodAvailability> methods) {
    }

    public record MethodAvailability(String method, boolean available, String reason) {
    }
}
