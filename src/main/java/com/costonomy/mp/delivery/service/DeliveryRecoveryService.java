package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.DeliveryRecoveryTask;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.DeliveryRecoveryTaskRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;

/**
 * Service for managing durable delivery recovery tasks: cancellation retries, wallet refunds,
 * and reconciliation (D-197).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryRecoveryService {

    private final DeliveryRecoveryTaskRepository tasks;
    private final DeliveryProviderRegistry registry;
    private final WalletService wallet;
    private final DeliveryRepository deliveries;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    private static final java.time.Duration LEASE_DURATION = java.time.Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 50;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecoveryTask scheduleCancellation(String flow, Long deliveryId, String providerCode,
                                                     String providerDeliveryId, String reason) {
        String ref = cancellationReference(flow, deliveryId, providerCode, providerDeliveryId);
        log.info("Scheduling durable cancellation task for {} delivery {} provider {} consignment {}",
                flow, deliveryId, providerCode, providerDeliveryId);
        return enqueueTask(flow, deliveryId, null, "PROVIDER_CANCEL", providerCode,
                providerDeliveryId, ref, null, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecoveryTask scheduleRefund(Long foodDeliveryId, Long outletId, String refundTag,
                                               BigDecimal amount, String reason) {
        String ref = "food-delivery-" + foodDeliveryId + "-" + refundTag;
        log.info("Scheduling durable refund task for food delivery {} outlet {} amount {} tag {}",
                foodDeliveryId, outletId, amount, refundTag);
        return enqueueTask("FOOD", foodDeliveryId, outletId, "WALLET_REFUND", null,
            null, ref, amount, reason);
        }

        private DeliveryRecoveryTask enqueueTask(String flow, Long deliveryId, Long outletId, String taskType,
                              String providerCode, String providerDeliveryId, String idempotencyRef,
                              BigDecimal amount, String reason) {
        jdbc.update("""
            INSERT INTO delivery_recovery_task
                (flow, delivery_id, outlet_id, task_type, provider_code, provider_delivery_id,
                 idempotency_ref, amount, reason, status, attempts, max_attempts, next_retry_at,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 5,
                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """, flow, deliveryId, outletId, taskType, providerCode, providerDeliveryId,
            idempotencyRef, amount, reason);
        return tasks.findByIdempotencyRef(idempotencyRef)
            .orElseThrow(() -> new IllegalStateException("Recovery task insert was not visible"));
    }

    private static String cancellationReference(String flow, Long deliveryId, String providerCode,
                                                String providerDeliveryId) {
        try {
            String providerKey = providerDeliveryId == null ? "" : providerDeliveryId;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(providerKey.getBytes(StandardCharsets.UTF_8)));
            return "cancel-" + flow.toUpperCase() + "-" + deliveryId + "-"
                    + (providerCode == null ? "unknown" : providerCode) + "-" + digest;
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Executes one recovery task outside long-running database transactions using atomic bounded claims.
     */
    public boolean executeTask(Long taskId) {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(LEASE_DURATION);
        String claimToken = java.util.UUID.randomUUID().toString();
        int claimed = tasks.claimTask(taskId, now, staleThreshold, claimToken);
        if (claimed == 0) {
            return false;
        }

        var task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return false;
        }

        boolean success = false;
        String errorMessage = null;

        try {
            if ("PROVIDER_CANCEL".equals(task.getTaskType())) {
                success = cancelAtProvider(task.getProviderCode(), task.getProviderDeliveryId(), task.getReason());
            } else if ("WALLET_REFUND".equals(task.getTaskType())) {
                success = executeWalletRefund(task);
            }
        } catch (Exception ex) {
            errorMessage = ex.getMessage();
            log.warn("Recovery task {} failed with exception: {}", taskId, ex.getMessage());
        }

        Instant completionTime = Instant.now();
        if (success) {
            tasks.completeTask(taskId, claimToken, completionTime);
            log.info("Recovery task {} ({}) completed successfully", taskId, task.getTaskType());
            return true;
        }

        String err = errorMessage != null ? errorMessage : "Operation unsuccessful";
        if (task.getAttempts() >= task.getMaxAttempts()) {
            tasks.exhaustTask(taskId, claimToken, err, completionTime);
            markReconciliationRequired(task.getFlow(), task.getDeliveryId(),
                    "Recovery task %s (%s) exhausted after %d attempts: %s"
                            .formatted(task.getId(), task.getTaskType(), task.getAttempts(), err));
            log.error("Recovery task {} ({}) exhausted after {} attempts", taskId, task.getTaskType(), task.getAttempts());
            return false;
        }

        long delaySeconds = Math.min(1800, 30L * (1L << (task.getAttempts() - 1)));
        Instant nextRetry = completionTime.plusSeconds(delaySeconds);
        tasks.retryTask(taskId, claimToken, nextRetry, err, completionTime);
        log.info("Recovery task {} ({}) scheduled for retry {} at {}", taskId, task.getTaskType(), task.getAttempts(), nextRetry);
        return false;
    }

    private boolean cancelAtProvider(String providerCode, String providerDeliveryId, String reason) {
        if (providerCode == null || providerDeliveryId == null) {
            return true;
        }
        var adapter = registry.adapter(providerCode);
        if (adapter == null) {
            log.warn("No adapter found for provider code {}", providerCode);
            return false;
        }
        try {
            adapter.cancel(providerDeliveryId, reason == null ? "Stood down" : reason);
            return true;
        } catch (DeliveryProviderException ex) {
            log.warn("Provider {} cancel failed for {}: {}", providerCode, providerDeliveryId, ex.getMessage());
            return false;
        }
    }

    private boolean executeWalletRefund(DeliveryRecoveryTask task) {
        if (task.getOutletId() == null || task.getAmount() == null) {
            return false;
        }
        String ref = task.getIdempotencyRef();
        String tag = ref != null && ref.endsWith("-adjust") ? "adjust" : "refund";
        boolean ok = wallet.refundFoodDelivery(task.getOutletId(), task.getDeliveryId(), tag, task.getAmount(), task.getReason());
        if (ok) {
            syncFoodDeliveryRefund(task);
            return true;
        }
        // If wallet entry already exists with this reference, the refund was already performed (idempotency guard)
        if (ref != null && walletTransactionExists(ref)) {
            syncFoodDeliveryRefund(task);
            return true;
        }
        return false;
    }

    private boolean walletTransactionExists(String reference) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM wallet_transaction WHERE reference = ?", Integer.class, reference);
        return count != null && count > 0;
    }

    private void syncFoodDeliveryRefund(DeliveryRecoveryTask task) {
                if (!"FOOD".equalsIgnoreCase(task.getFlow())) {
                        return;
        }
                jdbc.update("""
                                UPDATE food_delivery d
                                     SET fee_refunded_amount = (
                                                     SELECT COALESCE(SUM(w.amount), 0)
                                                         FROM wallet_transaction w
                                                        WHERE w.kind = 'FOOD_DELIVERY_REFUND'
                                                            AND w.reference LIKE CONCAT('food-delivery-', d.id, '-%')),
                                             fee_refunded_at = (
                                                     SELECT MAX(w.created_at)
                                                         FROM wallet_transaction w
                                                        WHERE w.kind = 'FOOD_DELIVERY_REFUND'
                                                            AND w.reference LIKE CONCAT('food-delivery-', d.id, '-%')),
                                             fee_refund_ref = ?
                                 WHERE d.id = ?
                                """, task.getIdempotencyRef(), task.getDeliveryId());
    }

    @Transactional
    public void markReconciliationRequired(String flow, Long deliveryId, String reason) {
        if ("FOOD".equalsIgnoreCase(flow)) {
            jdbc.update("UPDATE food_delivery SET reconciliation_required = true, reconciliation_reason = ? WHERE id = ?",
                    reason, deliveryId);
            auditService.record(null, null, "FOOD_DELIVERY_RECONCILIATION_REQUIRED", "FOOD_DELIVERY",
                    deliveryId, null, null, reason, "SYSTEM");
        } else {
            deliveries.findById(deliveryId).ifPresent(d -> {
                d.setReconciliationRequired(true);
                d.setReconciliationReason(reason);
                deliveries.save(d);
                auditService.record(null, null, "DELIVERY_RECONCILIATION_REQUIRED", "DELIVERY",
                        deliveryId, d.getStatus().name(), d.getStatus().name(), reason, "SYSTEM");
            });
        }
    }

    /** Run one batch of due pending or stale processing tasks. */
    public int processDueTasks() {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(LEASE_DURATION);
        var due = tasks.findDueTaskIds(now, staleThreshold, BATCH_SIZE);
        int count = 0;
        for (Long taskId : due) {
            try {
                if (executeTask(taskId)) {
                    count++;
                }
            } catch (Exception ex) {
                log.error("Error executing recovery task {}: {}", taskId, ex.getMessage());
            }
        }
        return count;
    }

    @Transactional(readOnly = true)
    public List<DeliveryRecoveryTask> getTasksForDelivery(String flow, Long deliveryId) {
        return tasks.findByFlowAndDeliveryIdOrderByCreatedAtAsc(flow, deliveryId);
    }

    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return tasks.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countByTaskTypeAndStatus(String taskType, String status) {
        return tasks.countByTaskTypeAndStatus(taskType, status);
    }
}
