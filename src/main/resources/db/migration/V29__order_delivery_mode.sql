-- The order stops asking, and the restaurant chooses how goods travel. D-091.
--
-- Columns and backfill only. The constraints that depend on this data being
-- correct are V30, because D-089 was taught the hard way that a migration which
-- adds a column, fills it, and validates it in one step passes against an empty
-- database and fails against a real one.

-- ── Dead columns ────────────────────────────────────────────────────────
--
-- Both were declared, defaulted and never written or read by anything in
-- src/main. `intent_item.status` was on the wire as well, so every client
-- received a constant 'REQUESTED' it could have branched on.
ALTER TABLE supplier_order DROP COLUMN credit_status;
ALTER TABLE intent_item    DROP COLUMN status;

-- ── How the goods travel ────────────────────────────────────────────────
--
-- PICKUP, SUPPLIER_DELIVERY or COSTONOMY_DELIVERY, chosen by the restaurant at
-- order creation because the restaurant pays the fee. V10 created this column
-- nullable and nothing ever defined a value for it; it becomes NOT NULL in V30.
--
-- Existing rows predate the choice. They take COSTONOMY_DELIVERY because every
-- order that has actually moved did so through the delivery module: a Delivery
-- row, a provider and a courier's events. Calling them PICKUP would rewrite what
-- happened.
UPDATE supplier_order so
   SET so.delivery_mode = 'COSTONOMY_DELIVERY'
 WHERE so.delivery_mode IS NULL;

-- ── Who cancelled, and why ──────────────────────────────────────────────
--
-- One CANCELLED status, because one thing happened and only the actor differs
-- (D-091). A reliability metric reads the column; every switch stays at eight
-- cases.
ALTER TABLE supplier_order
    ADD COLUMN cancelled_by        VARCHAR(32)  NULL AFTER delivery_mode,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER cancelled_by;

-- ── Retiring the four statuses that asked the supplier again ────────────
--
-- D-091 removes PENDING_ACCEPTANCE, PARTIALLY_ACCEPTED, REJECTED and EXPIRED.
-- Rows still holding them have to be mapped here, not left: the enum no longer
-- has a constant to read them back into, so an unconverted row throws on load
-- rather than showing up as a stale status somebody can investigate.
--
-- The mapping preserves what happened rather than what it was called.
-- `accepted_quantity` already records the reduced quantities, so a partial
-- acceptance was an accepted order and becomes one.
UPDATE supplier_order
   SET status = 'CONFIRMED'
 WHERE status = 'PARTIALLY_ACCEPTED';

-- A supplier declining is now a supplier cancelling, and the attribution says so.
UPDATE supplier_order
   SET status = 'CANCELLED', cancelled_by = 'SUPPLIER',
       cancellation_reason = COALESCE(cancellation_reason, 'Declined before D-091')
 WHERE status = 'REJECTED';

-- Nobody answered, or nobody ever will: neither is anyone's decision.
UPDATE supplier_order
   SET status = 'CANCELLED', cancelled_by = 'SYSTEM',
       cancellation_reason = COALESCE(cancellation_reason,
           'Acceptance window removed by D-091')
 WHERE status IN ('EXPIRED', 'PENDING_ACCEPTANCE');

-- Orders cancelled before this column existed. OrderReleaseService cancels
-- unfunded drafts and nothing else automated did, so SYSTEM is accurate for a
-- DRAFT and unknowable for the rest -- which stay NULL rather than being
-- attributed to somebody.
UPDATE supplier_order
   SET cancelled_by = 'SYSTEM'
 WHERE status = 'CANCELLED'
   AND accepted_at IS NULL
   AND cancelled_by IS NULL;

-- ── What a pack weighs ──────────────────────────────────────────────────
--
-- The delivery quote prices weight and distance, and the catalogue carried
-- neither a weight nor a reliable way to derive one: a KG pack size is mass, a
-- LTR needs an assumed density, and a PC carries no weight information at all.
-- Nullable, because a supplier who has not filled it in is a supplier whose
-- SKUs still have to be orderable -- the quote falls back to derivation and says
-- that it did.
ALTER TABLE supplier_sku
    ADD COLUMN weight_grams DECIMAL(19,4) NULL AFTER measure_unit;

UPDATE supplier_sku
   SET weight_grams = pack_size * 1000
 WHERE weight_grams IS NULL AND pack_unit = 'KG';

UPDATE supplier_sku
   SET weight_grams = measure_value * pack_size
 WHERE weight_grams IS NULL AND measure_unit = 'GM';

