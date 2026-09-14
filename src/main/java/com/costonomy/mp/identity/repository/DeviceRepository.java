package com.costonomy.mp.identity.repository;

import com.costonomy.mp.identity.domain.Device;
import com.costonomy.mp.identity.domain.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /**
     * Any device holding this push token, regardless of owner.
     *
     * <p>Queried without a user filter on purpose: the point is to find a row
     * that belongs to <em>someone else</em> so it can be reassigned rather than
     * left receiving another business's notifications.
     */
    Optional<Device> findFirstByPushToken(String pushToken);

    List<Device> findByUserIdAndStatus(Long userId, DeviceStatus status);

    Optional<Device> findByIdAndUserId(Long id, Long userId);
}
