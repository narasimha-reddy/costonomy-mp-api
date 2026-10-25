package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.DeliveryRecoveryTask;
import com.costonomy.mp.delivery.repository.DeliveryRecoveryTaskRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryRecoveryServiceTest {

    @Mock private DeliveryRecoveryTaskRepository tasks;
    @Mock private DeliveryProviderRegistry registry;
    @Mock private WalletService wallet;
    @Mock private DeliveryRepository deliveries;
    @Mock private JdbcTemplate jdbc;
    @Mock private AuditService auditService;
    @Mock private com.costonomy.mp.payment.service.GatewayRefundService gatewayRefunds;

    private DeliveryRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryRecoveryService(tasks, registry, wallet, deliveries, jdbc, auditService, gatewayRefunds);
    }

    @Test
    @DisplayName("atomic claim ensures concurrent workers claim task at most once")
    void concurrentWorkerClaimAtMostOnce() {
        long taskId = 42L;
        // First worker claims the task
        when(tasks.claimTask(eq(taskId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(1);
        var task = new DeliveryRecoveryTask();
        task.setId(taskId);
        task.setFlow("FOOD");
        task.setDeliveryId(101L);
        task.setOutletId(5L);
        task.setTaskType("WALLET_REFUND");
        task.setIdempotencyRef("food-delivery-101-refund");
        task.setAmount(new BigDecimal("120.0000"));
        task.setAttempts(1);
        task.setMaxAttempts(5);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        when(wallet.refundFoodDelivery(eq(5L), eq(101L), eq("refund"), eq(new BigDecimal("120.0000")), any()))
                .thenReturn(true);

        boolean worker1Result = service.executeTask(taskId);
        assertThat(worker1Result).isTrue();
        verify(tasks).completeTask(eq(taskId), anyString(), any(Instant.class));

        // Second worker tries to claim the same task concurrently but gets 0 rows updated
        when(tasks.claimTask(eq(taskId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(0);
        boolean worker2Result = service.executeTask(taskId);
        assertThat(worker2Result).isFalse();
    }

    @Test
    @DisplayName("crash recovery reclaims stale PROCESSING task after worker lease expires")
    void crashRecoveryReclaimsStaleProcessingTask() {
        long taskId = 88L;
        when(tasks.findDueTaskIds(any(Instant.class), any(Instant.class), eq(50)))
                .thenReturn(List.of(taskId));
        when(tasks.claimTask(eq(taskId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(1);

        var staleTask = new DeliveryRecoveryTask();
        staleTask.setId(taskId);
        staleTask.setFlow("SUPPLIER");
        staleTask.setDeliveryId(202L);
        staleTask.setTaskType("PROVIDER_CANCEL");
        staleTask.setProviderCode("MOCK_EXPRESS");
        staleTask.setProviderDeliveryId("MOCK-DEL-1");
        staleTask.setAttempts(2);
        staleTask.setMaxAttempts(5);
        when(tasks.findById(taskId)).thenReturn(Optional.of(staleTask));

        var mockAdapter = org.mockito.Mockito.mock(com.costonomy.mp.delivery.provider.DeliveryProvider.class);
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(mockAdapter);

        int processed = service.processDueTasks();
        assertThat(processed).isEqualTo(1);
        verify(mockAdapter).cancel(eq("MOCK-DEL-1"), any());
        verify(tasks).completeTask(eq(taskId), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("scheduleCancellation is idempotent at the database boundary")
    void scheduleCancellationIsIdempotent() {
        String expectedRef = "cancel-SUPPLIER-10-MOCK_SAVER-"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var existing = new DeliveryRecoveryTask();
        existing.setId(55L);
        existing.setIdempotencyRef(expectedRef);

        when(tasks.findByIdempotencyRef(expectedRef)).thenReturn(Optional.of(existing));

        var returned = service.scheduleCancellation("SUPPLIER", 10L, "MOCK_SAVER", null, "Cancelled");
        assertThat(returned.getId()).isEqualTo(55L);
        verify(tasks, never()).save(any());
    }

    @Test
    @DisplayName("scheduleRefund is idempotent at the database boundary")
    void scheduleRefundIsIdempotent() {
        String expectedRef = "food-delivery-30-adjust";
        var existing = new DeliveryRecoveryTask();
        existing.setId(77L);
        existing.setIdempotencyRef(expectedRef);

        when(tasks.findByIdempotencyRef(expectedRef)).thenReturn(Optional.of(existing));

        var returned = service.scheduleRefund(30L, 2L, "adjust", new BigDecimal("15.0000"), "Unused part");
        assertThat(returned.getId()).isEqualTo(77L);
        verify(tasks, never()).save(any());
    }

    @Test
    @DisplayName("executeWalletRefund treats already completed wallet transaction as idempotent success")
    void walletRefundIdempotentSuccess() {
        long taskId = 99L;
        when(tasks.claimTask(eq(taskId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(1);

        var task = new DeliveryRecoveryTask();
        task.setId(taskId);
        task.setFlow("FOOD");
        task.setDeliveryId(45L);
        task.setOutletId(3L);
        task.setTaskType("WALLET_REFUND");
        task.setIdempotencyRef("food-delivery-45-refund");
        task.setAmount(new BigDecimal("80.0000"));
        task.setAttempts(1);
        task.setMaxAttempts(5);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));

        // WalletService returns false because entry already exists
        when(wallet.refundFoodDelivery(eq(3L), eq(45L), eq("refund"), eq(new BigDecimal("80.0000")), any()))
                .thenReturn(false);
        // Database lookup verifies wallet_transaction has reference
        when(jdbc.queryForObject(eq("SELECT count(*) FROM wallet_transaction WHERE reference = ?"), eq(Integer.class), eq("food-delivery-45-refund")))
                .thenReturn(1);

        boolean result = service.executeTask(taskId);
        assertThat(result).isTrue();
        verify(tasks).completeTask(eq(taskId), anyString(), any(Instant.class));
        verify(tasks, never()).retryTask(any(), any(), any(), any(), any());
        verify(tasks, never()).exhaustTask(any(), any(), any(), any());
    }
}
