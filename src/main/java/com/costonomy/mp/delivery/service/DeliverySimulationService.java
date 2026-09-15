package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Drives a mock delivery forward. Doc 06 §11.
 *
 * <p><b>One transaction, one entity.</b> A simulated event touches the delivery
 * several times — the driver, the position, the ETA, the status — and each of those
 * is its own write. Done from a controller with no transaction, every call
 * re-attaches a <em>stale detached copy</em> of the delivery, so the second write
 * silently loses the first and the status never moves. That is not a test-only
 * hazard: any caller stringing these together outside a transaction has the same
 * bug, which is why the sequencing lives here rather than in the controller.
 *
 * <p><b>Protected twice over.</b> Doc 06 §11 requires simulation to be unavailable
 * to normal users: the caller needs {@code DELIVERY_OPERATE} at {@code PLATFORM},
 * an internal permission no tenant role holds, <em>and</em> the delivery's provider
 * must actually be a mock. The second gate matters because an operator does have
 * the permission, and fabricating an event about a real vehicle is exactly what
 * doc 06 §8 forbids.
 */
@Service
@RequiredArgsConstructor
public class DeliverySimulationService {

    private final DeliveryRepository deliveries;
    private final DeliveryEventService eventService;
    private final DeliveryProviderRegistry registry;
    private final AccessControlService accessControl;

    @Transactional
    public void simulate(Long actorId, Long deliveryId, DeliveryDtos.SimulateEventRequest body) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);

        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        var adapter = registry.adapter(delivery.getProviderCode());
        if (!(adapter instanceof MockDeliveryProvider mock)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This delivery isn't running on a mock partner.");
        }

        var status = parse(body.status());
        String providerDeliveryId = delivery.getProviderDeliveryId();

        // The driver first: a position or a status change for a delivery with no
        // driver would be refused by the checks downstream.
        if (status == DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED) {
            mock.simulate(providerDeliveryId, status, body.description());
            var state = mock.status(providerDeliveryId);
            eventService.recordDriver(delivery, state.driverName(),
                    state.driverPhone(), state.driverVehicle());
            eventService.apply(delivery, state.events().get(0));
        } else {
            eventService.apply(delivery,
                    mock.simulate(providerDeliveryId, status, body.description()));
        }

        if (body.latitude() != null && body.longitude() != null) {
            mock.simulateLocation(providerDeliveryId, body.latitude(), body.longitude(),
                    Instant.now());
            var location = mock.location(providerDeliveryId);
            eventService.recordLocation(delivery, location.latitude(), location.longitude(),
                    location.bearing(), location.speedKmph(), location.recordedAt());
        }
        if (body.etaMinutes() != null) {
            mock.simulateEta(providerDeliveryId, body.etaMinutes());
            eventService.recordEta(delivery, body.etaMinutes());
        }
    }

    /** The order a delivery belongs to, so an operator can read it back. */
    @Transactional(readOnly = true)
    public Long orderIdOf(Long deliveryId) {
        return deliveries.findById(deliveryId)
                .map(com.costonomy.mp.delivery.domain.Delivery::getSupplierOrderId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
    }

    private DeliveryProvider.ProviderDeliveryStatus parse(String value) {
        try {
            return DeliveryProvider.ProviderDeliveryStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unknown delivery status " + value);
        }
    }
}
