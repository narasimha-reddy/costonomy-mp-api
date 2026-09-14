package com.costonomy.mp.credit.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class CreditPolicyDtos {

    private CreditPolicyDtos() {
    }

    public record UpdatePolicyRequest(
            @NotNull(message = "Say whether credit is offered") Boolean creditEnabled,
            @DecimalMin(value = "0.00") BigDecimal defaultCreditLimit,
            @Min(1) @Max(180) Integer defaultCreditPeriodDays,
            @Min(0) @Max(60) Integer defaultGracePeriodDays,
            @DecimalMin(value = "1.00") BigDecimal maxSingleOrderCredit,
            @DecimalMin(value = "0.00") BigDecimal maxOverdueAmount,
            Boolean autoSuspendEnabled) {
    }

    public record PolicyResponse(
            Long supplierStoreId,
            Boolean creditEnabled,
            BigDecimal defaultCreditLimit,
            Integer defaultCreditPeriodDays,
            Integer defaultGracePeriodDays,
            BigDecimal maxSingleOrderCredit,
            BigDecimal maxOverdueAmount,
            Boolean autoSuspendEnabled) {
    }
}
