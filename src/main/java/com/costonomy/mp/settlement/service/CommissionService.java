package com.costonomy.mp.settlement.service;

import com.costonomy.mp.settlement.domain.CommissionCalculation;
import com.costonomy.mp.settlement.domain.CommissionConfiguration;
import com.costonomy.mp.settlement.repository.CommissionCalculationRepository;
import com.costonomy.mp.settlement.repository.CommissionConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * What a supplier owes on one order. Doc 01 §16, doc 09 §11.
 *
 * <p>Three rules, and each one is a way a marketplace quietly overcharges or
 * undercharges its suppliers.
 *
 * <p><b>The base is item value plus GST, excluding delivery.</b> Doc 01 §16.
 * Commission on a delivery fee would be commission on money that passes straight
 * through to a courier.
 *
 * <p><b>Only the accepted portion.</b> A partial acceptance owes commission on
 * what was actually supplied, not on what was ordered — the supplier was not paid
 * for the rest.
 *
 * <p><b>The rate is snapshotted, never re-read.</b> Doc 05 §33: a historical
 * settlement must not depend on current configuration. Recomputing from
 * {@link CommissionConfiguration} at read time would make every past figure a
 * function of today's table, and a statement printed twice would disagree with
 * itself the moment a rate was renegotiated.
 *
 * <p><b>Commission is never a ranking input</b> (guardrail 9), which is why
 * nothing in {@code discovery} depends on this class and nothing here is exposed
 * to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommissionService {

    private final CommissionConfigurationRepository configurations;
    private final CommissionCalculationRepository calculations;

    /**
     * Calculate once, for one order.
     *
     * <p>Idempotent: an existing calculation is returned rather than replaced.
     * {@code uk_commission_calc_order} is the guard behind that — charging a
     * supplier twice for one order is the failure worth making impossible rather
     * than merely unlikely.
     */
    @Transactional
    public CommissionCalculation calculate(SettlementDirectory.SettleableOrder order) {
        var existing = calculations.findBySupplierOrderId(order.orderId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        // Delivery out of the base. It is currently zero on every order — the fee
        // lives on the delivery record — but subtracting it is what keeps this
        // correct if it is ever folded into the order total.
        BigDecimal gross = order.acceptedAmount()
                .subtract(order.deliveryFee() == null ? BigDecimal.ZERO : order.deliveryFee())
                .max(BigDecimal.ZERO);

        var configuration = resolve(order.supplierStoreId(), order.supplierOrganizationId());
        BigDecimal rate = configuration
                .map(CommissionConfiguration::getRatePercent)
                .orElse(BigDecimal.ONE);

        BigDecimal commission = gross.multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        var calculation = new CommissionCalculation();
        calculation.setSupplierOrderId(order.orderId());
        calculation.setSupplierStoreId(order.supplierStoreId());
        calculation.setSupplierOrganizationId(order.supplierOrganizationId());
        calculation.setGrossAmount(gross.setScale(2, RoundingMode.HALF_UP));
        calculation.setRatePercent(rate);
        calculation.setCommissionConfigurationId(
                configuration.map(CommissionConfiguration::getId).orElse(null));
        calculation.setCommissionAmount(commission);
        calculation.setNetAmount(gross.subtract(commission).setScale(2, RoundingMode.HALF_UP));
        calculation.setCalculatedAt(Instant.now());

        return calculations.save(calculation);
    }

    /**
     * The rate that applies now, most specific first.
     *
     * <p>Store, then organisation, then the platform default — so a negotiated
     * rate for one supplier is a row rather than a code change, and a store-level
     * arrangement wins over its parent's.
     */
    @Transactional(readOnly = true)
    public Optional<CommissionConfiguration> resolve(Long storeId, Long organisationId) {
        Instant now = Instant.now();
        var active = configurations.findByStatusOrderByIdDesc("ACTIVE").stream()
                .filter(configuration -> !configuration.getEffectiveFrom().isAfter(now))
                .filter(configuration -> configuration.getEffectiveTo() == null
                        || configuration.getEffectiveTo().isAfter(now))
                .toList();

        return active.stream()
                .filter(configuration -> matches(configuration, storeId, organisationId))
                // Most specific wins; the newest version breaks a tie, because a
                // superseded row should already have an effective_to.
                .min(Comparator.comparingInt((CommissionConfiguration configuration) ->
                                specificity(configuration.getScopeType()))
                        .thenComparing(CommissionConfiguration::getConfigVersion,
                                Comparator.reverseOrder()));
    }

    private boolean matches(CommissionConfiguration configuration, Long storeId, Long orgId) {
        return switch (configuration.getScopeType()) {
            case "SUPPLIER_STORE" -> storeId.equals(configuration.getScopeId());
            case "SUPPLIER" -> orgId.equals(configuration.getScopeId());
            case "PLATFORM" -> true;
            default -> false;
        };
    }

    private int specificity(String scopeType) {
        return switch (scopeType) {
            case "SUPPLIER_STORE" -> 0;
            case "SUPPLIER" -> 1;
            default -> 2;
        };
    }
}
