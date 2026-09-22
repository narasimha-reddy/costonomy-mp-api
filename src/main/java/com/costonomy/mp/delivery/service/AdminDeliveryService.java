package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryLedgerEntry;
import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import com.costonomy.mp.delivery.repository.DeliveryLedgerRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderStatsRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Operations &amp; Admin service for delivery inspection and carrier management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDeliveryService {

    private final AccessControlService accessControl;
    private final DeliveryRepository deliveries;
    private final DeliveryLedgerRepository ledgerRepository;
    private final DeliveryProviderStatsRepository statsRepository;
    private final DeliveryWaterfallService waterfallService;
    private final DeliveryService deliveryService;

    @Transactional(readOnly = true)
    public List<DeliveryDtos.DeliveryLedgerResponse> getLedger(Long actorId, Long deliveryId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);

        if (!deliveries.existsById(deliveryId)) {
            throw new NotFoundException("Delivery", deliveryId);
        }

        return ledgerRepository.findByDeliveryIdOrderByCreatedAtAsc(deliveryId).stream()
                .map(this::toLedgerResponse)
                .toList();
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse forceWaterfall(Long actorId, Long deliveryId, String reason) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);

        log.info("Admin actor {} manually forcing delivery waterfall for delivery {}: {}", actorId, deliveryId, reason);
        waterfallService.forceEscalate(deliveryId, reason);

        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        return deliveryService.toResponse(delivery, null);
    }

    /**
     * Rolling 30-day reliability stats across all providers.
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderStatsResponse> getLast30DaysStats(Long actorId) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        LocalDate since = LocalDate.now().minusDays(30);
        return statsRepository.findSince(since).stream()
                .map(this::toStatsResponse)
                .toList();
    }

    /**
     * All daily rows for one provider (for trend analysis / charting).
     * Requires {@code DELIVERY_INSPECT} at PLATFORM scope.
     */
    @Transactional(readOnly = true)
    public List<DeliveryDtos.ProviderStatsResponse> getProviderStats(Long actorId, String providerCode) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        return statsRepository.findByProviderCodeOrderByWindowDateDesc(providerCode).stream()
                .map(this::toStatsResponse)
                .toList();
    }

    private DeliveryDtos.DeliveryLedgerResponse toLedgerResponse(DeliveryLedgerEntry entry) {
        return new DeliveryDtos.DeliveryLedgerResponse(
                entry.getId(),
                entry.getDeliveryId(),
                entry.getProviderCode(),
                entry.getProviderDeliveryId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getCreatedAt());
    }

    private DeliveryDtos.ProviderStatsResponse toStatsResponse(DeliveryProviderStats s) {
        return new DeliveryDtos.ProviderStatsResponse(
                s.getProviderCode(),
                s.getWindowDate(),
                s.getTotalBookings(),
                s.getDriverCancellations(),
                s.getPickupFailures(),
                s.getDeliveryFailures(),
                s.getEtaOverruns(),
                s.getCompletedDeliveries(),
                round1dp(s.cancellationRate() * 100),
                round1dp(s.etaBreachRate() * 100),
                round1dp(s.overallFailureRate() * 100),
                s.getAvgActualEtaMinutes(),
                s.getAvgQuotedEtaMinutes(),
                s.getAvgPriceDeviationInr());
    }

    private static double round1dp(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
