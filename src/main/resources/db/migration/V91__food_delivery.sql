-- V91 — Food delivery: a restaurant sends cooked food to its own customer (D-201, doc 06 §16).
--
-- Additive only. The supplier flow's `delivery` tables are not touched: that flow is one consignment per
-- supplier order, and every column and constraint in it says so. A food delivery has no supplier order,
-- so it gets its own tables, which mirror the shape of the supplier ones so the same courier adapters,
-- quoting and waterfall code can serve both.
--
-- The one change to an existing table: `delivery_provider.supports_food_delivery`. A parcel or freight
-- carrier is no use for hot food, so which providers may take a food delivery is data an operator can
-- change, not a list in code.

ALTER TABLE delivery_provider
    ADD COLUMN supports_food_delivery TINYINT(1) NOT NULL DEFAULT 0,
    ALGORITHM=INPLACE, LOCK=NONE;

-- On-demand couriers that take food, plus the two mocks CI runs on. Parcel and freight carriers
-- (Delhivery, Xpressbees, Shiprocket, Blowhorn) stay off.
UPDATE delivery_provider
   SET supports_food_delivery = 1
 WHERE code IN ('PIDGE', 'BORZO', 'PORTER', 'SHADOWFAX', 'LOADSHARE', 'MOCK_EXPRESS', 'MOCK_SAVER');

