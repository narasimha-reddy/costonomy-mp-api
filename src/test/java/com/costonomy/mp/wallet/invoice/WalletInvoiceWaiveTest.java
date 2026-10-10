package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.storage.invoice.InvoiceLinkSigner;
import com.costonomy.mp.storage.invoice.InvoiceStorage;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.domain.WalletTransaction;
import com.costonomy.mp.wallet.invoice.costapi.CostOutletMap;
import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoiceWaiver;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoicePageRepository;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoiceRepository;
import com.costonomy.mp.wallet.invoice.repository.WalletEntryInvoiceWaiverRepository;
import com.costonomy.mp.wallet.invoice.service.BillStatuses;
import com.costonomy.mp.wallet.invoice.service.InvoiceProperties;
import com.costonomy.mp.wallet.invoice.service.InvoiceReadingService;
import com.costonomy.mp.wallet.invoice.service.WalletInvoiceService;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** D-116 review L4: a failed waiver insert is a success only when a waiver row is really there afterwards. */
class WalletInvoiceWaiveTest {

    private static final long OUTLET = 7L;
    private static final long WALLET = 5L;
    private static final long ENTRY = 184L;

    private final WalletEntryInvoiceWaiverRepository waivers = mock(WalletEntryInvoiceWaiverRepository.class);

    @SuppressWarnings("unchecked")
    private WalletInvoiceService service() {
        var em = mock(EntityManager.class);
        TypedQuery<WalletTransaction> query = mock(TypedQuery.class, RETURNS_SELF);
        var entry = mock(WalletTransaction.class);
        when(entry.getId()).thenReturn(ENTRY);
        when(query.getResultList()).thenReturn(List.of(entry));
        when(em.createQuery(anyString(), eq(WalletTransaction.class))).thenReturn(query);

        var wallets = mock(WalletService.class);
        var wallet = mock(Wallet.class);
        when(wallet.getId()).thenReturn(WALLET);
        when(wallets.find(OUTLET)).thenReturn(Optional.of(wallet));

        var billStatuses = mock(BillStatuses.class);
        when(billStatuses.forEntries(WALLET, List.of(ENTRY))).thenReturn(Map.of(ENTRY,
                new BillStatuses.Facts(WalletEntryKind.ORDER_PAYMENT, Instant.now(), null, false, false, false)));
        when(waivers.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("fk_wallet_entry_invoice_waiver_outlet"));

        return new WalletInvoiceService(em, wallets, mock(WalletEntryInvoiceRepository.class),
                mock(WalletEntryInvoicePageRepository.class), mock(InvoiceStorage.class), mock(InvoiceLinkSigner.class),
                mock(InvoiceProperties.class), mock(InvoiceReadingService.class), mock(AuditService.class),
                new ObjectMapper(), mock(PlatformTransactionManager.class), mock(CostOutletMap.class), waivers,
                billStatuses);
    }

    @Test
    @DisplayName("an integrity failure with no waiver row afterwards (a foreign key, say) is rethrown, not answered as done")
    void failureWithoutWaiverIsRethrown() {
        var service = service();
        when(waivers.findByWalletTransactionId(ENTRY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.waive(OUTLET, "L" + ENTRY, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an integrity failure because another request's waiver got in first is answered as done")
    void lostRaceIsDone() {
        var service = service();
        when(waivers.findByWalletTransactionId(ENTRY)).thenReturn(Optional.of(new WalletEntryInvoiceWaiver()));
        assertThatCode(() -> service.waive(OUTLET, "L" + ENTRY, 1L)).doesNotThrowAnyException();
    }
}
