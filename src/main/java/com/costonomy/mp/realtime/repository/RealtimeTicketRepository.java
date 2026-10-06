package com.costonomy.mp.realtime.repository;

import com.costonomy.mp.realtime.domain.RealtimeTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RealtimeTicketRepository extends JpaRepository<RealtimeTicket, Long> {

    Optional<RealtimeTicket> findByTicketHash(String ticketHash);

    /** One DELETE statement, not load-then-delete-each (D-148). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from RealtimeTicket t where t.expiresAt < :before")
    int deleteExpired(@org.springframework.data.repository.query.Param("before") Instant before);
}
