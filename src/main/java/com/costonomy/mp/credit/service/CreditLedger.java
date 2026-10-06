package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.domain.CreditTransaction;
import com.costonomy.mp.credit.domain.CreditTransactionType;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Appends to the credit ledger, and the one movement that isn't tied to an order.
 *
 * <p>Split out of {@link CreditLedgerService} to break a genuine cycle rather than
 * paper over it: utilization raises an invoice, and a repayment against an invoice
 * moves the ledger. Both halves need to <em>write</em> the ledger, and neither
 * needs the other's lifecycle logic — so the writing lives here and both depend on
 * it. A {@code @Lazy} injection would have hidden the cycle instead of removing it.
 */
@Service
@RequiredArgsConstructor
public class CreditLedger {

    private final CreditExposureStore exposure;
    private final CreditTransactionRepository transactions;
    private final AuditService auditService;

    /**
     * Append a row carrying the balances as they now stand.
     *
     * <p>Always called in the same transaction as the movement it describes. A
     * balance that changed without a ledger row is a number nobody can explain
     * later, which is the state doc 09 §11 exists to prevent.
     */
    @Transactional
    public void record(Long agreementId, CreditTransactionType type, BigDecimal amount,
                       Long reservationId, Long supplierOrderId, Long invoiceId,
                       String description, Long actorId) {
        record(agreementId, type, amount, reservationId, supplierOrderId, invoiceId, null, description, actorId);
    }

    /** As above, for a CREDIT_NOTE or WRITE_OFF row, which names the note it records so a statement can show its number. */
    @Transactional
    public void record(Long agreementId, CreditTransactionType type, BigDecimal amount,
                       Long reservationId, Long supplierOrderId, Long invoiceId, Long creditNoteId,
                       String description, Long actorId) {

        // Straight from the row. A JPA read here would return the stale instance
        // the persistence context already holds — the balances were changed by SQL
        // it knows nothing about — and the ledger would record numbers that were
        // never true.
        var balances = exposure.read(agreementId);

        var transaction = new CreditTransaction();
        transaction.setCreditAgreementId(agreementId);
        transaction.setCreditReservationId(reservationId);
        transaction.setSupplierOrderId(supplierOrderId);
        transaction.setCreditInvoiceId(invoiceId);
        transaction.setCreditNoteId(creditNoteId);
        transaction.setTransactionType(type);
        transaction.setAmount(amount);
        transaction.setBalanceReservedAfter(balances.reserved());
        transaction.setBalanceUtilizedAfter(balances.utilized());
        transaction.setBalanceAvailableAfter(balances.available());
        transaction.setDescription(description);
        transaction.setCreatedBy(actorId);
        transactions.save(transaction);

        // CREDIT_NOTE and WRITE_OFF get their own names: "CREDIT_CREDIT_NOTE" reads wrongly, and the services that
        // issue them write CREDIT_NOTE_ISSUED / CREDIT_WRITTEN_OFF themselves.
        String action = switch (type) {
            case CREDIT_NOTE -> "CREDIT_NOTE_POSTED";
            case WRITE_OFF -> "CREDIT_WRITE_OFF_POSTED";
            default -> "CREDIT_" + type.name();
        };
        auditService.record(actorId, null, action, "CREDIT_AGREEMENT",
                agreementId, null, amount.toPlainString(), description, "SYSTEM");
    }

    /**
     * The supplier undid a payment of {@code amount}: the debt is owed again (D-140). The reverse of {@link #repay}, in
     * the same transaction as the invoice and receipt it belongs to.
     *
     * @return false, with nothing written, when the debt would not fit under the limit
     */
    @Transactional
    public boolean reverse(Long agreementId, Long invoiceId, BigDecimal amount, String description, Long actorId) {
        if (!exposure.unrepay(agreementId, amount)) {
            return false;
        }
        record(agreementId, CreditTransactionType.PAYMENT_REVERSED, amount, null, null, invoiceId, description, actorId);
        return true;
    }

    /**
     * An amount was taken off an invoice without a payment (B7, B8): reduce what is drawn, exactly as a repayment
     * does, and write the CREDIT_NOTE or WRITE_OFF row that names the note. Same transaction as the invoice and the
     * note, so the debt and the exposure move together.
     */
    @Transactional
    public void credit(Long agreementId, Long invoiceId, BigDecimal amount, CreditTransactionType type, Long creditNoteId,
                       String description, Long actorId) {
        if (!exposure.repay(agreementId, amount)) {
            // Taking off more than is drawn would drive utilization negative and inflate the limit.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That is larger than the outstanding credit.");
        }
        record(agreementId, type, amount, null, null, invoiceId, creditNoteId, description, actorId);
    }

    /**
     * The restaurant repaid {@code amount}. Reduce what is drawn.
     *
     * <p>Called once a payment has been recorded against an invoice, so the debt
     * and the exposure move together — a repayment that reduced one without the
     * other leaves available credit wrong in whichever direction the missing half
     * pointed.
     */
    @Transactional
    public void repay(Long agreementId, Long invoiceId, BigDecimal amount, Long actorId) {
        if (!exposure.repay(agreementId, amount)) {
            // Repaying more than is drawn would drive utilization negative and
            // inflate the limit — the restaurant would gain spending power out of
            // an accounting error.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That payment is larger than the outstanding credit.");
        }

        record(agreementId, CreditTransactionType.REPAYMENT, amount,
                null, null, invoiceId, "Repayment against invoice " + invoiceId, actorId);
    }
}
