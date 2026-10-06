package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;

/**
 * Records an attempt that produced no order, in its own transaction. A separate bean so the proxy applies:
 * the generator's transaction has rolled back by the time this runs, and the failure must outlive it.
 *
 * <p>The restaurant is told once per date and outcome, not on every hourly retry.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRunStore {

    private final SubscriptionRunWriter writer;
    private final AuditService auditService;
    private final OutboxService outbox;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Subscription sub, LocalDate date, String outcome, String reason, BigDecimal amount) {
        String previous = writer.outcomeOf(sub.getId(), date);
        writer.upsert(sub.getId(), date, outcome, reason, null, amount);

        if (outcome.equals(previous)) {
            return;
        }
        if ("FUNDING_FAILED".equals(outcome) || "SKIPPED_INVALID".equals(outcome)) {
            auditService.record(null, null, "SUBSCRIPTION_" + outcome, "SUBSCRIPTION", sub.getId(),
                    "ACTIVE", "ACTIVE", reason, "SYSTEM");

            var payload = new HashMap<String, Object>();
            payload.put("outletId", sub.getOutletId());
            payload.put("supplierStoreId", sub.getSupplierStoreId());
            payload.put("scheduledDate", date.toString());
            payload.put("paymentMethod", sub.getPaymentMethod());
            payload.put("reason", reason == null ? "" : reason);
            if (amount != null) {
                payload.put("requiredAmount", amount.toPlainString());
            }
            outbox.publish("FUNDING_FAILED".equals(outcome)
                            ? "SubscriptionFundingFailed" : "SubscriptionOrderSkipped",
                    "SUBSCRIPTION", sub.getId(), payload, null);
        }
    }
}
