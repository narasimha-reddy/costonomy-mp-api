package com.costonomy.mp.wallet.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.wallet.service.WalletService;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** An outlet's prepaid balance. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class WalletController {

    private final WalletService wallets;
    private final AccessControlService accessControl;

    /**
     * Which payment provider is live. Field-injected because Lombok's constructor
     * would not carry the annotation.
     */
    @Value("${costonomy.mp.providers.payment:MOCK}")
    private String paymentProvider;

    @GetMapping("/outlets/{outletId}/wallet")
    @Operation(
            summary = "What this outlet has to spend",
            description = "The balance and the last few movements behind it.")
    public ApiResponse<WalletDtos.WalletResponse> wallet(@PathVariable Long outletId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        var wallet = wallets.forOutlet(outletId);
        var recent = wallets.statement(outletId, 10).stream()
                .map(entry -> new WalletDtos.EntryResponse(
                        entry.getId(), entry.getDirection(), entry.getAmount(),
                        entry.getBalanceAfter(), entry.getSupplierOrderId(),
                        entry.getReason(), entry.getCreatedAt()))
                .toList();

        return ApiResponse.ok(new WalletDtos.WalletResponse(
                outletId, wallet.getBalance(), wallet.getCurrency(), wallet.getStatus(), recent));
    }

    @PostMapping("/outlets/{outletId}/wallet/top-up")
    @Operation(
            summary = "Put money in",
            description = """
                    **Stands in for a funding rail that does not exist yet.** Real money
                    reaches a wallet through a gateway, a bank transfer or an ops
                    adjustment, each with a reconciliation story this has none of. It is
                    here so the wallet can be used end to end.

                    Guarded by the same permission as placing an order: whoever may spend
                    this outlet's money may put money in it.

                    **Refused unless payments run on the mock provider.** It credits a
                    balance with no money behind it, so against a real provider it would
                    let anyone who can order fund their own orders for free (D-099).
                    """)
    public ApiResponse<WalletDtos.WalletResponse> topUp(
            @PathVariable Long outletId,
            @Valid @RequestBody WalletDtos.TopUpRequest request) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, outletId, "Outlet");

        // The same gate as mock checkout simulation: a stand-in for a funding
        // rail is only safe where no real money is in play. The real rail (a
        // virtual account credit, say) will call WalletService.topUp itself.
        if (!"MOCK".equalsIgnoreCase(paymentProvider)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Adding money this way isn't available. Top-ups need a real payment.");
        }

        var wallet = wallets.topUp(outletId, request.amount(),
                request.reason() == null ? "Top-up" : request.reason());

        return ApiResponse.ok(new WalletDtos.WalletResponse(
                outletId, wallet.getBalance(), wallet.getCurrency(), wallet.getStatus(), List.of()));
    }
}
