package com.costonomy.mp.intent.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.intent.domain.IntentAcceptanceStatus;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentAcceptanceRepository;
import com.costonomy.mp.intent.repository.IntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * System-driven transitions on an intent — the ones with no actor behind them.
 *
 * <p>A <b>separate bean</b> from {@link IntentExpiryJob} so each row expires in
 * its own transaction. A batch transaction would mean one supplier answering
 * mid-sweep rolls back the expiry of every other request in the batch, and the
 * failure would look like a job that silently does nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentTransitions {

    private final IntentRepository intents;
    private final IntentAcceptanceRepository acceptances;
    private final AuditService auditService;
    private final OutboxService outbox;

    /**
     * Expire one intent, if it is still where the sweep found it.
     *
     * <p>Re-reads and re-checks rather than trusting the selection: between the
     * query and this call a supplier may have answered or a restaurant ordered,
     * and the transition would then be illegal. Returns false rather than throwing
     * — losing that race is the ordinary case, not a fault.
     */
    @Transactional
    public boolean expire(Long intentId, IntentStatus target) {
        var intent = intents.findById(intentId).orElse(null);
        if (intent == null || !intent.getStatus().canTransitionTo(target)) {
            return false;
        }

        Instant now = Instant.now();
        var from = intent.getStatus();
        intent.setStatus(target);
        intent.setExpiredAt(now);
        intents.save(intent);

        // An answer that can no longer be ordered against is spent. Marked here
        // rather than in its own sweep so the two rows cannot disagree about
        // whether the offer still stands.
        acceptances.findByIntentId(intentId)
                .filter(acceptance -> acceptance.getStatus() == IntentAcceptanceStatus.SUBMITTED)
                .ifPresent(acceptance -> {
                    acceptance.setStatus(IntentAcceptanceStatus.EXPIRED);
                    acceptances.save(acceptance);
                });

        auditService.record(null, null, "INTENT_" + target.name(), "INTENT", intent.getId(),
                from.name(), target.name(), "Window elapsed", "SYSTEM");

        outbox.publish("Intent" + (target == IntentStatus.EXPIRED
                        ? "Expired" : "OrderWindowExpired"),
                "INTENT", intent.getId(),
                Map.of("reference", intent.getReference(),
                        "outletId", intent.getOutletId(),
                        "supplierStoreId", intent.getSupplierStoreId()),
                null, now);

        return true;
    }
}
