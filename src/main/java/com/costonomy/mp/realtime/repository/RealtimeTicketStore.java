package com.costonomy.mp.realtime.repository;

import com.costonomy.mp.realtime.domain.RealtimeTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Claims a handshake ticket, in its own transaction.
 *
 * <p>The same rule as {@code OtpAttemptStore} and {@code IdempotencyStore}
 * (D-016): a single-use credential is only single-use if the claim is an
 * <b>atomic conditional UPDATE</b> that cannot be repeated. A read-then-write
 * would let two simultaneous handshakes with one stolen ticket both find it
 * unconsumed and both open a socket — which is precisely the replay the ticket
 * exists to stop.
 *
 * <p>{@code REQUIRES_NEW} so the claim survives whatever happens to the handshake
 * afterwards. A ticket that was offered and rejected must stay spent.
 */
@Service
@RequiredArgsConstructor
public class RealtimeTicketStore {

    private final JdbcTemplate jdbc;
    private final RealtimeTicketRepository tickets;

    /**
     * Spend the ticket.
     *
     * @return the ticket if this caller won the claim; null if it was already
     *         used, has expired, or never existed — all three deliberately
     *         indistinguishable to the caller
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RealtimeTicket claim(String ticketHash) {
        int applied = jdbc.update("""
                update realtime_ticket
                   set consumed_at = utc_timestamp(6)
                 where ticket_hash = ?
                   and consumed_at is null
                   and expires_at > utc_timestamp(6)
                """, ticketHash);

        if (applied != 1) {
            return null;
        }
        return tickets.findByTicketHash(ticketHash).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(RealtimeTicket ticket) {
        tickets.save(ticket);
    }

    /** Housekeeping. Spent and expired tickets are of no further use to anyone. */
    @Transactional
    public long purgeExpired(Instant before) {
        return tickets.deleteByExpiresAtBefore(before);
    }
}
