package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Test-only: move a Pidge SANDBOX delivery to its next stage (D-154).
 *
 * <p>Pidge's sandbox has no riders, so nothing ever happens to a booking unless something asks Pidge's own dummy
 * endpoint for the next stage. The order object it returns goes through {@link PidgeWebhookService#process}, the same
 * code a real webhook runs after its signature check, so the delivery, the rider, the order and the buyer's screen
 * all move exactly as they would for a real rider.
 *
 * <p>Not transactional on purpose: the call to Pidge is an HTTP round trip and must not hold a database transaction
 * open; {@code process} runs in its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PidgeSandboxService {

    private final DeliveryRepository deliveries;
    private final DeliveryService deliveryService;
    private final AccessControlService accessControl;
    private final PidgeProperties properties;
    private final PidgeApiClient client;
    private final PidgeWebhookService webhook;

    public DeliveryDtos.DeliveryResponse advance(Long actorId, Long deliveryId) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, delivery.getSupplierStoreId(), "Delivery");

        // With the sandbox off this route does not exist, so production never reveals it.
        if (!properties.isSandbox()) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        if (delivery.getMode() != DeliveryMode.COSTONOMY) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The supplier carries this delivery, so there is no rider to move.");
        }
        // Another partner's delivery is not ours to simulate.
        if (!PidgeSandboxStages.isPidge(delivery) || delivery.getProviderDeliveryId() == null) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        var dummy = PidgeSandboxStages.nextDummyStatus(delivery.getStatus()).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This delivery is " + delivery.getStatus() + " and has no next step."));

        var body = client.simulateOrderStatus(delivery.getProviderDeliveryId(), dummy);
        var order = body == null ? null : body.path("data");
        if (order == null || order.isMissingNode() || order.isNull()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The sandbox gave no order to apply.");
        }
        log.info("Pidge sandbox advance: delivery {} {} -> '{}'", deliveryId, delivery.getStatus(), dummy);
        webhook.process(order);
        return deliveryService.get(actorId, deliveryId);
    }
}
