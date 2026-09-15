-- V14 — Realtime delivery of domain events.
--
-- Doc 06 §9, doc 05 §16, v2.2 §23A.21: **WebSocket → push → polling fallback.**
-- Three transports, and the thing that makes them agree is that all three read
-- the same rows. `realtime_event` is a projection of the outbox, fanned out to
-- the channels each event belongs to; the socket pushes it, the polling endpoint
-- reads it by cursor, and a reconnecting client catches up from the same place.
--
-- **The channel is the unit of authorization.** A session joins `outlet:12` or
-- `supplier-store:7` only if its user holds a grant there, and an event only
-- reaches a channel it was routed to. Getting that wrong leaks one restaurant's
-- orders to another over a transport that has no per-message audit — which is why
-- the entitlement is re-derived from grants on every handshake rather than
-- trusted from the client.
--
-- **Realtime is a hint, never the authority.** Doc 05 §16: on reconnect or cold
-- start the client refreshes authoritative state over REST. Nothing here is the
-- only place a fact lives, and a client that missed every message must end up in
-- the same place as one that received them all.

-- A short-lived, single-use credential for the WebSocket handshake.
--
-- **Why not just send the access token?** A browser's WebSocket API cannot set
-- headers, so the token would have to go in the query string — and query strings
-- end up in access logs, proxy logs and error reports. Doc 09 forbids logging a
-- token, and a URL is the one place you cannot promise it will not be. A ticket
-- is issued over an authenticated request, lives for seconds, is accepted once,
-- and is stored hashed so the table is not a list of working credentials.
CREATE TABLE realtime_ticket (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- SHA-256 of the ticket. The plaintext is returned once and never stored,
    -- the same treatment refresh tokens get in V1.
    ticket_hash     CHAR(64)     NOT NULL,
    user_id         BIGINT       NOT NULL,
    -- The channels this ticket may join, resolved from the user's grants at issue
    -- time. Snapshotted so the socket cannot outlive a revoked grant by more than
    -- the ticket's lifetime — and the connection re-checks on every join.
    channels_json   JSON         NOT NULL,
    issued_at       TIMESTAMP(6) NOT NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    -- Single use. Set atomically, so a replayed ticket cannot open a second socket.
    consumed_at     TIMESTAMP(6) NULL,
    device_id       VARCHAR(200) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_realtime_ticket_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_realtime_ticket_hash UNIQUE (ticket_hash),
    KEY ix_realtime_ticket_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One outbox event, on one channel. Append-only.
--
-- A single domain event usually belongs to two channels — a supplier order
-- concerns the restaurant that placed it and the store filling it — so this is
-- per (event, channel) rather than per event. The row id is the cursor every
-- transport shares: the socket pushes it, `GET /realtime/events?since=` reads
-- forward from it, and a client that was offline resumes from the last id it saw.
CREATE TABLE realtime_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- The outbox event this came from. With `channel`, the deduplication key:
    -- the relay is at-least-once, so it will re-offer events it already fanned out.
    event_id        CHAR(36)     NOT NULL,
    channel         VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    payload         JSON         NOT NULL,
    occurred_at     TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_realtime_event_channel UNIQUE (event_id, channel),
    -- The only query any transport makes: this channel, after this cursor.
    KEY ix_realtime_event_channel (channel, id),
    KEY ix_realtime_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
