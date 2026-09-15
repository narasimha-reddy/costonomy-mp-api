package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {
}
