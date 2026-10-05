package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentSource;
import com.costonomy.mp.credit.domain.CreditRepaymentStatus;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
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
 * A restaurant repays a credit invoice from its own wallet (D-123).
 *
 * <p>Credit stays the supplier's: Mandi never funds or guarantees it. This is the one repayment a restaurant
 * starts itself, because Mandi itself sees the money leave the restaurant's wallet (D-121, D-122).
 *
 * <p>It is a plain bean around a {@link TransactionTemplate}, not a {@code @Transactional} method, because the
 * work runs inside {@code IdempotencyService.execute} (D-016) and QuickScan's money-moving transaction is built
 * the same way. Everything that moves money is one transaction, in this order:
 * <ol>
 *   <li>the wallet is locked, and refused if it is on hold;</li>
 *   <li>the target invoices are locked {@code FOR UPDATE}, in ascending id order, this agreement's open ones only;</li>
 *   <li>the amount is allocated oldest due date first, and refused as {@link ErrorCode#CREDIT_OVERPAYMENT} if it is
 *       more than is owed, or as {@link ErrorCode#WALLET_INSUFFICIENT_BALANCE} if the wallet cannot cover it;</li>
 *   <li>the {@code credit_repayment} row is inserted and flushed;</li>
 *   <li>the wallet is debited, and the row is linked to the ledger entry;</li>
 *   <li>the {@code credit_repayment_payout} row (D-126): what Mandi now owes the supplier, with the commission snapshot;</li>
 *   <li>each allocation goes through {@link CreditInvoiceService#applyPayment}, the same method a payment the
 *       supplier records goes through;</li>
 *   <li>the audit row, and one {@code CreditRepaymentReceived} event for the supplier.</li>
 * </ol>
 * The wallet comes first and the invoices second, always: every taker locks in that order, so two repayments
 * cannot wait on each other. Both refusals come before anything is written.
 *
 * <p><b>Why the flag stays off.</b> The payout to the supplier now exists (D-126): a pending row, applied by the
 * next settlement. The flag stays off until the business decision on commission for wallet repayments is confirmed.
 * It is on only in tests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditWalletRepaymentService {

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID, CreditInvoiceStatus.WRITTEN_OFF);

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditRepaymentRepository repayments;
    private final CreditInvoiceService invoiceService;
    private final CreditExposureStore exposure;
    private final CreditRepaymentPayoutWriter payoutWriter;
    private final CreditDirectory directory;
    private final WalletService wallet;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
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
                    Map.of("agreementId", agreementId, "amount", amount.toPlainString(),
                            "invoiceIds", ids == null ? List.of() : ids),
                    CreditDtos.WalletRepaymentResponse.class,
                    () -> txTemplate.execute(status -> work(actorId, agreementId, outletId, storeId, amount, ids,
                            idempotencyKey)));
        }
    }

    private CreditDtos.WalletRepaymentResponse work(Long actorId, Long agreementId, Long outletId, Long storeId,
                                                    BigDecimal amount, List<Long> ids, String idempotencyKey) {
        // a. The wallet first, like QuickScan: nothing else is read before this lock is held.
        var walletRow = wallet.lock(outletId);
        if (!walletRow.isUsable()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This wallet is on hold. Please contact support.");
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

        // d. The repayment row, flushed so its id exists for the wallet reference.
        var repayment = new CreditRepayment();
        repayment.setCreditAgreementId(agreementId);
        repayment.setOutletId(outletId);
        repayment.setSupplierStoreId(storeId);
        repayment.setAmount(amount);
        repayment.setSource(CreditRepaymentSource.WALLET);
        repayment.setStatus(CreditRepaymentStatus.COMPLETED);
        repayment.setIdempotencyKey("wallet:" + outletId + ":" + idempotencyKey);
        repayment.setCreatedBy(actorId);
        repayments.saveAndFlush(repayment);
        Long repaymentId = repayment.getId();

        // e. The debit (its own conditional update stays the last line of defence), then the link to its entry.
        // The debit clears the persistence context, so the row is read again before it is changed.
        var supplierStore = directory.store(storeId);
        Long entryId = wallet.debitCreditRepayment(outletId, repaymentId, amount,
                supplierStore == null ? null : supplierStore.storeName());
        var linked = repayments.findById(repaymentId).orElseThrow();
        linked.setWalletTransactionId(entryId);
        repayments.saveAndFlush(linked);

        // e2. What Mandi now owes the supplier (D-126). Right after the debit, because the debit is what put the
        // restaurant's money in Mandi's hands; in this same transaction, so the payout exists if and only if the
        // debit does, and a refusal anywhere after it rolls back both.
        payoutWriter.record(linked);

        // f. Each allocation through the one shared method, once per invoice.
        String reference = "credit-repayment-" + repaymentId;
        Instant now = Instant.now();
        var allocations = new ArrayList<CreditDtos.WalletRepaymentAllocation>();
        for (var step : plan) {
            CreditInvoice invoice = step.getKey();
            BigDecimal part = step.getValue();
            invoiceService.applyPayment(invoice, part, "WALLET", reference, null, now, actorId,
                    "wallet-repayment:" + repaymentId + ":" + invoice.getId(),
                    CreditPaymentSource.WALLET, repaymentId);
            allocations.add(new CreditDtos.WalletRepaymentAllocation(
                    invoice.getId(), invoice.getInvoiceNumber(), part, invoice.getStatus()));
        }

        // g. Audit, and one event for the supplier. Not CreditRepaymentRecorded: that text says the supplier
        // recorded the payment, and here the restaurant did it itself and has seen the result on screen.
        auditService.record(actorId, null, "CREDIT_REPAYMENT_FROM_WALLET", "CREDIT_REPAYMENT", repaymentId,
                null, CreditRepaymentStatus.COMPLETED.name(), amount.toPlainString(), "API");
        var outlet = directory.outlet(outletId);
        outbox.publish(CreditEvents.REPAYMENT_RECEIVED, "CREDIT_AGREEMENT", agreementId,
                Map.of("creditAgreementId", agreementId,
                        "outletId", outletId,
                        "supplierStoreId", storeId,
                        "restaurantName", outlet == null || outlet.restaurantName() == null ? "" : outlet.restaurantName(),
                        "amount", amount.toPlainString(),
                        "repaymentId", repaymentId),
                actorId);
        log.info("Credit repayment {} of {} from the wallet of outlet {} on agreement {}",
                repaymentId, Rupees.of(amount), outletId, agreementId);

        // Read after the writes, from the row: the exposure moved by SQL, and the agreement may have been reinstated.
        var dues = invoiceService.duesFor(agreementId);
        var after = agreements.findById(agreementId).orElseThrow();
        return new CreditDtos.WalletRepaymentResponse(repaymentId, amount, entryId, wallet.balanceOf(outletId),
                allocations,
                new CreditDtos.RepaymentAgreementState(dues.due(), dues.overdue(),
                        exposure.read(agreementId).available(), after.getStatus()));
    }
}
