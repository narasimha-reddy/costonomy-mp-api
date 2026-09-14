-- V10 — Supplier orders.
--
-- Doc 02 §3, doc 03 §5, doc 11, doc 13.
--
-- One procurement produces one supplier order per supplier store. A restaurant
-- sees one checkout; three suppliers each see their own order, with their own
-- deadline and their own outcome. Doc 05 §11 keeps the restaurant's view unified
-- even though the backend splits — the split is real, the presentation is not.

CREATE TABLE supplier_order (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    procurement_id  BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    -- Human-readable, e.g. MP-260914-001284. What a restaurant and a supplier
    -- quote to each other and to support; the surrogate id never leaves the system.
    order_number    VARCHAR(32)  NOT NULL,
    -- DRAFT → PENDING_ACCEPTANCE → CONFIRMED / PARTIALLY_ACCEPTED → PREPARING →
    -- READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED → COMPLETED,
    -- with REJECTED / EXPIRED / CANCELLED branches. Doc 03 §5.
    status          VARCHAR(40)  NOT NULL DEFAULT 'DRAFT',

    -- The moment after which this order can no longer be accepted.
    --
    -- **Snapshotted from the store's response_sla_seconds at creation, never read
    -- live.** Doc 13: the SLA is configurable, and a supplier changing it must not
    -- move the deadline on an order already in flight — in either direction. This
    -- column is the authority the countdown (§23A.34) and the timeout job both
    -- read, so they cannot disagree.
    acceptance_deadline TIMESTAMP(6) NULL,
    -- Kept alongside so the client can render the countdown as a proportion of the
    -- window it was given, not of a default it assumed.
    response_sla_seconds INT       NOT NULL DEFAULT 60,

    accepted_at     TIMESTAMP(6) NULL,
    rejected_at     TIMESTAMP(6) NULL,
    rejection_reason VARCHAR(500) NULL,
    expired_at      TIMESTAMP(6) NULL,
    cancelled_at    TIMESTAMP(6) NULL,

    subtotal        DECIMAL(19,4) NOT NULL DEFAULT 0,
    gst_amount      DECIMAL(19,4) NOT NULL DEFAULT 0,
    delivery_fee    DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_amount    DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- What the supplier actually committed to, once they respond. Zero until then,
    -- and lower than total_amount after a partial acceptance — doc 01 §14 and §16:
    -- only the accepted commercial value is captured, and commission applies to it.
    accepted_amount DECIMAL(19,4) NOT NULL DEFAULT 0,

    payment_method  VARCHAR(32)  NOT NULL DEFAULT 'PREPAID',
    payment_status  VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    credit_status   VARCHAR(32)  NULL,
    delivery_mode   VARCHAR(32)  NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_supplier_order_procurement FOREIGN KEY (procurement_id) REFERENCES procurement (id),
    CONSTRAINT fk_supplier_order_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_supplier_order_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT uk_supplier_order_number UNIQUE (order_number),
    -- One order per store per procurement. Also the guard that makes duplicate
    -- submission safe at the database level, underneath the idempotency key:
    -- a second submit cannot produce a second set of orders (doc 10 §2).
    CONSTRAINT uk_supplier_order_procurement_store UNIQUE (procurement_id, supplier_store_id),
    -- The supplier's inbox: their pending orders, soonest deadline first.
    KEY ix_supplier_order_store_status (supplier_store_id, status, acceptance_deadline),
    -- The timeout job: everything pending whose deadline has passed.
    KEY ix_supplier_order_deadline (status, acceptance_deadline),
    KEY ix_supplier_order_outlet (outlet_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One line of a supplier order.
--
-- Three quantities, deliberately separate (doc 25's worked example: ordered 20,
-- accepted 18, received 17). Collapsing any two of them loses the ability to say
-- whether a shortfall was the supplier declining or the delivery falling short,
-- which is exactly what a dispute turns on.
CREATE TABLE supplier_order_item (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    procurement_item_id BIGINT   NOT NULL,
    -- Carried through so an accepted quantity can be credited back to the
    -- requirement it came from, and the remainder stays sourceable (guardrail 14).
    requirement_item_id BIGINT   NULL,
    canonical_product_id BIGINT  NOT NULL,
    supplier_sku_id BIGINT       NOT NULL,

    requested_quantity DECIMAL(19,4) NOT NULL,
    -- Null until the supplier responds. Null is "not yet answered"; 0 is "declined
    -- this line" — doc 04 §11 requires a zero to be explicit, and a nullable column
    -- is how the two stay distinguishable.
    accepted_quantity DECIMAL(19,4) NULL,
    -- What was actually delivered. Set at receiving (Phase 12).
    fulfilled_quantity DECIMAL(19,4) NULL,

    unit            VARCHAR(32)  NOT NULL,
    unit_price_snapshot DECIMAL(19,4) NOT NULL,
    gst_rate_snapshot DECIMAL(9,4) NOT NULL,
    line_item_value DECIMAL(19,4) NOT NULL,
    line_gst        DECIMAL(19,4) NOT NULL,
    line_total      DECIMAL(19,4) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(500) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_supplier_order_item_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_supplier_order_item_procurement_item FOREIGN KEY (procurement_item_id) REFERENCES procurement_item (id),
    CONSTRAINT fk_supplier_order_item_requirement_item FOREIGN KEY (requirement_item_id) REFERENCES requirement_item (id),
    CONSTRAINT fk_supplier_order_item_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    CONSTRAINT fk_supplier_order_item_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    -- Doc 10 §3's invariants, enforced by the database. A supplier cannot accept
    -- more than was ordered, and cannot deliver more than they accepted.
    CONSTRAINT ck_order_item_accepted CHECK (accepted_quantity is null or accepted_quantity <= requested_quantity),
    CONSTRAINT ck_order_item_fulfilled CHECK (fulfilled_quantity is null or accepted_quantity is null or fulfilled_quantity <= accepted_quantity),
    CONSTRAINT ck_order_item_requested CHECK (requested_quantity > 0),
    KEY ix_supplier_order_item_order (supplier_order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Daily sequence behind the human order number.
--
-- Its own table because MP-260914-001284 needs a per-day counter, and deriving one
-- from a count of today's orders is a race: two concurrent submissions would read
-- the same count and mint the same number, which uk_supplier_order_number would
-- then reject — turning a cosmetic concern into a failed checkout.
CREATE TABLE order_number_sequence (
    sequence_date   DATE         NOT NULL,
    next_value      BIGINT       NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
