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
import com.costonomy.mp.wallet.service.WalletTopUpService;
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
    private final WalletTopUpService topUps;
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
            description = """
                    The balance, the last few movements behind it, and the limits the
                    wallet is held to (D-107).""")
    public ApiResponse<WalletDtos.WalletResponse> wallet(@PathVariable Long outletId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(walletResponse(outletId));
    }

    /** The wallet as the client sees it: balance, recent movements and limits. */
    private WalletDtos.WalletResponse walletResponse(Long outletId) {
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

        return new WalletDtos.WalletResponse(
                outletId, wallet.getBalance(), wallet.getCurrency(), wallet.getStatus(), recent, limits(outletId));
    }

    private WalletDtos.Limits limits(Long outletId) {
        var view = topUps.limitsFor(outletId);
        return new WalletDtos.Limits(view.maxBalance(), view.monthlyTopUpLimit(), view.addedThisMonth(),
                view.remainingThisMonth(), view.minTopUp(), view.maxTopUp());
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
                outletId, wallet.getBalance(), wallet.getCurrency(), wallet.getStatus(), List.of(),
                limits(outletId)));
    }

    @PostMapping("/outlets/{outletId}/wallet/top-ups")
    @Operation(
            summary = "Start adding money through Razorpay",
            description = """
                    D-107. Checks the amount against the wallet's limits, records the
                    top-up and opens a Razorpay order for exactly that amount, to be
                    captured the moment it is paid. Returns what the client needs to open
                    Razorpay's checkout. Nothing is credited until the payment is captured
                    and confirmed — by the client's confirm call, or failing that by a
                    background job.

                    Guarded by the same permission as placing an order. Needs an
                    `Idempotency-Key`: a repeated request returns the same top-up and never
                    opens a second Razorpay order.
                    """)
    public ApiResponse<WalletDtos.TopUpResponse> createTopUp(
            @PathVariable Long outletId,
            @Valid @RequestBody WalletDtos.CreateTopUpRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, outletId, "Outlet");

        try (var trace = TraceScope.of("outlet", outletId)) {
            return ApiResponse.ok(idempotency.execute(actorId, "wallet.top-up.create", idempotencyKey,
                    Map.of("outletId", outletId, "amount", request.amount().toPlainString()),
                    WalletDtos.TopUpResponse.class,
                    () -> {
                        var intent = topUps.create(actorId, outletId, request.amount(), idempotencyKey);
                        return new WalletDtos.TopUpResponse(intent.topUpId(), intent.razorpayOrderId(),
                                intent.keyId(), intent.amount(), intent.currency());
                    }));
        }
    }

    @PostMapping("/outlets/{outletId}/wallet/top-ups/{topUpId}/confirm")
    @Operation(
            summary = "Confirm a top-up after checkout",
            description = """
                    D-107. Send what Razorpay's checkout returned. We verify the signature,
                    then ask Razorpay what the payment is — whose order, how much, captured
                    or not — and credit the wallet once if it is this top-up's, for the
                    amount we stored, and captured. Returns the wallet.

                    Safe to repeat and to race: however many calls arrive, the wallet is
                    credited once. If the payment is not captured yet this answers
                    `TOP_UP_PROCESSING` (409) — the money is safe and is credited by a
                    background job when it clears, so look at the top-up again rather than
                    paying again. If crediting would take the wallet over a limit the
                    payment is returned to where it came from and this answers
                    `WALLET_LIMIT_EXCEEDED`.
                    """)
    public ApiResponse<WalletDtos.WalletResponse> confirmTopUp(
            @PathVariable Long outletId,
            @PathVariable Long topUpId,
            @Valid @RequestBody WalletDtos.ConfirmTopUpRequest request) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, outletId, "Outlet");

        topUps.confirm(outletId, topUpId, request.razorpayPaymentId(), request.razorpaySignature());
        return ApiResponse.ok(walletResponse(outletId));
    }

    @GetMapping("/outlets/{outletId}/wallet/top-ups/{topUpId}")
    @Operation(
            summary = "Where a top-up has got to",
            description = """
                    CREATED (waiting for the payment to be captured — including while a
                    payment that would break a limit is being returned), CREDITED,
                    REFUNDED, FAILED or EXPIRED. What to poll after a `TOP_UP_PROCESSING`.
                    """)
    public ApiResponse<WalletDtos.TopUpStatusResponse> topUpStatus(
            @PathVariable Long outletId, @PathVariable Long topUpId) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_SUBMIT,
                ScopeType.OUTLET, outletId, "Outlet");

        var topUp = topUps.get(outletId, topUpId);
        return ApiResponse.ok(new WalletDtos.TopUpStatusResponse(
                topUp.getId(), topUp.getOutletId(), topUp.getStatus().apiName(), topUp.getAmount(), "INR",
                topUp.getRazorpayOrderId(), topUp.getCreditedAt(), topUp.getCreatedAt()));
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
