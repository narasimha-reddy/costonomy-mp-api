package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.CourierPayableEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourierPayableEventRepository extends JpaRepository<CourierPayableEvent, Long> {

    List<CourierPayableEvent> findByPayableIdOrderByIdAsc(Long payableId);

    long countByPayableIdAndEventType(Long payableId, String eventType);
}
