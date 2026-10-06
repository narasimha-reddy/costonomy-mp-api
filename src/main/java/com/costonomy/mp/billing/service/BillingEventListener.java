package com.costonomy.mp.billing.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Issues a doorstep credit note once the check-in has committed. Runs from the outbox, so it is after the commit by
 * construction, and does nothing while the tax-invoice feature is off. A failure is logged and never propagates:
 * the credit note is picked up again when the order's invoice is generated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingEventListener {

    private final BillingFeature feature;
    private final CreditNoteIssuer creditNotes;

    @EventListener
    public void onDomainEvent(OutboxPublisher.DomainEventEnvelope envelope) {
        if (!feature.enabled()
                || !"ReceivingCompleted".equals(envelope.eventType())
                || !"SUPPLIER_ORDER".equals(envelope.aggregateType())) {
            return;
        }
        try {
            creditNotes.issueForDoorstep(envelope.aggregateId());
        } catch (RuntimeException ex) {
            log.error("Credit note for order {} could not be issued", envelope.aggregateId(), ex);
        }
    }
}
