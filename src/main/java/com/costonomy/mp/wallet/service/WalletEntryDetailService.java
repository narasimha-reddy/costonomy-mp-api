package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.quickscan.domain.QuickScanPayment;
import com.costonomy.mp.quickscan.service.QuickScanValidation;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.domain.WalletTopUp;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.statement.WalletEntryCopy;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * One wallet entry in full, for the transaction-details page (D-112). Read-only: it explains a
 * ledger row from the rows that already exist, and states only what we hold. A reference that
 * is not on file is left out, never made up.
 *
 * <p>At most one query per kind of related row (order and store, refund and its payment, top-up,
 * QuickScan payment), however many references the entry has.
 */
@Service
@RequiredArgsConstructor
public class WalletEntryDetailService {

    /** The payee's VPA is shown as its first two characters, bullets, and the bank handle. */
    static final String BULLETS = "••••";

    private final EntityManager em;
    private final WalletService wallets;

    @Transactional(readOnly = true)
    public WalletDtos.TransactionDetail detail(Long outletId, String entryKey) {
        Long entryId = parse(entryKey);
        var wallet = wallets.find(outletId).orElseThrow(WalletEntryDetailService::notFound);
        // Scoped by the outlet's own wallet: another outlet's entry is not found, not forbidden.
        var found = em.createQuery("""
                        select t, r.status from WalletTransaction t
                          left join Refund r on r.id = t.refundId
                         where t.id = :id and t.walletId = :wallet
                        """, Object[].class)
                .setParameter("id", entryId).setParameter("wallet", wallet.getId())
                .getResultList();
        if (found.isEmpty()) {
            throw notFound();
        }
        var entry = (WalletTransaction) found.get(0)[0];
        var refundStatus = (RefundStatus) found.get(0)[1];
        var kind = entry.getKind();

        String counterpartyName = null;
        String counterpartyDetail = null;
        String instrument = null;
        var refs = new ArrayList<WalletDtos.Reference>();
        boolean canPayAgain = false;
        String payeeVpa = null;

        switch (kind) {
            case TOP_UP -> {
                var topUp = topUpOf(entry, outletId);
                instrument = topUp == null ? null : WalletHistoryService.instrumentOf(topUp);
                counterpartyName = instrument;
                if (topUp != null) {
                    add(refs, "Razorpay payment id", topUp.getRazorpayPaymentId());
                }
            }
            case ORDER_PAYMENT, ORDER_REFUND, DISPUTE_REFUND -> {
                var order = orderOf(entry.getSupplierOrderId(), outletId);
                if (order != null) {
                    counterpartyName = order[1];
                    counterpartyDetail = order[0];
                    add(refs, "Order number", order[0]);
                }
                if (kind == WalletEntryKind.ORDER_PAYMENT && entry.getSupplierOrderId() != null) {
                    // Present only when the order was paid through Razorpay rather than from the wallet.
                    add(refs, "Razorpay payment id", razorpayPaymentOfOrder(entry.getSupplierOrderId(), outletId));
                }
            }
            case REFUND -> {
                counterpartyName = WalletEntryCopy.label(kind, entry.getDirection());
                var order = orderOf(entry.getSupplierOrderId(), outletId);
                if (order != null) {
                    counterpartyDetail = order[0];
                    add(refs, "Order number", order[0]);
                }
                refundReferences(refs, entry.getRefundId(), false);
            }
            case WITHDRAWAL, WITHDRAWAL_REVERSAL -> {
                counterpartyName = kind == WalletEntryKind.WITHDRAWAL
                        ? "Your card or bank" : WalletEntryCopy.label(kind, entry.getDirection());
                refundReferences(refs, entry.getRefundId(), true);
            }
            case QUICKSCAN_PAYMENT, QUICKSCAN_RETURN -> {
                var payment = quickScanOf(entry, outletId);
                if (payment != null) {
                    counterpartyName = payment.getPayeeName();
                    counterpartyDetail = maskVpa(payment.getPayeeVpa());
                    add(refs, "QuickScan payment", String.valueOf(payment.getId()));
                    String payout = payment.getProviderPayoutId();
                    // A mock provider's id is not a bank reference; only a real one is shown.
                    if (payout != null && !payout.startsWith("mock_")) {
                        add(refs, "Payout reference", payout);
                    }
                    if (kind == WalletEntryKind.QUICKSCAN_PAYMENT
                            && QuickScanValidation.isValidVpa(payment.getPayeeVpa())) {
                        canPayAgain = true;
                        payeeVpa = payment.getPayeeVpa();
                    }
                }
            }
        }

        return new WalletDtos.TransactionDetail("L" + entry.getId(), entry.getId(), String.valueOf(entry.getId()),
                entry.getDirection(), kind.name(), entry.getAmount(), entry.getBalanceAfter(),
                entry.getSupplierOrderId(), entry.getReason(),
                WalletHistoryService.statusOf(kind, refundStatus).name(),
                kind == WalletEntryKind.WITHDRAWAL && refundStatus != null ? refundStatus.name() : null,
                instrument, entry.getCreatedAt(), counterpartyName, counterpartyDetail, List.copyOf(refs),
                new WalletDtos.Actions(canPayAgain, payeeVpa));
    }

