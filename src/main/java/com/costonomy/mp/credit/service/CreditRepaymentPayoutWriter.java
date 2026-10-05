package com.costonomy.mp.credit.service;

import com.costonomy.mp.credit.domain.CreditRepayment;
import com.costonomy.mp.credit.domain.CreditRepaymentPayout;
import com.costonomy.mp.credit.repository.CreditRepaymentPayoutRepository;
import com.costonomy.mp.settlement.domain.CommissionConfiguration;
import com.costonomy.mp.settlement.service.CommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records what Mandi owes the supplier for a wallet repayment, with the commission snapshotted (D-126).
 *
 * <p>Not transactional itself: it runs inside the repayment's transaction, so the payout row exists if and only
 * if the repayment does. The rate comes from {@link CommissionService#resolve}, the resolver orders use, and is
 * stored on the row; nothing reads the configuration again for this payout.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditRepaymentPayoutWriter {

    private final CreditRepaymentPayoutRepository payouts;
    private final CommissionService commission;
    private final CreditDirectory directory;

    @Value("${costonomy.mp.credit.repayment-commission.enabled:true}")
    private boolean commissionEnabled;

    private final Set<Long> warnedStores = ConcurrentHashMap.newKeySet();

    public CreditRepaymentPayout record(CreditRepayment repayment) {
        BigDecimal amount = repayment.getAmount();
        BigDecimal rate = null;
        Long configurationId = null;
        BigDecimal commissionAmount = BigDecimal.ZERO;

        if (commissionEnabled) {
            var store = directory.store(repayment.getSupplierStoreId());
            var configuration = store == null ? java.util.Optional.<CommissionConfiguration>empty()
                    : commission.resolve(repayment.getSupplierStoreId(), store.supplierOrganizationId());
            if (configuration.isPresent()) {
                rate = configuration.get().getRatePercent();
                configurationId = configuration.get().getId();
                commissionAmount = amount.multiply(rate)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        .min(amount);
            } else if (warnedStores.add(repayment.getSupplierStoreId())) {
                log.warn("No commission rate resolves for supplier store {}; wallet repayments to it carry no commission",
                        repayment.getSupplierStoreId());
            }
        }

        var payout = new CreditRepaymentPayout();
        payout.setCreditRepaymentId(repayment.getId());
        payout.setSupplierStoreId(repayment.getSupplierStoreId());
        payout.setAmount(amount);
        payout.setCommissionRatePercent(rate);
        payout.setCommissionConfigurationId(configurationId);
        payout.setCommissionAmount(commissionAmount);
        return payouts.saveAndFlush(payout);
    }
}
