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
import com.costonomy.mp.wallet.service.WalletHistoryService;
import com.costonomy.mp.wallet.service.WalletService;
import com.costonomy.mp.wallet.statement.StatementPeriod;
import com.costonomy.mp.wallet.statement.WalletStatementCsv;
import com.costonomy.mp.wallet.statement.WalletStatementPdf;
import com.costonomy.mp.wallet.statement.WalletStatementService;
import com.costonomy.mp.wallet.service.WalletTopUpService;
import com.costonomy.mp.wallet.service.WalletWithdrawalService;
import com.costonomy.mp.wallet.service.WalletBankPayoutService;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
    private final WalletBankPayoutService bankPayouts;
    private final RefundService refunds;
    private final IdempotencyService idempotency;
    private final AccessControlService accessControl;
    private final WalletHistoryService history;
    private final WalletStatementService statements;

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

    @GetMapping("/outlets/{outletId}/wallet/transactions")
    @Operation(
            summary = "The wallet's transaction history",
            description = """
                    D-108. Every movement of the balance, newest first, a page at a time.
                    All parameters are optional: `months` (comma list of `yyyy-MM`, Asia/Kolkata
                    months), `kinds` (comma list of entry kinds), `statuses` (comma list of
                    COMPLETED, IN_PROGRESS, FAILED, RETURNED), `cursor` (the previous page's
                    `nextCursor`) and `size` (default 20, at most 100).

                    A withdrawal whose refund has not completed is IN_PROGRESS; one the provider
                    would not send and that went back into the wallet is RETURNED, and the credit
                    that returned it is its own WITHDRAWAL_REVERSAL entry. A top-up that was
                    paid and then returned to the customer's bank appears as a TOP_UP with status
                    RETURNED and no `balanceAfter`: it never changed the balance. `monthTotals`
                    are the ledger's added and spent for the requested months (or, with none
                    requested, the months on this page), whatever the kind and status filters say.
                    `availableMonths` lists every month with any history.

                    D-116: each item has `bill`, null or `{"status": ...}` with PENDING, READING, ADDED,
                    REVIEWED or UNREADABLE, on order and QuickScan payments from the wallet (PENDING only from
                    the tracking start, INVOICE_TRACKING_START; 'No bill needed' shows null). `bills` (comma list
                    of those five) keeps only entries whose bill status is one of them; it composes with the other
                    filters and the cursor, and leaves `monthTotals` unfiltered. `billSummary` {pending, reading,
                    unreadable} counts all months since the tracking start, filters ignored, and each
                    `monthTotals` item has `billsPending`.
                    Same permission as reading the wallet.""")
    public ApiResponse<WalletDtos.HistoryResponse> transactions(
            @PathVariable Long outletId,
            @RequestParam(required = false) String months,
            @RequestParam(required = false) String kinds,
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) String bills,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(history.history(outletId,
                WalletHistoryService.parseFilter(months, kinds, statuses, bills), cursor,
                WalletHistoryService.parseSize(size)));
    }

    @GetMapping("/outlets/{outletId}/wallet/statement")
    @Operation(
            summary = "Download a wallet statement",
            description = """
                    D-108. A file, not JSON: the outlet, the period, opening and closing balance,
                    what was added and spent, and every ledger entry oldest first.

                    Pick the days with `range` (LAST_30, LAST_90, LAST_180, LAST_365, or CUSTOM
                    with `from` and `to` as `yyyy-MM-dd`, inclusive, at most 366 days, not in
                    the future) or with `financialYear` (`2025-26`, 1 April to 31 March; the
                    current year runs to today). `format` is PDF or CSV.

                    Refuses with 422 `STATEMENT_TOO_LARGE` beyond 20,000 entries. Same permission
                    as reading the wallet.""")
    public ResponseEntity<byte[]> statement(
            @PathVariable Long outletId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String financialYear,
            @RequestParam(required = false) String format) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        boolean pdf;
        if ("PDF".equalsIgnoreCase(format)) {
            pdf = true;
        } else if ("CSV".equalsIgnoreCase(format)) {
            pdf = false;
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "format must be PDF or CSV.");
        }
        var period = StatementPeriod.resolve(range, from, to, financialYear,
                LocalDate.now(StatementPeriod.ZONE));

        var statement = statements.build(outletId, period);
        byte[] body = pdf ? WalletStatementPdf.render(statement) : WalletStatementCsv.render(statement);
        String filename = "costonomy-wallet-statement-%s-to-%s.%s".formatted(
                period.from(), period.to(), pdf ? "pdf" : "csv");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
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
        // The bill chip, as the History list shows it (D-116): one query for all of them.
        var bills = history.billsOf(wallet.getId(), entries.stream().map(entry -> entry.getId()).toList());
        var recent = entries.stream()
                .map(entry -> new WalletDtos.EntryResponse(
                        entry.getId(), entry.getDirection(), entry.getKind().name(), entry.getAmount(),
                        entry.getBalanceAfter(), entry.getSupplierOrderId(), entry.getReason(),
                        entry.getKind() == WalletEntryKind.WITHDRAWAL && refundStatus.containsKey(entry.getRefundId())
                                ? refundStatus.get(entry.getRefundId()).name() : null,
                        entry.getCreatedAt(), bills.get(entry.getId())))
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

    @PostMapping("/outlets/{outletId}/wallet/bank-payout")
    @Operation(
            summary = "Transfer wallet balance to verified restaurant bank account",
            description = """
                    Enables payout of general wallet balances (including catch-weight refunds,
                    doorstep rejections, and direct top-ups) to the restaurant's bank account via IMPS/NEFT.
                    Needs `WALLET_WITHDRAW` and an `Idempotency-Key`.
                    """)
    public ApiResponse<WalletDtos.BankPayoutResponse> bankPayout(
            @PathVariable Long outletId,
            @Valid @RequestBody WalletDtos.BankPayoutRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.WALLET_WITHDRAW,
                ScopeType.OUTLET, outletId, "Outlet");

        try (var trace = TraceScope.of("outlet", outletId)) {
            return ApiResponse.ok(idempotency.execute(actorId, "wallet.bank-payout", idempotencyKey,
                    Map.of("outletId", outletId, "amount", request.amount().toPlainString(),
                            "account", request.accountNumber()),
                    WalletDtos.BankPayoutResponse.class,
                    () -> bankPayouts.initiatePayout(actorId, outletId, request, idempotencyKey)));
        }
    }
}
