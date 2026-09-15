package com.costonomy.mp.realtime.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A single-use credential for the WebSocket handshake.
 *
 * <p>Stored hashed, like a refresh token, so the table is not a list of working
 * credentials. The plaintext exists once, in the response to the authenticated
 * request that asked for it.
 */
@Entity
@Table(name = "realtime_ticket")
@Getter
@Setter
@NoArgsConstructor
public class RealtimeTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "ticket_hash", nullable = false, columnDefinition = "char(64)")
    private String ticketHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The channels this ticket may join, as of the moment it was issued. */
    @Column(name = "channels_json", nullable = false, columnDefinition = "json")
    private String channelsJson;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set atomically on use, so a replayed ticket cannot open a second socket. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "device_id", length = 200)
    private String deviceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
