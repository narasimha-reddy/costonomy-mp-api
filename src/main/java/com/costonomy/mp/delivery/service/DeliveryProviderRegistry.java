package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The couriers we can actually dispatch to right now.
 *
 * <p>Two halves have to agree: an adapter must exist on the classpath, and a row
 * must be enabled in {@code delivery_provider}. A row with no adapter is a
 * configuration mistake rather than a courier, and an adapter with no row is one
 * someone deliberately turned off — both are excluded, and the first is logged
 * because it is nearly always a deploy that went out half-done.
 */
@Service
@Slf4j
public class DeliveryProviderRegistry {

    private final Map<String, DeliveryProvider> adapters;
    private final DeliveryProviderRepository records;

    public DeliveryProviderRegistry(List<DeliveryProvider> adapters,
                                    DeliveryProviderRepository records) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(DeliveryProvider::code, Function.identity()));
        this.records = records;
        log.info("Delivery adapters available: {}", this.adapters.keySet());
    }

    /** An enabled provider and its adapter, in tie-break order. */
    public record Available(DeliveryProviderRecord record, DeliveryProvider adapter) {
    }

    public List<Available> enabled() {
        return records.findByEnabledTrueOrderByPriorityAsc().stream()
                .map(record -> {
                    var adapter = adapters.get(record.getCode());
                    if (adapter == null) {
                        log.warn("Delivery provider {} is enabled but has no adapter",
                                record.getCode());
                        return null;
                    }
                    return new Available(record, adapter);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public DeliveryProvider adapter(String code) {
        return adapters.get(code);
    }

    public DeliveryProviderRecord record(String code) {
        return records.findByCode(code).orElse(null);
    }
}
