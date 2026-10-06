package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditNoteKind;
import com.costonomy.mp.credit.domain.CreditNoteReason;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditRefundDue;
import com.costonomy.mp.credit.domain.CreditReservationStatus;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditNoteRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRepository;
import com.costonomy.mp.credit.repository.CreditRefundDueRepository;
import com.costonomy.mp.credit.repository.CreditReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * An order funded by credit is cancelled after the draw (B7, D-152, D-153). A restaurant must never owe for goods it
 * did not receive, so in the cancel's own transaction:
 *
 * <ul>
 *   <li>a SYSTEM credit note (reason CANCELLED, no author) takes off what is still owed on the invoice, never more;</li>
 *   <li>money already paid on the invoice cannot be cancelled by a note, so it becomes a refund due to the restaurant:
 *       OFF_PLATFORM for what the supplier received directly (the supplier refunds it and marks it refunded),
 *       WALLET for what the restaurant paid from its Mandi wallet (flagged for ops; no wallet or payout money moves).</li>
 * </ul>
 *
 * <p>Called by {@link CreditFundingAdapter#onOrderUnfulfilled}, joined to the cancel transaction: if anything here
 * fails the whole cancel rolls back (no half state). Idempotent: a second delivery for the same order finds the note
 * (key {@code cancel:<orderId>}) or the refunds already there and does nothing. Lock order is the payment paths': the
 * invoice {@code FOR UPDATE}, then the agreement's exposure inside {@code applyCredit}. The invoice's id is read first
 * on its own, so the locked read is the first load of the row and sees a racing payment's result.
 *
 * <p><b>Limit:</b> the order is cancelled whole. The codebase has no partial fulfilment amount to cancel (a short
 * delivery is a manual credit note, OR11), so the note is always the invoice's remaining outstanding.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditCancellationService {

    static final String WALLET_NOTE = "wallet-funded: ops refund";

    private final CreditReservationRepository reservations;
    private final CreditInvoiceRepository invoices;
    private final CreditNoteRepository notes;
    private final CreditPaymentRepository payments;
    private final CreditRefundDueRepository refundsDue;
    private final CreditInvoiceService invoiceService;
    private final CreditNoteService noteService;
    private final CreditDirectory directory;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional
    public void onCancelledAfterDraw(Long supplierOrderId, String reason) {
        var reservation = reservations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (reservation == null || reservation.getStatus() != CreditReservationStatus.UTILIZED) {
            // Not a credit order, or nothing was drawn yet (the hold is released by the ledger).
            return;
        }
        Long invoiceId = invoices.findIdBySupplierOrderId(supplierOrderId).orElse(null);
        if (invoiceId == null) {
            return;
        }
        var invoice = invoices.lockById(invoiceId).orElseThrow();

        String noteKey = "cancel:" + supplierOrderId;
        if (notes.findByIdempotencyKey(noteKey).isPresent() || refundsDue.existsByCreditInvoiceId(invoiceId)) {
            // Delivered before: the note and the refunds are already there.
            return;
        }

        var order = directory.order(supplierOrderId);
        String orderLabel = order == null ? "#" + supplierOrderId : order.orderNumber();

        BigDecimal owed = invoice.outstanding();
        if (owed.signum() > 0) {
            var credit = invoiceService.applyCredit(invoice, owed, CreditNoteKind.SYSTEM_CANCEL, CreditNoteReason.CANCELLED,
                    "Order %s was cancelled".formatted(orderLabel), null, null, noteKey, true);
            auditService.record(null, null, "CREDIT_NOTE_ISSUED", "CREDIT_NOTE", credit.getId(), null,
                    invoice.getStatus().name(),
                    "%s %s on %s: order cancelled".formatted(credit.getCreditNoteNumber(), owed.toPlainString(),
                            invoice.getInvoiceNumber()),
                    "SYSTEM");
            noteService.publishIssued(credit, invoice, null);
            refundPaidPart(invoice, credit.getId(), orderLabel);
            log.info("Order {} cancelled after the draw: credit note {} of {} on invoice {}", orderLabel,
                    credit.getCreditNoteNumber(), Rupees.of(owed), invoice.getInvoiceNumber());
        } else {
            refundPaidPart(invoice, null, orderLabel);
        }
    }

    /** What was paid on the invoice is owed back: the supplier's part off-platform, the wallet's part to ops. */
    private void refundPaidPart(com.costonomy.mp.credit.domain.CreditInvoice invoice, Long noteId, String orderLabel) {
        BigDecimal paid = invoice.getPaidAmount();
        if (paid.signum() <= 0) {
            return;
        }
        // A locking read: a wallet repayment that committed while this waited for the invoice must be counted.
        BigDecimal viaWallet = payments.lockedSumByInvoiceAndSource(invoice.getId(), CreditPaymentSource.WALLET.name()).min(paid);
        BigDecimal direct = paid.subtract(viaWallet);

        if (direct.signum() > 0) {
            var row = newRefund(invoice, noteId, direct, CreditRefundDue.Channel.OFF_PLATFORM, null);
            var store = directory.store(invoice.getSupplierStoreId());
            var outlet = directory.outlet(invoice.getOutletId());
            outbox.publish(CreditEvents.REFUND_DUE, "CREDIT_INVOICE", invoice.getId(),
                    Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                            "outletId", invoice.getOutletId(),
                            "supplierStoreId", invoice.getSupplierStoreId(),
                            "supplierName", store == null || store.supplierName() == null ? "" : store.supplierName(),
                            "restaurantName", outlet == null || outlet.restaurantName() == null ? "" : outlet.restaurantName(),
                            "invoiceNumber", invoice.getInvoiceNumber(),
                            "amount", CreditNoteService.money(direct).toPlainString(),
                            "refundDueId", row.getId()),
                    null);
        }
        if (viaWallet.signum() > 0) {
            var row = newRefund(invoice, noteId, viaWallet, CreditRefundDue.Channel.WALLET, WALLET_NOTE);
            // The alert row for ops: Mandi paid this on to the supplier, so only ops can put it right. Nothing moves here.
            auditService.record(null, null, "CREDIT_REFUND_DUE_OPS", "CREDIT_REFUND_DUE", row.getId(), null,
                    CreditRefundDue.Status.OPEN.name(),
                    "%s of order %s (invoice %s) was paid from the restaurant's wallet and is owed back: %s"
                            .formatted(viaWallet.toPlainString(), orderLabel, invoice.getInvoiceNumber(), WALLET_NOTE),
                    "SYSTEM");
            log.warn("Wallet-funded refund of {} owed on cancelled order {} (invoice {}): ops must refund it",
                    Rupees.of(viaWallet), orderLabel, invoice.getInvoiceNumber());
        }
    }

    private CreditRefundDue newRefund(com.costonomy.mp.credit.domain.CreditInvoice invoice, Long noteId, BigDecimal amount,
                                      CreditRefundDue.Channel channel, String note) {
        var row = new CreditRefundDue();
        row.setCreditNoteId(noteId);
        row.setCreditInvoiceId(invoice.getId());
        row.setCreditAgreementId(invoice.getCreditAgreementId());
        row.setOutletId(invoice.getOutletId());
        row.setSupplierStoreId(invoice.getSupplierStoreId());
        row.setAmount(amount);
        row.setChannel(channel);
        row.setStatus(CreditRefundDue.Status.OPEN);
        row.setNote(note);
        row.setIdempotencyKey("cancel:%d:%s".formatted(invoice.getId(), channel.name()));
        return refundsDue.saveAndFlush(row);
    }
}
