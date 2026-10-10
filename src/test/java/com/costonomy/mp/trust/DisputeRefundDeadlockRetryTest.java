package com.costonomy.mp.trust;

import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.service.OrderFunding;
import com.costonomy.mp.settlement.service.SupplierRefundLedger;
import com.costonomy.mp.trust.domain.Dispute;
import com.costonomy.mp.trust.domain.DisputeRefund;
import com.costonomy.mp.trust.domain.DisputeRefundStatus;
import com.costonomy.mp.trust.repository.DisputeMessageRepository;
import com.costonomy.mp.trust.repository.DisputeRefundRepository;
import com.costonomy.mp.trust.repository.DisputeRepository;
import com.costonomy.mp.trust.service.DisputeRefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An approval that loses a deadlock to another outlet's refund is run again, once, and does not fail (D-110). The
 * whole approval is one transaction (the supplier's charge and the wallet credit), so the rollback undid all of it
 * and running it again cannot pay twice.
 */
class DisputeRefundDeadlockRetryTest {

    private final DisputeRefundRepository requests = mock(DisputeRefundRepository.class);
    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final OrderFunding funding = mock(OrderFunding.class);
    private final SupplierRefundLedger ledger = mock(SupplierRefundLedger.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final DisputeRefundService service = new DisputeRefundService(disputes, requests,
            mock(DisputeMessageRepository.class), mock(AccessControlService.class), funding, ledger,
            mock(AuditService.class), mock(OutboxService.class), new TransactionTemplate(txManager));

    private DisputeRefund aRequest() {
        var refund = new DisputeRefund();
        refund.setId(7L);
        refund.setDisputeId(3L);
        refund.setSupplierOrderId(11L);
        refund.setAmount(new BigDecimal("300.00"));
        refund.setStatus(DisputeRefundStatus.REQUESTED);
        var dispute = new Dispute();
        dispute.setId(3L);
        dispute.setDisputeNumber("D-3");
        dispute.setOutletId(1L);
        dispute.setSupplierStoreId(2L);
        dispute.setSupplierOrderId(11L);
        when(requests.lockById(7L)).thenReturn(Optional.of(refund));
        when(disputes.findById(3L)).thenReturn(Optional.of(dispute));
        when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        return refund;
    }

    private static CannotAcquireLockException aDeadlock() {
        return new CannotAcquireLockException("could not execute statement",
                new SQLException("Deadlock found when trying to get lock", "40001", 1213));
    }

    @Test
    @DisplayName("a supplier's approval that lost a deadlock is made again and succeeds")
    void supplierApprovalIsRetried() {
        var refund = aRequest();
        when(funding.refundToWallet(anyLong(), any(), anyString(), any(), any()))
                .thenThrow(aDeadlock()).thenReturn(null);

        var response = service.supplierApprove(9L, 7L, "ok");

        assertThat(response.status()).isEqualTo(DisputeRefundStatus.APPROVED);
        assertThat(refund.getStatus()).isEqualTo(DisputeRefundStatus.APPROVED);
        verify(funding, times(2)).refundToWallet(anyLong(), any(), eq("dispute-refund-7"), any(), any());
        // Each run was its own transaction, and the first was rolled back whole.
        verify(txManager, times(2)).getTransaction(any());
        verify(txManager).rollback(any());
        verify(txManager).commit(any());
    }

    @Test
    @DisplayName("an operations approval that lost a deadlock is made again and succeeds")
    void operationsApprovalIsRetried() {
        var refund = aRequest();
        refund.setStatus(DisputeRefundStatus.DECLINED);
        when(funding.refundToWallet(anyLong(), any(), anyString(), any(), any()))
                .thenThrow(aDeadlock()).thenReturn(null);

        var response = service.opsApprove(9L, 7L, "ok");

        assertThat(response.status()).isEqualTo(DisputeRefundStatus.OPS_APPROVED);
        verify(funding, times(2)).refundToWallet(anyLong(), any(), eq("dispute-refund-7"), any(), any());
    }

    @Test
    @DisplayName("a second deadlock is not hidden: the approval fails, rolled back")
    void aSecondDeadlockFails() {
        aRequest();
        doThrow(aDeadlock()).when(funding).refundToWallet(anyLong(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> service.supplierApprove(9L, 7L, "ok")).isInstanceOf(CannotAcquireLockException.class);

        verify(funding, times(2)).refundToWallet(anyLong(), any(), anyString(), any(), any());
        verify(txManager, times(2)).rollback(any());
    }
}
