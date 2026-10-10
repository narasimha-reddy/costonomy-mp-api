package com.costonomy.mp.wallet.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.wallet.service.WalletEntryDetailService;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One wallet entry in full, for the transaction-details page (D-112). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class WalletTransactionDetailController {

    private final AccessControlService accessControl;
    private final WalletEntryDetailService details;

    @GetMapping("/outlets/{outletId}/wallet/transactions/{entryId}")
    @Operation(
            summary = "One wallet entry, for the transaction-details page",
            description = """
                    The fields the history list returns for an entry, plus `transactionId` (our own id: the
                    ledger entry id), the other side (`counterpartyName`, `counterpartyDetail`: a QuickScan
                    payee's VPA is masked), the real references we hold (`references`, in display order), and
                    `actions`. `actions.canPayAgain` is true only for a QuickScan payment whose payee VPA is
                    still valid, and only then is the full `actions.payeeVpa` returned. `entryId` is the
                    ledger id, or the list's `key` ("L184"). An entry of another outlet, a returned top-up
                    (which has no ledger entry) or an unknown id is a 404. Same permission as the history.""")
    public ApiResponse<WalletDtos.TransactionDetail> transaction(
            @PathVariable Long outletId, @PathVariable String entryId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(details.detail(outletId, entryId));
    }
}
