package com.costonomy.mp.quickscan.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.quickscan.repository.QuickScanPaymentRepository;
import com.costonomy.mp.quickscan.service.QuickScanService;
import com.costonomy.mp.quickscan.web.dto.QuickScanDtos;
import com.costonomy.mp.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * QuickScan wallet payments — pay a UPI merchant from the outlet's wallet.
 * D-106, part one.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "QuickScan")
public class QuickScanController {

    private static final int RECENT_LIMIT = 50;

    private final QuickScanService quickScan;
    private final QuickScanPaymentRepository payments;
    private final WalletService wallet;
    private final IdempotencyService idempotency;
    private final AccessControlService accessControl;

    @GetMapping("/outlets/{outletId}/quickscan/config")
    @Operation(summary = "What the QuickScan screen needs before showing the scanner")
    public ApiResponse<QuickScanDtos.ConfigResponse> config(@PathVariable Long outletId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        boolean enabled = quickScan.isEnabled();
        var methods = List.of(
                new QuickScanDtos.MethodAvailability("WALLET", enabled,
                        enabled ? null : "QuickScan isn't available yet."),
                new QuickScanDtos.MethodAvailability("UPI", false, "UPI isn't enabled yet."));

        return ApiResponse.ok(new QuickScanDtos.ConfigResponse(
                enabled, quickScan.maxAmount(), quickScan.fee(), wallet.balanceOf(outletId), methods));
    }

    @PostMapping("/outlets/{outletId}/quickscan/payments")
    @Operation(
            summary = "Pay a UPI merchant from the wallet",
            description = """
                    Debits the wallet at once and sends the payout in the background; the
                    common case finishes within this request, and a job is the safety net
                    for what does not. Needs `QUICKSCAN_PAY` and an `Idempotency-Key`.

                    `method` must be `WALLET` — UPI-direct is not built yet.
                    """)
    public ApiResponse<QuickScanDtos.PaymentResponse> pay(
            @PathVariable Long outletId,
            @Valid @RequestBody QuickScanDtos.PayRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.QUICKSCAN_PAY, ScopeType.OUTLET, outletId, "Outlet");

        if (!"WALLET".equalsIgnoreCase(request.method())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "UPI isn't enabled yet.");
        }

        try (var trace = TraceScope.of("outlet", outletId)) {
            return ApiResponse.ok(idempotency.execute(actorId, "quickscan.pay", idempotencyKey,
                    Map.of("outletId", outletId, "vpa", String.valueOf(request.payeeVpa()),
                            "amount", String.valueOf(request.amount()), "note", String.valueOf(request.note())),
                    QuickScanDtos.PaymentResponse.class,
                    () -> quickScan.payFromWallet(actorId, outletId,
                            new QuickScanService.PayRequest(
                                    request.payeeVpa(), request.payeeName(), request.amount(), request.note()),
                            idempotencyKey)));
        }
    }

    @GetMapping("/outlets/{outletId}/quickscan/payments")
    @Operation(summary = "This outlet's QuickScan payments, newest first")
    public ApiResponse<List<QuickScanDtos.PaymentResponse>> forOutlet(@PathVariable Long outletId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");

        return ApiResponse.ok(payments.findByOutletIdOrderByCreatedAtDesc(
                        outletId, PageRequest.of(0, RECENT_LIMIT))
                .stream().map(quickScan::toResponse).toList());
    }

    @GetMapping("/quickscan/payments/{id}")
    @Operation(summary = "Get a QuickScan payment")
    public ApiResponse<QuickScanDtos.PaymentResponse> get(@PathVariable Long id) {
        var payment = payments.findById(id).orElseThrow(() -> new NotFoundException("QuickScan payment", id));
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, payment.getOutletId(), "QuickScan payment");

        return ApiResponse.ok(quickScan.toResponse(payment));
    }
}
