package com.costonomy.mp.intent.service;

import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Closes requests that ran out of time. §20.
 *
 * <p>Two sweeps, because there are two ways a request dies of old age and they
 * are not the same event:
 *
 * <ul>
 *   <li><b>Unanswered</b> — the supplier never replied inside the response
 *       window. The restaurant needs to know so they can source elsewhere, and
 *       the supplier's response rate should record that they did not answer.</li>
 *   <li><b>Answered but never ordered from</b> — the supplier did their part and
 *       the restaurant let the window lapse. That is
 *       {@code ORDER_CREATION_EXPIRED}, and conflating it with the first would
 *       blame the supplier for the restaurant's delay.</li>
 * </ul>
 *
 * <p><b>Neither sweep is the authority on expiry.</b> {@code IntentOrderCreator}
 * refuses to create an order outside the window whether or not this has run, and
 * {@code IntentResponder} refuses an answer to an intent that is not {@code OPEN}.
 * These jobs move the state so the right thing is <i>displayed</i>; correctness
 * does not depend on their timing.
 *
 * <p>One row per transaction, each failure isolated — the reasoning is the same as
 * {@code SupplierOrderTimeoutJob}, and so is the reason for
 * {@code lockAtLeastFor = PT0S}: both sweeps are idempotent, so a double run
 * costs nothing while a skipped run leaves somebody waiting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentExpiryJob {

    private final IntentRepository intents;
    private final IntentTransitions transitions;

    @Scheduled(fixedDelayString = "${costonomy.mp.intents.expiry-poll-interval:PT30S}")
    @SchedulerLock(name = "intent-expiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweep() {
        expireUnanswered();
        expireUnordered();
    }

    /** Sent, never answered, past the deadline the store promised. */
    private void expireUnanswered() {
        int expired = sweep(
                intents.findStaleOpen(Instant.now()).stream()
                        .map(intent -> intent.getId()).toList(),
                IntentStatus.EXPIRED);
        if (expired > 0) {
            log.info("Intent expiry sweep: {} requests went unanswered", expired);
        }
    }

    /** Answered, and the restaurant's window to order has passed. */
    private void expireUnordered() {
        int expired = sweep(
                intents.findPastOrderWindow(Instant.now()).stream()
                        .map(intent -> intent.getId()).toList(),
                IntentStatus.ORDER_CREATION_EXPIRED);
        if (expired > 0) {
            log.info("Intent expiry sweep: {} answers were not ordered from in time", expired);
        }
    }

    private int sweep(java.util.List<Long> ids, IntentStatus target) {
        int moved = 0;
        for (Long id : ids) {
            try {
                if (transitions.expire(id, target)) {
                    moved++;
                }
            } catch (OptimisticLockingFailureException ex) {
                // The other side acted in the same instant — a supplier answering,
                // a restaurant ordering. That race resolving to them is a correct
                // outcome, not an error.
                log.debug("Intent {} changed while expiring", id);
            } catch (RuntimeException ex) {
                // One bad row must not stop the sweep, or a single poison request
                // would leave every later one hanging indefinitely.
                log.error("Could not expire intent {}", id, ex);
            }
        }
        return moved;
    }
}
