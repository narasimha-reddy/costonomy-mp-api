package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.identity.domain.Device;
import com.costonomy.mp.identity.domain.DeviceStatus;
import com.costonomy.mp.identity.repository.DeviceRepository;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Registers app installs for push. Doc 04 §3. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository repository;

    /**
     * Register or update a device.
     *
     * <p>A push token identifies one app install, not one user. If the same token
     * is already registered to someone else — a shared handset, a reinstall after
     * a account switch — the existing row is <b>reassigned</b> rather than
     * duplicated. Leaving it would mean the previous user keeps receiving this
     * user's notifications, and for a procurement app those notifications carry
     * order values and supplier names between unrelated businesses.
     */
    @Transactional
    public Device register(Long userId, AuthDtos.DeviceRequest request) {
        Instant now = Instant.now();

        Device device = null;
        if (request.pushToken() != null && !request.pushToken().isBlank()) {
            device = repository.findFirstByPushToken(request.pushToken()).orElse(null);
            if (device != null && !device.getUserId().equals(userId)) {
                log.info("Reassigning push token from user {} to user {}",
                        device.getUserId(), userId);
                device.setUserId(userId);
            }
        }

        if (device == null) {
            device = new Device();
            device.setUserId(userId);
        }

        device.setPlatform(request.platform());
        device.setPushToken(request.pushToken());
        device.setAppVersion(request.appVersion());
        device.setDeviceModel(request.deviceModel());
        device.setOsVersion(request.osVersion());
        device.setStatus(DeviceStatus.ACTIVE);
        device.setLastSeenAt(now);

        return repository.save(device);
    }

    /**
     * Unregister on logout.
     *
     * <p>Scoped by user id, so passing someone else's device id is a not-found
     * rather than a successful unregistration of their device (doc 09 §3).
     */
    @Transactional
    public void unregister(Long userId, Long deviceId) {
        Device device = repository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new NotFoundException("Device", deviceId));

        device.setStatus(DeviceStatus.INACTIVE);
        // Cleared so a stale token cannot be pushed to after sign-out.
        device.setPushToken(null);
        repository.save(device);
    }

    @Transactional(readOnly = true)
    public List<Device> activeDevices(Long userId) {
        return repository.findByUserIdAndStatus(userId, DeviceStatus.ACTIVE);
    }

    public static AuthDtos.DeviceResponse toResponse(Device device) {
        return new AuthDtos.DeviceResponse(
                device.getId(),
                device.getPlatform(),
                device.getAppVersion(),
                device.getStatus().name(),
                device.getLastSeenAt());
    }
}
