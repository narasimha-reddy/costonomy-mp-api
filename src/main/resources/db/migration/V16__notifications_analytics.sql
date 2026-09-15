-- V16 — Notifications and analytics.
--
-- Doc 08 in full, doc 02 §3, doc 04 §18, §23A.27.
--
-- **Two different things share this migration because they share one source.**
-- Both are driven by events: a notification is an event someone needs to be told
-- about, an analytics row is an event worth counting. Neither is written by the
-- module that caused it — see `NotificationDispatcher` for why that matters.
--
-- **In-app and outbound are separated on purpose.** The `notification` row is the
-- thing §23A.27 lists, groups and marks read; it exists the moment it is created
-- and is never "delivered" in any sense a phone would recognise. Doc 08 §6's
-- `CREATED → QUEUED → SENT → DELIVERED` describes a push or an SMS leaving the
-- building, which can fail and be retried — so that lifecycle lives on
-- `notification_delivery`, one row per channel attempted. Collapsing the two
-- would make "did they read it" and "did the network take it" the same column.

-- What a user is told. The in-app inbox.
CREATE TABLE notification (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    -- ORDERS, APPROVALS, PAYMENTS, CREDIT, DELIVERY, MARKETPLACE. §23A.27 groups
    -- the inbox by these, so the grouping is decided here rather than by a client
    -- pattern-matching on event names.
    category        VARCHAR(32)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    -- The outbox event this came from. With user_id, the deduplication key: the
    -- relay is at-least-once and will re-offer events it has already fanned out.
    event_id        CHAR(36)     NULL,

    title           VARCHAR(200) NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    -- Where tapping it goes (§23A.27). A route, not a URL: the app owns its own
    -- navigation, and a server-built deep link would break on every redesign.
    target_type     VARCHAR(64)  NULL,
    target_id       BIGINT       NULL,

    -- Doc 08 §5: critical notifications ignore preferences. Stored per row rather
    -- than looked up from the event type at read time, so a rule change tomorrow
    -- cannot retrospectively reclassify what was already sent.
    critical        TINYINT(1)   NOT NULL DEFAULT 0,
    read_at         TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- One notification per user per event. Nulls are distinct in MySQL, so
    -- notifications we raise ourselves (no event id) are unconstrained.
    CONSTRAINT uk_notification_user_event UNIQUE (user_id, event_id),
    -- The inbox query: mine, newest first, optionally unread only.
    KEY ix_notification_user (user_id, created_at),
    KEY ix_notification_unread (user_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One attempt to get a notification out of the building. Doc 08 §6.
--
-- CREATED → QUEUED → SENT → DELIVERED, or FAILED with bounded retry. A row per
-- channel, because a push can fail while an SMS succeeds and a user who received
-- one has been told.
CREATE TABLE notification_delivery (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    notification_id BIGINT       NOT NULL,
    -- PUSH or SMS. IN_APP has no row: it is the notification itself, and
    -- inventing a delivery attempt for it would mean tracking whether we
    -- successfully wrote to our own database.
    channel         VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'CREATED',
    -- The device or number it went to. Kept so a support question about a missing
    -- notification has an answer; the token itself is the provider's handle for an
    -- app install, not a credential.
    destination     VARCHAR(512) NULL,
    device_id       BIGINT       NULL,
    provider        VARCHAR(64)  NULL,
    provider_message_id VARCHAR(200) NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL,
    failure_reason  VARCHAR(500) NULL,
    sent_at         TIMESTAMP(6) NULL,
    delivered_at    TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_notification_delivery_notification FOREIGN KEY (notification_id)
        REFERENCES notification (id),
    CONSTRAINT fk_notification_delivery_device FOREIGN KEY (device_id) REFERENCES device (id),
    -- The dispatcher's only query: due attempts, oldest first.
    KEY ix_notification_delivery_due (status, next_attempt_at, id),
    KEY ix_notification_delivery_notification (notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What a user has chosen to mute. Doc 08 §5.
--
-- **Opt-out, not opt-in**: a row exists only where someone has turned something
-- off. Absent means enabled, so a new category or a new user starts receiving
-- rather than silently not — the failure mode of opt-in is a restaurant who never
-- learns their order was rejected and never knew there was a setting.
CREATE TABLE notification_preference (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    channel         VARCHAR(32)  NOT NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_notification_preference UNIQUE (user_id, category, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Product analytics. Doc 08 §7. Append-only.
--
-- **Never carries a secret.** Doc 08 §8 forbids logging an OTP, a card number, a
-- CVV, or a provider credential. The ingest endpoint strips any property whose
-- name looks like one rather than trusting clients not to send them — a client
-- bug should not become a compliance incident, and the server is the only place
-- that can guarantee it.
CREATE TABLE analytics_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- The client's id for this occurrence, so a retried batch does not double-count.
    client_event_id VARCHAR(100) NULL,
    event_name      VARCHAR(100) NOT NULL,
    user_id         BIGINT       NULL,
    -- Internal ids only (doc 08 §8): which outlet, not which person.
    outlet_id       BIGINT       NULL,
    supplier_store_id BIGINT     NULL,
    session_id      VARCHAR(100) NULL,
    platform        VARCHAR(16)  NULL,
    app_version     VARCHAR(32)  NULL,
    properties      JSON         NULL,
    -- When it happened on the device, which may be well before it was uploaded —
    -- the app batches, and a phone in a basement uploads late.
    occurred_at     TIMESTAMP(6) NOT NULL,
    received_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_analytics_event_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_analytics_client_event UNIQUE (user_id, client_event_id),
    KEY ix_analytics_event_name (event_name, occurred_at),
    KEY ix_analytics_event_outlet (outlet_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
