package com.costonomy.mp.billing.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues a doorstep credit note once the check-in has committed. Runs from the outbox, so it is after the commit by
 * construction, and does nothing while the tax-invoice feature is off. A failure is logged and never propagates (the credit note is issued in a transaction of its own, so it cannot mark the outbox event's transaction rollback-only):
 * the credit note is picked up again when the order's invoice is generated.
 */
@Component
@Slf4j
public class BillingEventListener {

    private final BillingFeature feature;
    private final CreditNoteIssuer creditNotes;
    /**
     * Its own transaction: joining the event's would let a failure here mark the outbox transaction rollback-only
     * even though the exception is swallowed, and the event would be retried (D-194).
     */
    private final TransactionTemplate isolated;

    public BillingEventListener(BillingFeature feature, CreditNoteIssuer creditNotes,
                                PlatformTransactionManager transactionManager) {
        this.feature = feature;
        this.creditNotes = creditNotes;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onDomainEvent(OutboxPublisher.DomainEventEnvelope envelope) {
        if (!feature.enabled()
                || !"ReceivingCompleted".equals(envelope.eventType())
                || !"SUPPLIER_ORDER".equals(envelope.aggregateType())) {
            return;
        }
        try {
            isolated.executeWithoutResult(status -> creditNotes.issueForDoorstep(envelope.aggregateId()));
        } catch (RuntimeException ex) {
            log.error("Credit note for order {} could not be issued", envelope.aggregateId(), ex);
        }
    }
}
