-- V13 — Delivery.
--
-- Doc 06 in full, doc 02 §3, doc 03 §10, doc 04 §14.
--
-- **One delivery per supplier order, for the life of that order.** Doc 03 §10 and
-- doc 06 §7: a driver cancelling, a provider failing, a pickup going wrong — none
-- of these create a second delivery. They append an attempt and an event to the
-- one that already exists. A second row would make a restaurant's tracking screen
-- show two journeys for one consignment, and would make "how often do deliveries
-- fail" unanswerable, because the failures would be counted as separate successes
-- and failures rather than as retries of one job.
--
-- **Provider quotes never leave this schema.** Doc 06 §4 and §10: the restaurant
-- sees one fee. Which providers bid, what they bid and who lost is Mandi's
-- commercial business, and an API that leaks it hands a supplier's or provider's
-- pricing to everyone who places an order.

-- The providers Mandi can dispatch to. Doc 06 §3.
--
-- A row per adapter rather than a config list, so enabling one is an operational
-- change with an audit trail rather than a deploy.
CREATE TABLE delivery_provider (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    -- Whether this provider reports driver location. Doc 06 §8: where it does not,
    -- the app shows a timeline and no map — never a fabricated position.
    supports_tracking TINYINT(1) NOT NULL DEFAULT 1,
    supports_proof  TINYINT(1)   NOT NULL DEFAULT 0,
    -- Tie-break only. Cost and ETA decide first (doc 06 §4); this settles a draw
    -- so selection is deterministic and a test can assert it.
    priority        INT          NOT NULL DEFAULT 100,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_delivery_provider_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One consignment, from ready-for-pickup to delivered. Doc 06 §5.
CREATE TABLE delivery (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,

    -- SUPPLIER_OWN or COSTONOMY. Doc 06 §2. The mode decides who may move the
    -- delivery forward: on own delivery the supplier is the courier and reports
    -- their own progress; on Costonomy delivery only the provider's events do,
    -- because §23A.38 forbids a supplier claiming pickup on a courier's behalf.
    mode            VARCHAR(32)  NOT NULL,
    -- DELIVERY_REQUESTED → QUOTE_RECEIVED → PROVIDER_SELECTED → DRIVER_ASSIGNED
    -- → DRIVER_AT_PICKUP → PICKED_UP → IN_TRANSIT → ARRIVED_AT_DESTINATION
    -- → DELIVERED, plus the failure states. Doc 03 §10.
    status          VARCHAR(40)  NOT NULL DEFAULT 'DELIVERY_REQUESTED',

    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NULL,
    provider_delivery_id VARCHAR(200) NULL,

    -- What the restaurant pays. The selected quote for Costonomy delivery, the
    -- supplier's own fee (often zero) for own delivery. Never a bid (doc 06 §10).
    fee             DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',

    pickup_address  VARCHAR(500) NOT NULL,
    pickup_latitude DECIMAL(10,7) NULL,
    pickup_longitude DECIMAL(10,7) NULL,
    pickup_contact_name VARCHAR(150) NULL,
    pickup_contact_phone VARCHAR(32) NULL,

    drop_address    VARCHAR(500) NOT NULL,
    drop_latitude   DECIMAL(10,7) NULL,
    drop_longitude  DECIMAL(10,7) NULL,
    drop_contact_name VARCHAR(150) NULL,
    drop_contact_phone VARCHAR(32) NULL,

    -- Driver identity where the provider supports it (doc 06 §8). Nullable
    -- throughout: an unassigned delivery has no driver, and inventing a placeholder
    -- would put a fake name in front of a restaurant.
    driver_name     VARCHAR(150) NULL,
    driver_phone    VARCHAR(32)  NULL,
    driver_vehicle  VARCHAR(100) NULL,

    -- The provider's own estimate, which is authoritative over any distance-based
    -- guess we made at discovery time (doc 07 §13).
    eta_minutes     INT          NULL,
    estimated_arrival_at TIMESTAMP(6) NULL,

    -- How many times we have gone out to a provider for this consignment. A
    -- reassignment increments it; it never creates a new delivery row (doc 06 §7).
    attempt_count   INT          NOT NULL DEFAULT 0,

    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,

    requested_at    TIMESTAMP(6) NOT NULL,
    booked_at       TIMESTAMP(6) NULL,
    assigned_at     TIMESTAMP(6) NULL,
    picked_up_at    TIMESTAMP(6) NULL,
    delivered_at    TIMESTAMP(6) NULL,
    cancelled_at    TIMESTAMP(6) NULL,
    -- When the provider last told us anything. Doc 06 §8's staleness indicator is
    -- computed from this and from the newest location, never assumed fresh.
    last_provider_update_at TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_delivery_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_delivery_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_delivery_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_delivery_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    -- One delivery per order. The database guard behind doc 06 §7's "reassign
    -- without creating a second logical delivery", and behind a duplicate booking
    -- request (doc 10 §2).
    CONSTRAINT uk_delivery_order UNIQUE (supplier_order_id),
    KEY ix_delivery_status (status, created_at),
    KEY ix_delivery_provider_delivery (provider_code, provider_delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What each provider said when asked. Doc 06 §4, §12.
--
-- **Internal.** Never serialised to a restaurant, and there is no endpoint that
-- returns one. Kept because doc 06 §12 requires the quote to be reconstructable
-- for reconciliation, and because "why did this cost that" needs an answer.
CREATE TABLE delivery_quote (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id     BIGINT       NOT NULL,
    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NOT NULL,
    -- QUOTED, UNSERVICEABLE or FAILED. A provider that could not answer is
    -- recorded as having been asked — otherwise a delivery that fell back to an
    -- expensive provider looks like a choice rather than the only option left.
    status          VARCHAR(32)  NOT NULL,
    amount          DECIMAL(19,4) NULL,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',
    eta_minutes     INT          NULL,
    distance_km     DECIMAL(9,4) NULL,
    provider_quote_id VARCHAR(200) NULL,
    -- Valid until. A stale quote must not be booked at yesterday's price.
    expires_at      TIMESTAMP(6) NULL,
    selected        TINYINT(1)   NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_delivery_quote_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    CONSTRAINT fk_delivery_quote_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    KEY ix_delivery_quote_delivery (delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every time we asked a provider to carry this consignment. Append-only. Doc 06 §7.
--
-- This is what makes reassignment auditable: attempt 1 to provider A ending in
-- DRIVER_CANCELLED, attempt 2 to provider B ending in DELIVERED, on one delivery.
CREATE TABLE delivery_provider_attempt (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id     BIGINT       NOT NULL,
    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NOT NULL,
    attempt_number  INT          NOT NULL,
    -- BOOKING, REASSIGNMENT.
    attempt_type    VARCHAR(32)  NOT NULL DEFAULT 'BOOKING',
    -- BOOKED, FAILED, CANCELLED, COMPLETED.
    outcome         VARCHAR(32)  NOT NULL,
    provider_delivery_id VARCHAR(200) NULL,
    quoted_amount   DECIMAL(19,4) NULL,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    started_at      TIMESTAMP(6) NOT NULL,
    ended_at        TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_delivery_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    CONSTRAINT fk_delivery_attempt_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    CONSTRAINT uk_delivery_attempt_number UNIQUE (delivery_id, attempt_number),
    KEY ix_delivery_attempt_delivery (delivery_id, attempt_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The timeline. Append-only. Doc 06 §9, §12.
--
-- Deduplicated on the provider's event id, because providers retry and deliver
-- out of order (doc 06 §12, §13). An event that would move the delivery backwards
-- is stored and not applied — it is a statement about the past.
CREATE TABLE delivery_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id     BIGINT       NOT NULL,
    -- The internal status this event set, or the reason it changed nothing.
    event_type      VARCHAR(64)  NOT NULL,
    status          VARCHAR(40)  NULL,
    provider_code   VARCHAR(64)  NULL,
    provider_event_id VARCHAR(200) NULL,
    provider_status VARCHAR(64)  NULL,
    -- APPLIED, DUPLICATE, OUT_OF_ORDER, IGNORED.
    disposition     VARCHAR(32)  NOT NULL DEFAULT 'APPLIED',
    description     VARCHAR(500) NULL,
    payload         JSON         NULL,
    -- When the provider says it happened, which may be well before we saw it.
    occurred_at     TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_delivery_event_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    -- Replay protection (doc 06 §13). Nulls are distinct in MySQL, so our own
    -- internally-generated events are unconstrained while provider events are not.
    CONSTRAINT uk_delivery_event_provider UNIQUE (provider_code, provider_event_id),
    KEY ix_delivery_event_delivery (delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Driver positions. Append-only. Doc 06 §8.
--
-- **Never fabricated.** Every row came from a provider, and `recorded_at` is the
-- provider's own timestamp, not ours. The staleness indicator doc 06 §8 requires
-- is computed by comparing that against now — which only means anything if we
-- never write a row we did not receive.
CREATE TABLE delivery_location (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id     BIGINT       NOT NULL,
    latitude        DECIMAL(10,7) NOT NULL,
    longitude       DECIMAL(10,7) NOT NULL,
    bearing         DECIMAL(6,2) NULL,
    speed_kmph      DECIMAL(6,2) NULL,
    -- The provider's timestamp for the fix, not when we stored it.
    recorded_at     TIMESTAMP(6) NOT NULL,
    received_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_delivery_location_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    KEY ix_delivery_location_delivery (delivery_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The two mock providers local development and CI run on (doc 06 §11, doc 10 §6).
--
-- Two, not one, because "lowest cost meeting the required ETA" (doc 06 §4) is not
-- a rule that can be tested against a single candidate — with one provider every
-- selection strategy agrees.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES
('MOCK_EXPRESS', 'Mock Express', 1, 1, 1, 10, NOW(6), NOW(6)),
('MOCK_SAVER',   'Mock Saver',   1, 1, 0, 20, NOW(6), NOW(6));