-- One consignment: cooked food from an outlet to a customer. Doc 06 §16.
CREATE TABLE food_delivery (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_id   BIGINT       NOT NULL,
    outlet_id       BIGINT       NOT NULL,

    -- The restaurant's own order number (from its POS or website). Shown to the courier and the customer.
    external_order_ref VARCHAR(100) NULL,
    -- Replay protection: the same key from the same restaurant returns the first delivery, never a second
    -- courier. Required on create.
    idempotency_key VARCHAR(100) NOT NULL,

    -- REQUESTED → QUOTED → PROVIDER_SELECTED → DRIVER_ASSIGNED → AT_PICKUP → PICKED_UP → OUT_FOR_DELIVERY
    -- → ARRIVED → DELIVERED, plus QUOTE_FAILED, PROVIDER_UNAVAILABLE, CANCELLED, FAILED, RETURNED.
    status          VARCHAR(40)  NOT NULL DEFAULT 'REQUESTED',

    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NULL,
    provider_delivery_id VARCHAR(200) NULL,

    -- Pickup is the outlet; drop is the customer.
    pickup_address  VARCHAR(500) NOT NULL,
    pickup_latitude DECIMAL(10,7) NULL,
    pickup_longitude DECIMAL(10,7) NULL,
    pickup_contact_name VARCHAR(150) NULL,
    pickup_contact_phone VARCHAR(32) NULL,

    customer_name   VARCHAR(150) NOT NULL,
    customer_phone  VARCHAR(32)  NOT NULL,
    drop_address    VARCHAR(500) NOT NULL,
    drop_landmark   VARCHAR(250) NULL,
    drop_latitude   DECIMAL(10,7) NULL,
    drop_longitude  DECIMAL(10,7) NULL,
    drop_instructions VARCHAR(500) NULL,

    -- The food bill, for the courier's declared value and the record. Cash on delivery is out of scope
    -- (D-201): the courier carries food, not money, so there is no collected-amount column.
    order_value     DECIMAL(19,4) NULL,
    weight_kg       DECIMAL(19,4) NULL,
    vehicle_type    VARCHAR(32)  NULL,
    -- Hot food: bike by default, bag required, and a hard ceiling on how long it may be on the road.
    max_delivery_minutes INT     NULL,

    -- What the restaurant pays. The selected quote; never a bid and never a provider list (doc 06 §10).
    fee             DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',
    -- The wallet debit for the fee. Taken when a courier is booked, returned if the delivery is cancelled
    -- before pickup or fails for a reason that is ours or the courier's.
    fee_debit_ref   VARCHAR(100) NULL,
    fee_debited_at  TIMESTAMP(6) NULL,
    fee_refund_ref  VARCHAR(100) NULL,
    fee_refunded_at TIMESTAMP(6) NULL,

    driver_name     VARCHAR(150) NULL,
    driver_phone    VARCHAR(32)  NULL,
    driver_vehicle  VARCHAR(100) NULL,
    eta_minutes     INT          NULL,
    estimated_arrival_at TIMESTAMP(6) NULL,
    tracking_url    VARCHAR(1000) NULL,

    -- The customer's public tracking link (doc 06 §16): an unguessable token, not the row id. Reveals
    -- status, ETA and the rider's position only; never the fee, the provider, or the customer's number.
    tracking_token  CHAR(32)     NOT NULL,
    tracking_expires_at TIMESTAMP(6) NULL,

    -- Proof of handover. Only a hash is stored; attempts are counted so the code cannot be brute-forced.
    delivery_otp_hash CHAR(64)   NULL,
    delivery_otp_attempts INT    NOT NULL DEFAULT 0,

    attempt_count   INT          NOT NULL DEFAULT 0,
    assignment_deadline TIMESTAMP(6) NULL,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,

    requested_at    TIMESTAMP(6) NOT NULL,
    booked_at       TIMESTAMP(6) NULL,
    assigned_at     TIMESTAMP(6) NULL,
    picked_up_at    TIMESTAMP(6) NULL,
    delivered_at    TIMESTAMP(6) NULL,
    cancelled_at    TIMESTAMP(6) NULL,
    last_provider_update_at TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_food_delivery_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    CONSTRAINT fk_food_delivery_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_food_delivery_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    CONSTRAINT uk_food_delivery_idempotency UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT uk_food_delivery_token UNIQUE (tracking_token),
    KEY ix_food_delivery_outlet (outlet_id, created_at),
    KEY ix_food_delivery_status (status, created_at),
    KEY ix_food_delivery_provider_delivery (provider_code, provider_delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What each provider said when asked. Internal; no endpoint returns it (doc 06 §10).
CREATE TABLE food_delivery_quote (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    food_delivery_id BIGINT      NOT NULL,
    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    amount          DECIMAL(19,4) NULL,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',
    eta_minutes     INT          NULL,
    distance_km     DECIMAL(9,4) NULL,
    provider_quote_id VARCHAR(200) NULL,
    expires_at      TIMESTAMP(6) NULL,
    selected        TINYINT(1)   NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_food_quote_delivery FOREIGN KEY (food_delivery_id) REFERENCES food_delivery (id),
    CONSTRAINT fk_food_quote_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    KEY ix_food_quote_delivery (food_delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every time a courier was asked to carry this food. Append-only; this is the waterfall's record: attempt 1
-- to Pidge ending in a timeout, attempt 2 to Borzo ending in DELIVERED, on one delivery.
CREATE TABLE food_delivery_attempt (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    food_delivery_id BIGINT      NOT NULL,
    delivery_provider_id BIGINT  NULL,
    provider_code   VARCHAR(64)  NOT NULL,
    attempt_number  INT          NOT NULL,
    attempt_type    VARCHAR(32)  NOT NULL DEFAULT 'BOOKING',
    outcome         VARCHAR(32)  NOT NULL,
    provider_delivery_id VARCHAR(200) NULL,
    quoted_amount   DECIMAL(19,4) NULL,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    started_at      TIMESTAMP(6) NOT NULL,
    ended_at        TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_food_attempt_delivery FOREIGN KEY (food_delivery_id) REFERENCES food_delivery (id),
    CONSTRAINT fk_food_attempt_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    CONSTRAINT uk_food_attempt_number UNIQUE (food_delivery_id, attempt_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The timeline. Append-only, deduplicated on the provider's event id (providers retry and arrive out of order).
CREATE TABLE food_delivery_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    food_delivery_id BIGINT      NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    status          VARCHAR(40)  NULL,
    provider_code   VARCHAR(64)  NULL,
    provider_event_id VARCHAR(200) NULL,
    provider_status VARCHAR(64)  NULL,
    disposition     VARCHAR(32)  NOT NULL DEFAULT 'APPLIED',
    description     VARCHAR(500) NULL,
    payload         JSON         NULL,
    occurred_at     TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_food_event_delivery FOREIGN KEY (food_delivery_id) REFERENCES food_delivery (id),
    CONSTRAINT uk_food_event_provider UNIQUE (provider_code, provider_event_id),
    KEY ix_food_event_delivery (food_delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Rider positions. Append-only, provider-supplied only, never fabricated (doc 06 §8).
CREATE TABLE food_delivery_location (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    food_delivery_id BIGINT      NOT NULL,
    latitude        DECIMAL(10,7) NOT NULL,
    longitude       DECIMAL(10,7) NOT NULL,
    bearing         DECIMAL(6,2) NULL,
    speed_kmph      DECIMAL(6,2) NULL,
    recorded_at     TIMESTAMP(6) NOT NULL,
    received_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_food_location_delivery FOREIGN KEY (food_delivery_id) REFERENCES food_delivery (id),
    KEY ix_food_location_delivery (food_delivery_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
