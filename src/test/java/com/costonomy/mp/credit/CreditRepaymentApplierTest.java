package com.costonomy.mp.credit;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentSource;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.service.CreditDirectory;
import com.costonomy.mp.credit.service.CreditInvoiceService;
import com.costonomy.mp.credit.service.CreditRepaymentApplier;
import com.costonomy.mp.credit.service.CreditRepaymentApplier.Funding;
import com.costonomy.mp.credit.service.CreditRepaymentApplier.Line;
import com.costonomy.mp.credit.service.CreditRepaymentPayoutWriter;
import com.costonomy.mp.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The shared core that moves credit money (D-199): what keys, references, sources and events a wallet repayment and
 * an online capture write. The wallet strings are asserted byte for byte because existing rows and reports read them.
 */
class CreditRepaymentApplierTest {

    private final CreditRepaymentRepository repayments = mock(CreditRepaymentRepository.class);
    private final CreditInvoiceService invoiceService = mock(CreditInvoiceService.class);
    private final CreditRepaymentPayoutWriter payoutWriter = mock(CreditRepaymentPayoutWriter.class);
    private final CreditDirectory directory = mock(CreditDirectory.class);
    private final WalletService wallet = mock(WalletService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private CreditRepaymentApplier applier;

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @BeforeEach
    void setUp() {
        applier = new CreditRepaymentApplier(repayments, invoiceService, payoutWriter, directory, wallet, audit, outbox);
        when(repayments.saveAndFlush(any(CreditRepayment.class))).thenAnswer(call -> {
            CreditRepayment row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(90L);
            }
            saved = row;
            return row;
        });
        when(repayments.findById(90L)).thenAnswer(call -> Optional.of(saved));
        when(directory.store(3L)).thenReturn(new CreditDirectory.StoreInfo(3L, "Koramangala", 1L, "Spice Garden",
                true, null, null));
        when(directory.outlet(7L)).thenReturn(new CreditDirectory.OutletInfo(7L, "MG Road", 2L, "Paradise",
                null, null, null, null));
        when(wallet.debitCreditRepayment(eq(7L), eq(90L), any(), any())).thenReturn(555L);
        when(invoiceService.applyPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    var payment = new CreditPayment();
                    payment.setId(1000L + ((CreditInvoice) call.getArgument(0)).getId());
                    return payment;
                });
    }

    private CreditRepayment saved;

    private CreditRepayment lastSaved() {
        return saved;
    }

    private static CreditInvoice invoice(long id, String amount) {
        var invoice = new CreditInvoice();
        invoice.setId(id);
        invoice.setCreditAgreementId(11L);
        invoice.setInvoiceNumber("INV-" + id);
        invoice.setAmount(new BigDecimal(amount));
        invoice.setPaidAmount(BigDecimal.ZERO);
        return invoice;
    }

    @Test
    @DisplayName("wallet: the repayment, the debit, the payout and each invoice keep the exact keys and strings of D-153")
    void walletKeepsItsStrings() {
        var applied = applier.apply(5L, 11L, 7L, 3L,
                List.of(new Line(invoice(101, "6500.00"), new BigDecimal("6500.00")),
                        new Line(invoice(102, "500.00"), new BigDecimal("200.00"))),
                Funding.wallet("wallet:7:key-1", "wallet-repayment:", null), NOW);

        var repayment = lastSaved();
        assertThat(repayment.getSource()).isEqualTo(CreditRepaymentSource.WALLET);
        assertThat(repayment.getIdempotencyKey()).isEqualTo("wallet:7:key-1");
        assertThat(repayment.getAmount()).isEqualByComparingTo("6700.00");
        assertThat(repayment.getCreatedBy()).isEqualTo(5L);
        assertThat(repayment.getCreditPaymentAttemptId()).isNull();
        assertThat(repayment.getWalletTransactionId()).isEqualTo(555L);

        InOrder order = inOrder(repayments, wallet, payoutWriter, invoiceService, audit, outbox);
        order.verify(repayments).saveAndFlush(any());
        order.verify(wallet).debitCreditRepayment(7L, 90L, new BigDecimal("6700.00"), "Koramangala");
        order.verify(payoutWriter).record(any());
        order.verify(invoiceService).applyPayment(any(), eq(new BigDecimal("6500.00")), eq("WALLET"),
                eq("credit-repayment-90"), eq(null), eq(NOW), eq(5L), eq("wallet-repayment:90:101"),
                eq(CreditPaymentSource.WALLET), eq(90L));
        order.verify(invoiceService).applyPayment(any(), eq(new BigDecimal("200.00")), eq("WALLET"),
                eq("credit-repayment-90"), eq(null), eq(NOW), eq(5L), eq("wallet-repayment:90:102"),
                eq(CreditPaymentSource.WALLET), eq(90L));
        order.verify(audit).record(eq(5L), eq(null), eq("CREDIT_REPAYMENT_FROM_WALLET"), eq("CREDIT_REPAYMENT"),
                eq(90L), eq(null), eq("COMPLETED"), eq("6700.00"), eq("API"));
        order.verify(outbox).publish(eq(CreditEvents.REPAYMENT_RECEIVED), eq("CREDIT_AGREEMENT"), eq(11L), any(),
                eq(5L));

        assertThat(applied.repaymentId()).isEqualTo(90L);
        assertThat(applied.amount()).isEqualByComparingTo("6700.00");
        assertThat(applied.walletEntryId()).isEqualTo(555L);
        assertThat(applied.allocations()).extracting("invoiceId").containsExactly(101L, 102L);
        assertThat(applied.paymentIdByInvoice()).containsEntry(101L, 1101L).containsEntry(102L, 1102L);
    }

    @Test
    @DisplayName("online: no wallet debit, no provider_payment_id, the attempt is on the repayment, the Razorpay payment is the reference")
    void onlineTouchesNoWallet() {
        var applied = applier.apply(5L, 11L, 7L, 3L,
                List.of(new Line(invoice(101, "6500.00"), new BigDecimal("6500.00"))),
                Funding.online(42L, 11L, "UPI", "pay_ABC", "UPI"), NOW);

        verifyNoInteractions(wallet);
        var repayment = lastSaved();
        assertThat(repayment.getSource()).isEqualTo(CreditRepaymentSource.ONLINE);
        assertThat(repayment.getIdempotencyKey()).isEqualTo("online:42:11");
        assertThat(repayment.getCreditPaymentAttemptId()).isEqualTo(42L);
        assertThat(repayment.getProviderPaymentId()).isNull();
        assertThat(repayment.getWalletTransactionId()).isNull();
        verify(payoutWriter).record(any());
        verify(invoiceService).applyPayment(any(), eq(new BigDecimal("6500.00")), eq("UPI"), eq("pay_ABC"),
                eq(null), eq(NOW), eq(5L), eq("online-repayment:90:101"), eq(CreditPaymentSource.ONLINE), eq(90L));
        verify(audit).record(eq(5L), eq(null), eq("CREDIT_REPAYMENT_ONLINE"), eq("CREDIT_REPAYMENT"), eq(90L),
                eq(null), eq("COMPLETED"), eq("6500.00"), eq("API"));
        assertThat(applied.walletEntryId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the supplier's event keeps its old keys and gains the invoice count and how it was paid")
    void eventPayload() {
        applier.apply(5L, 11L, 7L, 3L,
                List.of(new Line(invoice(101, "100.00"), new BigDecimal("100.00")),
                        new Line(invoice(102, "50.00"), new BigDecimal("50.00")),
                        new Line(invoice(103, "25.00"), new BigDecimal("25.00"))),
                Funding.online(42L, 11L, "CARD", "pay_X", "card"), NOW);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(CreditEvents.REPAYMENT_RECEIVED), eq("CREDIT_AGREEMENT"), eq(11L),
                payload.capture(), eq(5L));
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("creditAgreementId", 11L).containsEntry("outletId", 7L)
                .containsEntry("supplierStoreId", 3L).containsEntry("restaurantName", "Paradise")
                .containsEntry("amount", "175.00").containsEntry("repaymentId", 90L)
                .containsEntry("invoiceCount", 3).containsEntry("invoiceCountText", "3 invoices")
                .containsEntry("methodLabel", "card").containsEntry("paidVia", "by card")
                .containsEntry("attemptId", 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("one invoice reads \"1 invoice\"; a wallet repayment names no attempt")
    void singularAndNoAttempt() {
        applier.apply(5L, 11L, 7L, 3L, List.of(new Line(invoice(101, "100.00"), new BigDecimal("100.00"))),
                Funding.wallet("wallet:7:k", "wallet-repayment:", null), NOW);
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(CreditEvents.REPAYMENT_RECEIVED), any(), anyLong(), payload.capture(), any());
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("invoiceCountText", "1 invoice").containsEntry("methodLabel", "wallet")
                .doesNotContainKey("attemptId");
    }

    @Test
    @DisplayName("nothing to pay is refused before anything is written")
    void emptyLines() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applier.apply(5L, 11L, 7L, 3L, List.of(),
                Funding.wallet("k", "p:", null), NOW)).isInstanceOf(IllegalArgumentException.class);
        verify(repayments, never()).saveAndFlush(any());
    }
}
