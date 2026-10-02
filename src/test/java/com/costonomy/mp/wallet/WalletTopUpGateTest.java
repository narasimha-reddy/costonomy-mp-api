package com.costonomy.mp.wallet;

import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.security.AuthenticatedActor;
import com.costonomy.mp.wallet.domain.Wallet;
import com.costonomy.mp.wallet.service.WalletService;
import com.costonomy.mp.wallet.web.WalletController;
import com.costonomy.mp.wallet.web.dto.WalletDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The manual top-up credits a balance with no money behind it. It is a stand-in
 * for a funding rail, and against a real payment provider it would let anyone who
 * can order fund their own orders for free — so it is refused there (D-099).
 */
class WalletTopUpGateTest {

    private final WalletService wallets = mock(WalletService.class);
    private final com.costonomy.mp.wallet.service.WalletTopUpService topUps =
            mock(com.costonomy.mp.wallet.service.WalletTopUpService.class);
    private final WalletController controller =
            new WalletController(wallets, mock(com.costonomy.mp.wallet.service.WalletWithdrawalService.class),
                    topUps,
                    mock(com.costonomy.mp.payment.service.RefundService.class),
                    mock(com.costonomy.mp.common.idempotency.IdempotencyService.class),
                    mock(AccessControlService.class),
                    mock(com.costonomy.mp.wallet.service.WalletHistoryService.class),
                    mock(com.costonomy.mp.wallet.statement.WalletStatementService.class));

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedActor(1L, "+919876500004"), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("refused on a real provider, and no money is credited")
    void refusedOnRealProvider() {
        ReflectionTestUtils.setField(controller, "paymentProvider", "RAZORPAY");

        assertThatThrownBy(() -> controller.topUp(1L,
                new WalletDtos.TopUpRequest(new BigDecimal("5000.00"), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(wallets, never()).topUp(any(), any(), any());
    }

    @Test
    @DisplayName("still works on the mock, so local development can use a wallet")
    void allowedOnMock() {
        ReflectionTestUtils.setField(controller, "paymentProvider", "MOCK");
        var wallet = new Wallet();
        wallet.setBalance(new BigDecimal("5000.00"));
        wallet.setCurrency("INR");
        when(wallets.topUp(any(), any(), any())).thenReturn(wallet);
        when(topUps.limitsFor(any())).thenReturn(new com.costonomy.mp.wallet.service.WalletTopUpService.LimitsView(
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));

        var response = controller.topUp(1L, new WalletDtos.TopUpRequest(new BigDecimal("5000.00"), null));

        assertThat(response.data().balance()).isEqualByComparingTo("5000.00");
        verify(wallets).topUp(eq(1L), any(), any());
    }
}
