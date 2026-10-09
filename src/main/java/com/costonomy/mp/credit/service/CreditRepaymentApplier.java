package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentSource;
import com.costonomy.mp.credit.domain.CreditRepaymentStatus;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a restaurant's money repays one credit agreement (D-153, D-196).
 *
 * <p>Extracted from the wallet repayment so the wallet repayment, the new wallet method of paying whole invoices, and
 * the online capture all move credit money through the same steps: the {@code credit_repayment} row, the wallet debit
 * (wallet funding only), the payout owed to the supplier with its commission snapshot (D-156), each invoice through
 * {@link CreditInvoiceService#applyPayment}, the audit row, and the supplier's event.
 *
 * <p>A plain bean, <b>not transactional</b>: it runs inside the caller's transaction, after the caller has taken
 * its locks (attempt, wallet, invoices ascending id, then agreements ascending id) and decided what to pay. It checks
 * nothing about balances or outstanding amounts; that is the caller's, under those locks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditRepaymentApplier {

    private final CreditRepaymentRepository repayments;
    private final CreditInvoiceService invoiceService;
    private final CreditRepaymentPayoutWriter payoutWriter;
    private final CreditDirectory directory;
    private final WalletService wallet;
    private final AuditService auditService;
    private final OutboxService outbox;

    /** One invoice and what it is paid with now. Whole invoices for credit pay; any part for the old wallet endpoint. */
    public record Line(CreditInvoice invoice, BigDecimal amount) { }

    /**
     * How the money came in.
     *
     * @param repaymentKey     credit_repayment.idempotency_key
     * @param paymentKeyPrefix credit_payment.idempotency_key = prefix + repaymentId + ":" + invoiceId
     * @param method           credit_payment.method: "WALLET", or UPI|CARD|NETBANKING|PROVIDER_WALLET|EMI|OTHER
     * @param reference        credit_payment.reference; null means "credit-repayment-{repaymentId}"
     * @param methodLabel      for the supplier's notification: "wallet", "UPI", "card", "net banking", "online"
     */
    public record Funding(CreditRepaymentSource source, CreditPaymentSource paymentSource, String method,
                          String reference, String repaymentKey, String paymentKeyPrefix, Long attemptId,
                          String auditAction, String methodLabel) {

        public static Funding wallet(String repaymentKey, String paymentKeyPrefix, Long attemptId) {
            return new Funding(CreditRepaymentSource.WALLET, CreditPaymentSource.WALLET, "WALLET", null,
                    repaymentKey, paymentKeyPrefix, attemptId, "CREDIT_REPAYMENT_FROM_WALLET", "wallet");
        }

        public static Funding online(Long attemptId, Long agreementId, String method, String razorpayPaymentId,
                                     String methodLabel) {
            return new Funding(CreditRepaymentSource.ONLINE, CreditPaymentSource.ONLINE, method, razorpayPaymentId,
                    "online:" + attemptId + ":" + agreementId, "online-repayment:", attemptId,
                    "CREDIT_REPAYMENT_ONLINE", methodLabel);
        }
    }

    /** What one apply did. allocations are in the order applied. */
    public record Applied(Long repaymentId, BigDecimal amount, Long walletEntryId,
                          List<CreditDtos.WalletRepaymentAllocation> allocations,
                          Map<Long, Long> paymentIdByInvoice) { }

    /**
     * Repay one agreement. Preconditions (the caller's, not checked here): inside a transaction; for WALLET the
     * wallet is locked and has the balance; every invoice is locked FOR UPDATE (ascending id) and each amount is at
     * most its outstanding; lines are in the order to apply (oldest due date, then id).
     */
    public Applied apply(Long actorId, Long agreementId, Long outletId, Long storeId, List<Line> lines,
                         Funding funding, Instant paidAt) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A repayment needs at least one invoice.");
        }
        BigDecimal amount = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // d. The repayment row, flushed so its id exists for the wallet reference.
        var repayment = new CreditRepayment();
        repayment.setCreditAgreementId(agreementId);
        repayment.setOutletId(outletId);
        repayment.setSupplierStoreId(storeId);
        repayment.setAmount(amount);
        repayment.setSource(funding.source());
        repayment.setStatus(CreditRepaymentStatus.COMPLETED);
        repayment.setIdempotencyKey(funding.repaymentKey());
        repayment.setCreatedBy(actorId);
        repayment.setCreditPaymentAttemptId(funding.attemptId());
        if (funding.source() == CreditRepaymentSource.ONLINE) {
            repayment.setMethod(funding.method());
            repayment.setReference(funding.reference());
        }
        repayments.saveAndFlush(repayment);
        Long repaymentId = repayment.getId();

        // e. WALLET only: the debit (its own conditional update stays the last line of defence), then the link to
        // its entry. The debit clears the persistence context, so the row is read again before it is changed.
        Long entryId = null;
        var linked = repayment;
        if (funding.source() == CreditRepaymentSource.WALLET) {
            var supplierStore = directory.store(storeId);
            entryId = wallet.debitCreditRepayment(outletId, repaymentId, amount,
                    supplierStore == null ? null : supplierStore.storeName());
            linked = repayments.findById(repaymentId).orElseThrow();
            linked.setWalletTransactionId(entryId);
            repayments.saveAndFlush(linked);
        }

        // e2. What Mandi now owes the supplier (D-156). Right after the money came into Mandi's hands, in this same
        // transaction, so the payout exists if and only if the repayment does.
        payoutWriter.record(linked);

        // f. Each line through the one shared method, once per invoice.
        String reference = funding.reference() != null ? funding.reference() : "credit-repayment-" + repaymentId;
        var allocations = new ArrayList<CreditDtos.WalletRepaymentAllocation>();
        var paymentIdByInvoice = new LinkedHashMap<Long, Long>();
        for (Line line : lines) {
            CreditInvoice invoice = line.invoice();
            var payment = invoiceService.applyPayment(invoice, line.amount(), funding.method(), reference, null,
                    paidAt, actorId, funding.paymentKeyPrefix() + repaymentId + ":" + invoice.getId(),
                    funding.paymentSource(), repaymentId);
            allocations.add(new CreditDtos.WalletRepaymentAllocation(
                    invoice.getId(), invoice.getInvoiceNumber(), line.amount(), invoice.getStatus()));
            paymentIdByInvoice.put(invoice.getId(), payment.getId());
        }

        // g. Audit, and one event for the supplier. Not CreditRepaymentRecorded: that text says the supplier
        // recorded the payment, and here the restaurant did it itself.
        auditService.record(actorId, null, funding.auditAction(), "CREDIT_REPAYMENT", repaymentId,
                null, CreditRepaymentStatus.COMPLETED.name(), amount.toPlainString(), "API");
        var outlet = directory.outlet(outletId);
        var payload = new HashMap<String, Object>();
        payload.put("creditAgreementId", agreementId);
        payload.put("outletId", outletId);
        payload.put("supplierStoreId", storeId);
        payload.put("restaurantName", outlet == null || outlet.restaurantName() == null ? "" : outlet.restaurantName());
        payload.put("amount", amount.toPlainString());
        payload.put("repaymentId", repaymentId);
        payload.put("invoiceCount", lines.size());
        payload.put("invoiceCountText", lines.size() == 1 ? "1 invoice" : lines.size() + " invoices");
        payload.put("methodLabel", funding.methodLabel());
        // The rule reads "... {paidVia|through Mandi}", so a payload without it still renders the old sentence.
        payload.put("paidVia", "by " + funding.methodLabel());
        if (funding.attemptId() != null) {
            payload.put("attemptId", funding.attemptId());
        }
        outbox.publish(CreditEvents.REPAYMENT_RECEIVED, "CREDIT_AGREEMENT", agreementId, payload, actorId);
        log.info("Credit repayment {} of {} by {} for outlet {} on agreement {}",
                repaymentId, Rupees.of(amount), funding.methodLabel(), outletId, agreementId);

        return new Applied(repaymentId, amount, entryId, allocations, paymentIdByInvoice);
    }
}
