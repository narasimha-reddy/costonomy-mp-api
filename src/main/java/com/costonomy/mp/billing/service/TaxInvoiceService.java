package com.costonomy.mp.billing.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.billing.domain.CreditNote;
import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.repository.CreditNoteItemRepository;
import com.costonomy.mp.billing.repository.CreditNoteRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceItemRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import com.costonomy.mp.billing.web.dto.BillingDtos;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statutory tax invoices (Section 31 CGST Act), behind {@link BillingFeature} (D-133).
 *
 * <p>Generation is the supplier's: the supplier is the issuer, with Mandi as technology provider. A buyer can read
 * an invoice but never create one. Everything the document states comes from the order's stored figures and the
 * parties' registered details; whatever is missing is reported, never invented.
 *
 * <p><b>{@code generateOrGetInvoice} is deliberately not transactional.</b> A transaction would fix its snapshot
 * at the first read, and the re-read after losing the insert race would not see the winner's row. The insert is
 * {@link TaxInvoiceStore}'s, in a transaction of its own (D-021): the loser's insert fails on
 * {@code uk_tax_invoice_order}, its number rolls back with it, and the caller returns the winner's invoice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceService {

    /** Issued from the moment the supply is made (ready for collection or dispatch); not before it is settled. */
    static final Set<String> ISSUABLE = Set.of("READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED", "COMPLETED");

    private static final int INSERT_ATTEMPTS = 3;

    private final BillingFeature feature;
    private final BillingDirectory directory;
    private final TaxInvoiceRepository invoices;
    private final TaxInvoiceItemRepository invoiceItems;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteItemRepository creditNoteItems;
    private final TaxInvoiceStore store;
    private final CreditNoteIssuer creditNoteIssuer;
    private final AccessControlService accessControl;

    public BillingDtos.TaxInvoiceResponse generateOrGetInvoice(Long actorId, Long supplierOrderId) {
        feature.requireEnabled();
        var order = directory.order(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));

        // The supplier's staff only, and no caller without an actor: a buyer, with ORDER_VIEW on their own
        // outlet, is refused as if the order were not there.
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.SUPPLIER_STORE,
                order.supplierStoreId(), "SupplierOrder");

        var existing = invoices.findBySupplierOrderId(supplierOrderId);
        if (existing.isPresent()) {
            return toInvoiceResponse(existing.get());
        }

        if (!ISSUABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_NOT_ALLOWED,
                    "A tax invoice can be issued once the order is ready; this order is " + order.status() + ".");
        }

        var supplier = directory.supplier(order.supplierStoreId()).orElse(null);
        var buyer = directory.buyer(order.outletId()).orElse(null);
        var lines = directory.lines(supplierOrderId);
        var missing = InvoiceRequirements.missing(supplier, buyer, lines);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_DATA_MISSING,
                    "A tax invoice can't be issued until this is filled in: " + String.join(", ", missing),
                    Map.of("missing", missing));
        }
        var parties = InvoiceRequirements.parties(supplier, buyer);
        var figures = InvoiceCalculator.calculate(order, lines, parties.interState());
        var draft = new TaxInvoiceStore.Draft(order, supplier, buyer, parties, figures);

        Long invoiceId = insert(draft, supplierOrderId);

        // A doorstep rejection recorded before the invoice existed could not be linked or, without these same
        // details, issued; now it can.
        try {
            creditNoteIssuer.issueForDoorstep(supplierOrderId);
        } catch (RuntimeException ex) {
            log.error("Credit note for order {} could not be issued with its invoice", supplierOrderId, ex);
        }
        return toInvoiceResponse(invoices.findById(invoiceId).orElseThrow());
    }

    private Long insert(TaxInvoiceStore.Draft draft, Long supplierOrderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return store.insert(draft);
            } catch (DataIntegrityViolationException lostTheRace) {
                // Another request issued this order's invoice first. Return theirs.
                return invoices.findBySupplierOrderId(supplierOrderId)
                        .map(TaxInvoice::getId)
                        .orElseThrow(() -> lostTheRace);
            } catch (PessimisticLockingFailureException contention) {
                // Two suppliers' first documents of a year, or a deadlock on the sequence row: the whole attempt
                // rolled back, so trying again is safe.
                if (attempt >= INSERT_ATTEMPTS) {
                    throw contention;
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public BillingDtos.TaxInvoiceResponse getInvoiceForOrder(Long actorId, Long supplierOrderId) {
        feature.requireEnabled();
        requireAccess(actorId, supplierOrderId);
        return invoices.findBySupplierOrderId(supplierOrderId)
                .map(this::toInvoiceResponse)
                .orElseThrow(() -> new NotFoundException("TaxInvoice for order", supplierOrderId));
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.CreditNoteResponse> getCreditNotesForOrder(Long actorId, Long supplierOrderId) {
        feature.requireEnabled();
        requireAccess(actorId, supplierOrderId);
        return creditNotes.findBySupplierOrderIdOrderByIdAsc(supplierOrderId).stream()
                .map(this::toCreditNoteResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public String generateTallyXml(Long actorId, Long supplierOrderId) {
        return InvoiceExports.tallyXml(getInvoiceForOrder(actorId, supplierOrderId));
    }

    @Transactional(readOnly = true)
    public String generateGstr1Csv(Long actorId, Long supplierOrderId) {
        return InvoiceExports.gstr1Csv(getInvoiceForOrder(actorId, supplierOrderId));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Reading is open to both sides of the order: the supplier's store and the buyer's outlet. */
    private void requireAccess(Long actorId, Long supplierOrderId) {
        var order = directory.order(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));
        if (accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, order.outletId())
                || accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.SUPPLIER_STORE,
                order.supplierStoreId())) {
            return;
        }
        throw new NotFoundException("SupplierOrder", supplierOrderId);
    }

    private BillingDtos.TaxInvoiceResponse toInvoiceResponse(TaxInvoice inv) {
        var items = invoiceItems.findByTaxInvoiceIdOrderByIdAsc(inv.getId()).stream()
                .map(i -> new BillingDtos.TaxInvoiceItemResponse(
                        i.getId(), i.getSupplierOrderItemId(), i.getProductName(),
                        i.getHsnCode(), i.getQuantity(), i.getUnit(), i.getUnitPrice(),
                        i.getTaxableValue(), i.getGstRate(), i.getCgstAmount(),
                        i.getSgstAmount(), i.getIgstAmount(), i.getTotalAmount()))
                .toList();
        String orderNumber = directory.order(inv.getSupplierOrderId()).map(BillingDirectory.OrderFacts::orderNumber)
                .orElse(null);

        return new BillingDtos.TaxInvoiceResponse(
                inv.getId(), inv.getInvoiceNumber(), inv.getSupplierOrderId(), orderNumber,
                inv.getSupplierStoreId(), inv.getSupplierName(), inv.getSupplierGstin(),
                inv.getSupplierAddress(), inv.getSupplierStateCode(),
                inv.getOutletId(), inv.getRestaurantId(), inv.getBuyerName(), inv.getBuyerGstin(),
                inv.getBuyerAddress(), inv.getBuyerStateCode(), inv.getPlaceOfSupply(),
                inv.isInterState(), inv.getTaxableAmount(), inv.getCgstAmount(),
                inv.getSgstAmount(), inv.getIgstAmount(), inv.getDeliveryFee(),
                inv.getTotalAmount(), inv.getStatus(), inv.getIssuedAt(), items);
    }

    private BillingDtos.CreditNoteResponse toCreditNoteResponse(CreditNote cn) {
        var items = creditNoteItems.findByCreditNoteIdOrderByIdAsc(cn.getId()).stream()
                .map(i -> new BillingDtos.CreditNoteItemResponse(
                        i.getId(), i.getSupplierOrderItemId(), i.getProductName(),
                        i.getHsnCode(), i.getRejectedQuantity(), i.getUnit(), i.getUnitPrice(),
                        i.getTaxableRefund(), i.getGstRate(), i.getCgstRefund(),
                        i.getSgstRefund(), i.getIgstRefund(), i.getTotalRefund(),
                        i.getRejectionReason()))
                .toList();
        String orderNumber = directory.order(cn.getSupplierOrderId()).map(BillingDirectory.OrderFacts::orderNumber)
                .orElse(null);

        return new BillingDtos.CreditNoteResponse(
                cn.getId(), cn.getCreditNoteNumber(), cn.getTaxInvoiceId(), cn.getTaxInvoiceNumber(),
                cn.getSupplierOrderId(), orderNumber, cn.getSupplierStoreId(), cn.getSupplierName(),
                cn.getSupplierGstin(), cn.getOutletId(), cn.getRestaurantId(), cn.getBuyerName(),
                cn.getBuyerGstin(), cn.getReasonCode(), cn.isInterState(),
                cn.getTaxableRefundAmount(), cn.getCgstRefundAmount(), cn.getSgstRefundAmount(),
                cn.getIgstRefundAmount(), cn.getTotalRefundAmount(), cn.getStatus(),
                cn.getIssuedAt(), items);
    }
}
