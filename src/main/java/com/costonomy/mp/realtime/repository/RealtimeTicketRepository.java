package com.costonomy.mp.realtime.repository;

import com.costonomy.mp.realtime.domain.RealtimeTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RealtimeTicketRepository extends JpaRepository<RealtimeTicket, Long> {

    Optional<RealtimeTicket> findByTicketHash(String ticketHash);

    long deleteByExpiresAtBefore(Instant before);
}