    /** {@code first two characters of the handle, bullets, @bank}; never the whole handle. */
    public static String maskVpa(String vpa) {
        if (vpa == null) {
            return null;
        }
        int at = vpa.lastIndexOf('@');
        if (at < 1) {
            return BULLETS;
        }
        String handle = vpa.substring(0, at);
        int keep = Math.min(2, handle.length() - 1);
        return handle.substring(0, Math.max(keep, 1)) + BULLETS + vpa.substring(at);
    }

    private static void add(List<WalletDtos.Reference> refs, String label, String value) {
        if (value != null && !value.isBlank()) {
            refs.add(new WalletDtos.Reference(label, value, true));
        }
    }

    /** "184" or the list's "L184". A returned top-up ("T12") has no ledger entry and is not found. */
    private static Long parse(String key) {
        String digits = key != null && key.startsWith("L") ? key.substring(1) : key;
        if (digits == null || !digits.matches("\\d{1,18}")) {
            throw notFound();
        }
        return Long.parseLong(digits);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "That wallet entry wasn't found.");
    }

    private WalletTopUp topUpOf(WalletTransaction entry, Long outletId) {
        Long id = idAfter(entry.getReference(), "topup-");
        if (id == null) {
            return null;
        }
        return em.createQuery("select t from WalletTopUp t where t.id = :id and t.outletId = :o", WalletTopUp.class)
                .setParameter("id", id).setParameter("o", outletId).getResultStream().findFirst().orElse(null);
    }

    private QuickScanPayment quickScanOf(WalletTransaction entry, Long outletId) {
        String ref = entry.getReference();
        Long id = ref == null ? null
                : ref.startsWith("quickscan-return-") ? idAfter(ref, "quickscan-return-") : idAfter(ref, "quickscan-");
        if (id == null) {
            return null;
        }
        return em.createQuery("select p from QuickScanPayment p where p.id = :id and p.outletId = :o",
                        QuickScanPayment.class)
                .setParameter("id", id).setParameter("o", outletId).getResultStream().findFirst().orElse(null);
    }

    private static Long idAfter(String reference, String prefix) {
        if (reference == null || !reference.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(reference.substring(prefix.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** {order number, supplier shop name} of one of this outlet's orders, or null. */
    private String[] orderOf(Long supplierOrderId, Long outletId) {
        if (supplierOrderId == null) {
            return null;
        }
        List<Object[]> rows = em.createQuery("""
                        select o.orderNumber, s.name from SupplierOrder o, SupplierStore s
                         where o.id = :id and o.outletId = :outlet and s.id = o.supplierStoreId
                        """, Object[].class)
                .setParameter("id", supplierOrderId).setParameter("outlet", outletId).getResultList();
        return rows.isEmpty() ? null : new String[]{(String) rows.get(0)[0], (String) rows.get(0)[1]};
    }

    private String razorpayPaymentOfOrder(Long supplierOrderId, Long outletId) {
        return em.createQuery("""
                        select p.providerPaymentId from Payment p
                         where p.supplierOrderId = :id and p.outletId = :outlet and p.provider = 'RAZORPAY'
                           and p.providerPaymentId is not null
                         order by p.id
                        """, String.class)
                .setParameter("id", supplierOrderId).setParameter("outlet", outletId)
                .setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    /** Our refund id, the provider's refund id if it has been sent, and the payment it refunds. */
    private void refundReferences(List<WalletDtos.Reference> refs, Long refundId, boolean withPayment) {
        if (refundId == null) {
            return;
        }
        var refund = em.find(Refund.class, refundId);
        if (refund == null) {
            return;
        }
        add(refs, "Refund id", String.valueOf(refund.getId()));
        add(refs, "Razorpay refund id", refund.getProviderRefundId());
        if (withPayment && refund.getPaymentId() != null) {
            var payment = em.find(Payment.class, refund.getPaymentId());
            if (payment != null && "RAZORPAY".equals(payment.getProvider())) {
                add(refs, "Razorpay payment id", payment.getProviderPaymentId());
            }
        }
    }
}
