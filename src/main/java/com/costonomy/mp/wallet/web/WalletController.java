package com.costonomy.mp.wallet.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.service.WalletService;
import com.costonomy.mp.wallet.service.WalletWithdrawalService;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An outlet's prepaid balance. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class WalletController {

    private final WalletService wallets;
    private final WalletWithdrawalService withdrawals;
    private final RefundService refunds;
    private final IdempotencyService idempotency;
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
        var entries = wallets.statement(outletId, 10);
        // A withdrawal's row says the money left the wallet; whether it has reached
        // the card yet is its refund's status, read live rather than copied.
        var refundStatus = refunds.statusesOf(entries.stream()
                .filter(entry -> entry.getKind() == WalletEntryKind.WITHDRAWAL)
                .map(entry -> entry.getRefundId())
                .filter(Objects::nonNull)
                .toList());
        var recent = entries.stream()
                .map(entry -> new WalletDtos.EntryResponse(
                        entry.getId(), entry.getDirection(), entry.getKind().name(), entry.getAmount(),
                        entry.getBalanceAfter(), entry.getSupplierOrderId(), entry.getReason(),
                        entry.getKind() == WalletEntryKind.WITHDRAWAL && refundStatus.containsKey(entry.getRefundId())
                                ? refundStatus.get(entry.getRefundId()).name() : null,
                        entry.getCreatedAt()))
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

    @PostMapping("/outlets/{outletId}/wallet/withdraw")
    @Operation(
            summary = "Send wallet money back to the card or bank it came from",
            description = """
                    D-104. Only refund money can be withdrawn, and only to where it came
                    from: the amount is split across the payments it was refunded from,
                    oldest first, and each part is a provider refund against that payment.
                    A balance with no card behind it stays spendable on orders.

                    The balance drops at once; each part's refund then reaches the card
                    in the provider's usual time, and its status is on the wallet
                    statement. Needs `WALLET_WITHDRAW` and an `Idempotency-Key` — a
                    repeated request returns the first withdrawal, never a second.
                    """)
    public ApiResponse<WalletDtos.WithdrawalResponse> withdraw(
            @PathVariable Long outletId,
            @Valid @RequestBody WalletDtos.WithdrawRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.WALLET_WITHDRAW,
                ScopeType.OUTLET, outletId, "Outlet");

        try (var trace = TraceScope.of("outlet", outletId)) {
            return ApiResponse.ok(idempotency.execute(actorId, "wallet.withdraw", idempotencyKey,
                    Map.of("outletId", outletId, "amount", request.amount().toPlainString()),
                    WalletDtos.WithdrawalResponse.class,
                    () -> withdrawals.withdraw(actorId, outletId, request.amount(), idempotencyKey)));
        }
    }
}
