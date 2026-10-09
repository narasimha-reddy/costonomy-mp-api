package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A restaurant repays a credit invoice from its own wallet (D-153).
 *
 * <p>Credit stays the supplier's: Mandi never funds or guarantees it. This is the one repayment a restaurant
 * starts itself, because Mandi itself sees the money leave the restaurant's wallet (D-151, D-152).
 *
 * <p>It is a plain bean around a {@link TransactionTemplate}, not a {@code @Transactional} method, because the
 * work runs inside {@code IdempotencyService.execute} (D-016) and QuickScan's money-moving transaction is built
 * the same way. Everything that moves money is one transaction, in this order:
 * <ol>
 *   <li>the wallet is locked, and refused if it is on hold;</li>
 *   <li>the target invoices are locked {@code FOR UPDATE}, in ascending id order, this agreement's open ones only;</li>
 *   <li>the amount is allocated oldest due date first, and refused as {@link ErrorCode#CREDIT_OVERPAYMENT} if it is
 *       more than is owed, or as {@link ErrorCode#WALLET_INSUFFICIENT_BALANCE} if the wallet cannot cover it;</li>
 *   <li>steps 4 to 7 below are {@link CreditRepaymentApplier#apply}, shared with paying whole invoices (D-196):</li>
 *   <li>the {@code credit_repayment} row is inserted and flushed;</li>
 *   <li>the wallet is debited, and the row is linked to the ledger entry;</li>
 *   <li>the {@code credit_repayment_payout} row (D-156): what Mandi now owes the supplier, with the commission snapshot;</li>
 *   <li>each allocation goes through {@link CreditInvoiceService#applyPayment}, the same method a payment the
 *       supplier records goes through;</li>
 *   <li>the audit row, and one {@code CreditRepaymentReceived} event for the supplier.</li>
 * </ol>
 * The wallet comes first and the invoices second, always: every taker locks in that order, so two repayments
 * cannot wait on each other. Both refusals come before anything is written.
 *
 * <p><b>Why the flag stays off.</b> The payout to the supplier now exists (D-156): a pending row, applied by the
 * next settlement. The flag stays off until the business decision on commission for wallet repayments is confirmed.
 * It is on only in tests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditWalletRepaymentService {

    static final BigDecimal MIN_AMOUNT = new BigDecimal("1.00");
    static final String MIN_AMOUNT_MESSAGE = "Enter at least ₹1, or the exact remaining amount";

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID, CreditInvoiceStatus.WRITTEN_OFF);

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditRepaymentApplier applier;
    private final CreditInvoiceService invoiceService;
    private final CreditExposureStore exposure;
    private final WalletService wallet;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final TransactionTemplate txTemplate;

    @Value("${costonomy.mp.credit.wallet-repay.enabled:false}")
    private boolean enabled;

    public CreditDtos.WalletRepaymentResponse repay(Long actorId, Long agreementId, BigDecimal requestedAmount,
                                                    List<Long> invoiceIds, String idempotencyKey) {
        if (!enabled) {
            // Before anything is read or claimed: a refusal here leaves no trace, not even an idempotency key.
            throw new BusinessException(ErrorCode.FORBIDDEN, "Repaying credit from your wallet isn't available yet.");
        }
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        // Another outlet's agreement is "not found", like every restaurant-side credit endpoint.
        accessControl.requireScoped(actorId, Permissions.CREDIT_REPAY, ScopeType.OUTLET,
                agreement.getOutletId(), "CreditAgreement");

        BigDecimal amount = requestedAmount.setScale(2);
        List<Long> ids = invoiceIds == null ? null : invoiceIds.stream().sorted().toList();
        Long outletId = agreement.getOutletId();
        Long storeId = agreement.getSupplierStoreId();

        try (var trace = TraceScope.of("credit-agreement", agreementId)) {
            return idempotency.execute(actorId, "credit.wallet-repay", idempotencyKey,
                    payload(agreementId, amount, ids),
                    CreditDtos.WalletRepaymentResponse.class,
                    () -> txTemplate.execute(status -> work(actorId, agreementId, outletId, storeId, amount, ids,
                            idempotencyKey)));
        }
    }

    /**
     * What identifies "the same request" for the idempotency hash. The amount is already scaled to 2 places and written
     * as plain text, so 1e3, 1000, 1000.0 and "1000.00" are one request; the ids are sorted by the caller.
     */
    public static Map<String, Object> payload(Long agreementId, BigDecimal amount, List<Long> ids) {
        return Map.of("agreementId", agreementId, "amount", amount.toPlainString(),
                "invoiceIds", ids == null ? List.of() : ids);
    }

    private CreditDtos.WalletRepaymentResponse work(Long actorId, Long agreementId, Long outletId, Long storeId,
                                                    BigDecimal amount, List<Long> ids, String idempotencyKey) {
        // a. The wallet first, like QuickScan: nothing else is read before this lock is held.
        var walletRow = wallet.lock(outletId);
        if (!walletRow.isUsable()) {
            throw new BusinessException(ErrorCode.WALLET_ON_HOLD);
        }

        // b. The invoices, ascending id. One that is not this agreement's, or not open, is absent from the result,
        // and is "not found" whoever's it is, so no other tenant's invoice is ever confirmed.
        List<CreditInvoice> locked = ids == null
                ? invoices.lockOpenOfAgreement(agreementId, SETTLED)
                : invoices.lockOpenOfAgreementWithIds(agreementId, SETTLED, ids);
        if (ids != null && locked.size() != ids.size()) {
            var found = locked.stream().map(CreditInvoice::getId).toList();
            throw new NotFoundException("CreditInvoice", ids.stream().filter(id -> !found.contains(id)).findFirst().orElse(null));
        }

        // c. Allocation, oldest due date first (then id). Whole paise: each takes min(outstanding, remaining).
        BigDecimal owed = locked.stream().map(CreditInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(owed) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_OVERPAYMENT,
                    "That's more than the ₹%s you owe.".formatted(Rupees.of(owed)),
                    Map.of("outstanding", owed));
        }
        // Below ₹1 only when it clears exactly what it targets (D-160): otherwise a part payment could leave a
        // residual the restaurant can never pay. Still before anything moves.
        if (amount.compareTo(MIN_AMOUNT) < 0 && amount.compareTo(owed) != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, MIN_AMOUNT_MESSAGE);
        }
        BigDecimal balance = walletRow.getBalance();
        if (amount.compareTo(balance) > 0) {
            BigDecimal shortBy = amount.subtract(balance);
            throw new BusinessException(ErrorCode.WALLET_INSUFFICIENT_BALANCE,
                    "You're ₹%s short.".formatted(Rupees.of(shortBy)),
                    Map.of("shortBy", shortBy, "balance", balance));
        }
        var oldestFirst = new ArrayList<>(locked);
        oldestFirst.sort(Comparator.comparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId));
        var plan = new ArrayList<Map.Entry<CreditInvoice, BigDecimal>>();
        BigDecimal remaining = amount;
        for (CreditInvoice invoice : oldestFirst) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal part = invoice.outstanding().min(remaining);
            plan.add(Map.entry(invoice, part));
            remaining = remaining.subtract(part);
        }

        // d-g. The repayment, the debit, the payout, each invoice, the audit and the supplier's event: one shared
        // method, the same one the new "pay whole invoices" paths use (D-196).
        var lines = plan.stream().map(step -> new CreditRepaymentApplier.Line(step.getKey(), step.getValue())).toList();
        var applied = applier.apply(actorId, agreementId, outletId, storeId, lines, CreditRepaymentApplier.Funding.wallet(
                "wallet:" + outletId + ":" + idempotencyKey, "wallet-repayment:", null), Instant.now());
        Long repaymentId = applied.repaymentId();
        Long entryId = applied.walletEntryId();
        var allocations = applied.allocations();

        // Read after the writes, from the row: the exposure moved by SQL, and the agreement may have been reinstated.
        var dues = invoiceService.duesFor(agreementId);
        var after = agreements.findById(agreementId).orElseThrow();
        return new CreditDtos.WalletRepaymentResponse(repaymentId, amount, entryId, wallet.balanceOf(outletId),
                allocations,
                new CreditDtos.RepaymentAgreementState(dues.due(), dues.overdue(),
                        exposure.read(agreementId).available(), after.getStatus()));
    }
}
