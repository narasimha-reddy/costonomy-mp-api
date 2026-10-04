package com.costonomy.mp.wallet;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.service.WalletBankPayoutService;
import com.costonomy.mp.wallet.service.WalletService;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletBankPayoutServiceTest {

    @Mock
    private WalletService walletService;

    @Mock
    private AccessControlService accessControl;

    @Mock
    private AuditService auditService;

    @Mock
    private OutboxService outbox;

    @Mock
    private PlatformTransactionManager txManager;

    private WalletBankPayoutService bankPayoutService;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        bankPayoutService = new WalletBankPayoutService(
                walletService, accessControl, auditService, outbox, txManager);
    }

    @Test
    @DisplayName("Initiate bank payout debits wallet balance and emits outbox event")
    void initiatePayoutSuccess() {
        Long actorId = 1L;
        Long outletId = 100L;
        BigDecimal amount = new BigDecimal("5000.00");

        Wallet lockedWallet = new Wallet();
        lockedWallet.setId(10L);
        lockedWallet.setOutletId(outletId);
        lockedWallet.setBalance(new BigDecimal("12000.00"));

        Wallet afterDebit = new Wallet();
        afterDebit.setId(10L);
        afterDebit.setOutletId(outletId);
        afterDebit.setBalance(new BigDecimal("7000.00"));

        when(walletService.lock(outletId)).thenReturn(lockedWallet);
        when(walletService.debitBankPayout(eq(outletId), anyString(), eq(amount), eq("••••5678")))
                .thenReturn(afterDebit);

        WalletDtos.BankPayoutRequest request = new WalletDtos.BankPayoutRequest(
                amount, "12345678", "HDFC0001234", "Taj Kitchens LLP", "Operating funds payout");

        WalletDtos.BankPayoutResponse response = bankPayoutService.initiatePayout(
                actorId, outletId, request, "idemp-payout-1");

        assertThat(response.outletId()).isEqualTo(outletId);
        assertThat(response.amountDebited()).isEqualByComparingTo(amount);
        assertThat(response.balanceAfter()).isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(response.maskedAccountNumber()).isEqualTo("••••5678");
        assertThat(response.status()).isEqualTo("INITIATED");

        verify(accessControl).requireScoped(actorId, Permissions.WALLET_WITHDRAW, ScopeType.OUTLET, outletId, "Outlet");
        verify(outbox).publish(eq("WalletBankPayoutInitiated"), eq("WALLET"), eq(10L), anyMap(), eq(actorId));
    }

    @Test
    @DisplayName("Bank payout fails when wallet balance is insufficient")
    void initiatePayoutFailsOnInsufficientBalance() {
        Long actorId = 1L;
        Long outletId = 100L;
        BigDecimal amount = new BigDecimal("15000.00");

        Wallet lockedWallet = new Wallet();
        lockedWallet.setId(10L);
        lockedWallet.setOutletId(outletId);
        lockedWallet.setBalance(new BigDecimal("3000.00"));

        when(walletService.lock(outletId)).thenReturn(lockedWallet);

        WalletDtos.BankPayoutRequest request = new WalletDtos.BankPayoutRequest(
                amount, "12345678", "HDFC0001234", "Taj Kitchens LLP", "Operating funds payout");

        assertThatThrownBy(() -> bankPayoutService.initiatePayout(actorId, outletId, request, "idemp-payout-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("insufficient");

        verify(walletService, never()).debitBankPayout(any(), any(), any(), any());
    }
}
