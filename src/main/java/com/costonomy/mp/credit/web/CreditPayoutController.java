package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditPayoutReadService;
import com.costonomy.mp.credit.web.dto.CreditPayoutDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The supplier's view of wallet repayments Mandi collected for the store and pays out through settlement. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditPayoutController {

    private final CreditPayoutReadService payouts;

    @GetMapping("/supplier-stores/{storeId}/credit/payouts")
    @Operation(summary = "Wallet repayments Mandi collected for the store, and where each is in settlement",
            description = """
                    Newest first. `status` is PENDING (not yet in a settlement), APPLIED or ALL (default). `from` and
                    `to` are India calendar days on the creation date, both inclusive. `page` counts from 0; `size`
                    defaults to 20, at most 100. Each row has the gross amount, the commission as it was snapshotted
                    when the repayment was made, the net, and the invoices it settled. `summary` is the whole store's
                    position whatever the filter: `pendingNet`, and `appliedNetThisMonth` for the current India month.
                    Needs `CREDIT_VIEW` or `SETTLEMENT_VIEW` on the store; anyone else gets a 404.
                    """)
    public ApiResponse<CreditPayoutDtos.PayoutListResponse> list(
            @PathVariable Long storeId,
            @RequestParam(required = false, defaultValue = "ALL") CreditPayoutReadService.StatusFilter status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(payouts.list(ActorContext.requireUserId(), storeId, status, from, to, page, size));
    }

    @GetMapping("/supplier-stores/{storeId}/credit/payouts/{payoutId}")
    @Operation(summary = "One payout",
            description = "The same row the list gives. A payout of another store is a 404.")
    public ApiResponse<CreditPayoutDtos.PayoutResponse> get(@PathVariable Long storeId, @PathVariable Long payoutId) {
        return ApiResponse.ok(payouts.get(ActorContext.requireUserId(), storeId, payoutId));
    }
}