-- ── Which modes a store can serve this request ──────────────────────────
--
-- The supplier named one mode; they now declare what they can offer and the
-- restaurant picks. A CSV of mode names rather than a join table: the set has
-- three members, is read whole every time, and is never queried across.
ALTER TABLE intent_acceptance
    ADD COLUMN delivery_modes VARCHAR(120) NULL AFTER delivery_mode;

UPDATE intent_acceptance
   SET delivery_modes = delivery_mode
 WHERE delivery_modes IS NULL AND delivery_mode IS NOT NULL;

-- ── A quoted delivery fee ───────────────────────────────────────────────
--
-- Stored rather than recomputed, and referenced by id at order creation. A fee
-- recomputed between the screen that showed it and the charge that collected it
-- is a silent reprice, which 23A.16 forbids.
--
-- `vehicle_type` and `provider_reference` are the platform's cost side and never
-- leave the server: doc 06 10 -- the restaurant sees one fee and never a quote.
CREATE TABLE delivery_fee_quote (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    reference           VARCHAR(64)  NOT NULL,
    outlet_id           BIGINT       NOT NULL,
    supplier_store_id   BIGINT       NOT NULL,
    intent_id           BIGINT       NULL,

    -- Resolved from the outlet and the store, never accepted from the caller.
    pickup_latitude     DECIMAL(10,7) NOT NULL,
    pickup_longitude    DECIMAL(10,7) NOT NULL,
    drop_latitude       DECIMAL(10,7) NOT NULL,
    drop_longitude      DECIMAL(10,7) NOT NULL,
    distance_km         DECIMAL(9,4)  NOT NULL,
    weight_grams        DECIMAL(19,4) NOT NULL,

    -- What the restaurant is shown.
    fee                 DECIMAL(19,4) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',
    eta_minutes         INT           NULL,

    -- Internal. QUOTED from a provider, ESTIMATED from the rate card when the
    -- providers could not be reached -- recorded so a fee that was guessed is
    -- known to have been guessed.
    source              VARCHAR(32)   NOT NULL DEFAULT 'ESTIMATED',
    vehicle_type        VARCHAR(32)   NULL,
    provider_reference  VARCHAR(200)  NULL,

    expires_at          TIMESTAMP(6) NOT NULL,
    consumed_at         TIMESTAMP(6) NULL,
    supplier_order_id   BIGINT       NULL,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_delivery_fee_quote_reference UNIQUE (reference),
    CONSTRAINT fk_delivery_fee_quote_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_delivery_fee_quote_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_delivery_fee_quote_intent FOREIGN KEY (intent_id) REFERENCES intent (id),
    CONSTRAINT fk_delivery_fee_quote_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT ck_delivery_fee_quote_fee CHECK (fee >= 0)
);

CREATE INDEX ix_delivery_fee_quote_intent ON delivery_fee_quote (intent_id, expires_at);

-- ── The rate card ───────────────────────────────────────────────────────
--
-- What a Costonomy delivery costs when no provider can be reached. Not a
-- pricing strategy -- it is the floor under an outage, and it is what a
-- restaurant actually pays whenever one happens, so it has to be defensible on
-- its own.
--
-- Weight is in it because a card that prices by distance alone quotes a hundred
-- kilos like a single crate.
--
-- In app_config for the reason V8 gives: doc 09 10 lists this kind of figure
-- among the values operations change without a deploy.
INSERT INTO app_config
    (config_key, config_value, value_type, config_version, description,
     effective_from, status, created_at, updated_at)
VALUES
('delivery.baseFee', '40', 'DECIMAL', 1,
 'Rate-card base fee, used when no provider quotes',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.perKm', '8', 'DECIMAL', 1,
 'Rate-card fee per kilometre of the straight-line route',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.perKg', '2', 'DECIMAL', 1,
 'Rate-card fee per kilogram of the consignment',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.minFee', '40', 'DECIMAL', 1,
 'Floor under any rate-card delivery fee',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.quoteTtlSeconds', '900', 'INTEGER', 1,
 'How long a delivery fee quote stands before it must be taken again',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.bikeMaxKg', '20', 'DECIMAL', 1,
 'Heaviest consignment a two-wheeler takes',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('delivery.threeWheelerMaxKg', '150', 'DECIMAL', 1,
 'Heaviest consignment a three-wheeler takes',
 NOW(6), 'ACTIVE', NOW(6), NOW(6));
