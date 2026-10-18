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
import java.time.Instant;
import java.util.List;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecoveryTask scheduleCancellation(String flow, Long deliveryId, String providerCode,
                                                     String providerDeliveryId, String reason) {
        log.info("Scheduling durable cancellation task for {} delivery {} provider {} consignment {}",
                flow, deliveryId, providerCode, providerDeliveryId);
        var task = new DeliveryRecoveryTask();
        task.setFlow(flow);
        task.setDeliveryId(deliveryId);
        task.setTaskType("PROVIDER_CANCEL");
        task.setProviderCode(providerCode);
        task.setProviderDeliveryId(providerDeliveryId);
        task.setReason(reason);
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setMaxAttempts(5);
        task.setNextRetryAt(Instant.now());
        return tasks.save(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRecoveryTask scheduleRefund(Long foodDeliveryId, Long outletId, String refundTag,
                                               BigDecimal amount, String reason) {
        log.info("Scheduling durable refund task for food delivery {} outlet {} amount {} tag {}",
                foodDeliveryId, outletId, amount, refundTag);
        var task = new DeliveryRecoveryTask();
        task.setFlow("FOOD");
        task.setDeliveryId(foodDeliveryId);
        task.setOutletId(outletId);
        task.setTaskType("WALLET_REFUND");
        task.setIdempotencyRef("food-delivery-" + foodDeliveryId + "-" + refundTag);
        task.setAmount(amount);
        task.setReason(reason);
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setMaxAttempts(5);
        task.setNextRetryAt(Instant.now());
        return tasks.save(task);
    }

    /**
     * Executes one recovery task outside long-running database transactions.
     */
    public boolean executeTask(Long taskId) {
        var task = tasks.findById(taskId).orElse(null);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return false;
        }

        task.setStatus("PROCESSING");
        task.setAttempts(task.getAttempts() + 1);
        tasks.save(task);

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

        Instant now = Instant.now();
        if (success) {
            task.setStatus("COMPLETED");
            task.setLastError(null);
            tasks.save(task);
            log.info("Recovery task {} ({}) completed successfully", taskId, task.getTaskType());
            return true;
        }

        task.setLastError(errorMessage != null ? errorMessage : "Operation unsuccessful");
        if (task.getAttempts() >= task.getMaxAttempts()) {
            task.setStatus("EXHAUSTED");
            tasks.save(task);
            markReconciliationRequired(task.getFlow(), task.getDeliveryId(),
                    "Recovery task %s (%s) exhausted after %d attempts: %s"
                            .formatted(task.getId(), task.getTaskType(), task.getAttempts(), task.getLastError()));
            log.error("Recovery task {} ({}) exhausted after {} attempts", taskId, task.getTaskType(), task.getAttempts());
            return false;
        }

        // Exponential backoff: 30s * 2^(attempts-1)
        long delaySeconds = Math.min(1800, 30L * (1L << (task.getAttempts() - 1)));
        task.setStatus("PENDING");
        task.setNextRetryAt(now.plusSeconds(delaySeconds));
        tasks.save(task);
        log.info("Recovery task {} ({}) scheduled for retry {} at {}", taskId, task.getTaskType(), task.getAttempts(), task.getNextRetryAt());
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
        return wallet.refundFoodDelivery(task.getOutletId(), task.getDeliveryId(), tag, task.getAmount(), task.getReason());
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

    /** Run one batch of due pending tasks. */
    public int processDueTasks() {
        var due = tasks.findByStatusAndNextRetryAtBeforeOrderByNextRetryAtAsc("PENDING", Instant.now());
        int count = 0;
        for (var task : due) {
            try {
                if (executeTask(task.getId())) {
                    count++;
                }
            } catch (Exception ex) {
                log.error("Error executing recovery task {}: {}", task.getId(), ex.getMessage());
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
