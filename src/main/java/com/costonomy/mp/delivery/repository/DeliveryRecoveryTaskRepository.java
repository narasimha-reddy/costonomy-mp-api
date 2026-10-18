package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryRecoveryTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface DeliveryRecoveryTaskRepository extends JpaRepository<DeliveryRecoveryTask, Long> {

    List<DeliveryRecoveryTask> findByStatusAndNextRetryAtBeforeOrderByNextRetryAtAsc(String status, Instant threshold);

    List<DeliveryRecoveryTask> findByFlowAndDeliveryIdOrderByCreatedAtAsc(String flow, Long deliveryId);

    List<DeliveryRecoveryTask> findByStatus(String status);

    long countByStatus(String status);

    long countByTaskTypeAndStatus(String taskType, String status);
}
