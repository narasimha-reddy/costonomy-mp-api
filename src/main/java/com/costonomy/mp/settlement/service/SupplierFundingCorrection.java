package com.costonomy.mp.settlement.service;

import com.costonomy.mp.delivery.domain.SupplierShareReduced;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.service.GatewayRefundService;
import com.costonomy.mp.settlement.domain.SupplierDeduction;
import com.costonomy.mp.settlement.repository.SupplierDeductionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Never charge a supplier more than the courier cost (D-204). When the courier's final cost comes in under what the
 * supplier was charged, the excess goes back the way it was collected:
 * <ul>
 *   <li><b>still pending</b>: the charge is reduced (or waived if the share is now nothing);</li>
 *   <li><b>taken from a payout</b>: a credit for the excess, applied by the store's next settlement (an approved
 *       settlement is frozen, so the correction belongs to the next one);</li>
 *   <li><b>paid directly</b>: a partial refund at the gateway, recorded before it is sent.</li>
 * </ul>
 * Runs in the transaction that learned the lower cost, so the correction and the new figure commit together.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplierFundingCorrection {

    private final SupplierDeductionRepository deductions;
    private final GatewayRefundService gatewayRefunds;

    @EventListener
    public void onShareReduced(SupplierShareReduced e) {
        var d = deductions.findByDeliveryId(e.deliveryId()).orElse(null);
        if (d == null || !SupplierDeduction.KIND_DELIVERY_FUNDING.equals(d.getKind())) {
            return;
        }
        BigDecimal excess = d.getAmount().subtract(e.newShare());
        if (excess.signum() <= 0) {
            return;
        }
        switch (d.getStatus()) {
            case SupplierDeduction.PENDING -> {
                if (e.newShare().signum() == 0) {
                    d.setStatus(SupplierDeduction.WAIVED);
                } else {
                    d.setAmount(e.newShare());
                }
                deductions.save(d);
            }
            case SupplierDeduction.APPLIED -> {
                BigDecimal credited = deductions.sumCreditsFor(d.getId());
                BigDecimal still = excess.subtract(credited);
                if (still.signum() > 0) {
                    var credit = new SupplierDeduction();
                    credit.setSupplierStoreId(d.getSupplierStoreId());
                    credit.setSupplierOrderId(d.getSupplierOrderId());
                    credit.setKind(SupplierDeduction.KIND_DELIVERY_FUNDING_CREDIT);
                    credit.setCorrectsDeductionId(d.getId());
                    credit.setAmount(still);
                    credit.setReason("The courier cost less than your share of the delivery");
                    deductions.save(credit);
                    log.info("Credit of {} queued for supplier store {}: delivery {} cost less than the share charged",
                            still, d.getSupplierStoreId(), e.deliveryId());
                }
            }
            case SupplierDeduction.PAID_DIRECTLY -> {
                BigDecimal back = gatewayRefunds.forSource(GatewayRefund.SUPPLIER_DELIVERY_FUNDING, d.getId()).stream()
                        .filter(r -> "FEE_ADJUSTMENT".equals(r.getPurpose()))
                        .map(GatewayRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal still = excess.subtract(back);
                if (still.signum() > 0 && d.getPaymentId() != null) {
                    int n = (int) gatewayRefunds.forSource(GatewayRefund.SUPPLIER_DELIVERY_FUNDING, d.getId()).stream()
                            .filter(r -> "FEE_ADJUSTMENT".equals(r.getPurpose())).count() + 1;
                    var g = gatewayRefunds.record(new GatewayRefundService.Request(GatewayRefund.SUPPLIER_DELIVERY_FUNDING,
                            d.getId(), "FEE_ADJUSTMENT", d.getPaymentId(), d.getAmount(), still,
                            "The courier cost less than your share of the delivery",
                            "del-funding-adjust-" + d.getId() + "-" + n));
                    gatewayRefunds.sendSoon(g.getId());
                }
            }
            default -> { }
        }
    }
}
