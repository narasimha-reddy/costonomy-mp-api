package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims one automatic retry in its own small transaction (D-185). Its own bean, so the proxy applies (D-021): the
 * claim commits before the provider is called, and a provider failure cannot roll the count back and have the next
 * sweep hammer the same partners again.
 */
@Service
@RequiredArgsConstructor
class DeliveryRetryClaims {

    private final DeliveryRepository deliveries;

    @Transactional
    public boolean claim(Long deliveryId, long windowSeconds, long intervalSeconds, int maxRetries) {
        return deliveries.claimRetry(deliveryId, windowSeconds, intervalSeconds, maxRetries) == 1;
    }
}
