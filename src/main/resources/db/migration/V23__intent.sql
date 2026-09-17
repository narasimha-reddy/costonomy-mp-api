-- Intent: a restaurant's procurement intent for one supplier, before any money.
--
-- The architecture this introduces separates three things the single Order flow
-- had collapsed into one:
--
--   Intent              what a restaurant wants from a supplier  (non-financial)
--   Intent acceptance   what that supplier will actually supply  (non-financial)
--   Order               the commercial transaction                (financial)
--
-- The old flow made the restaurant pay before the supplier had agreed to
-- anything, then let the supplier reduce the quantities afterwards — so money
-- moved against a promise that had not been made yet, and the order the
-- restaurant paid for was not the order it received. Here the supplier commits
-- first, the restaurant sees exactly what is on offer, and payment happens last.
--
-- ── One supplier per intent ─────────────────────────────────────────────
--
-- An intent holds supplier SKUs, so it belongs to one store: `supplier_store_id`
-- is on the intent itself and there is no invitation table. Sourcing the same
-- products from three suppliers is three intents, which is what keeps the
-- intent → order boundary one-to-one. A restaurant comparing suppliers still
-- does so on the product screen, where it always did.
--
-- ── Nothing here is financial ───────────────────────────────────────────
--
-- No payment, no credit reservation, no commission, no settlement, no invoice.
-- Those begin at order creation and only there. An intent that is never ordered
-- from costs nobody anything and appears in no GMV figure.

-- ── The intent ──────────────────────────────────────────────────────────
CREATE TABLE intent (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    reference           VARCHAR(32)  NOT NULL,
    outlet_id           BIGINT       NOT NULL,
    supplier_store_id   BIGINT       NOT NULL,
    created_by          BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    -- MANUAL or CLONED. A cloned intent is a fresh entity; this only records
    -- where the restaurant got the idea, which is worth measuring.
    source              VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    -- The intent this was cloned from, for the repeat-purchase path. Null for a
    -- first-time intent, and never a parent-child relationship: the clone owns
    -- nothing of the original's lifecycle.
    cloned_from_id      BIGINT       NULL,
    requested_delivery_time TIMESTAMP(6) NULL,
    notes               VARCHAR(1000),
    sent_at             TIMESTAMP(6) NULL,
    -- When the supplier's acceptance landed. The authoritative start of the
    -- order-creation window; a device clock never decides this.
    accepted_at         TIMESTAMP(6) NULL,
    -- Snapshotted at acceptance so a configuration change cannot retroactively
    -- shorten or extend a window a restaurant is already inside.
    accepted_order_creation_window_seconds INT NULL,
    -- accepted_at + the window above, stored rather than derived so the order
    -- creation endpoint can enforce it in one comparison inside its transaction.
    order_creation_deadline TIMESTAMP(6) NULL,
    cancelled_at        TIMESTAMP(6) NULL,
    expired_at          TIMESTAMP(6) NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_intent_reference UNIQUE (reference),
    CONSTRAINT fk_intent_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_intent_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_intent_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_intent_clone FOREIGN KEY (cloned_from_id) REFERENCES intent (id),
    -- A deadline without an acceptance, or an acceptance without a deadline,
    -- would let the order endpoint fall back on a default and guess.
    CONSTRAINT ck_intent_window CHECK (
        (accepted_at IS NULL AND order_creation_deadline IS NULL)
        OR (accepted_at IS NOT NULL AND order_creation_deadline IS NOT NULL))
);

CREATE INDEX ix_intent_outlet_status ON intent (outlet_id, status, created_at);
CREATE INDEX ix_intent_store_status ON intent (supplier_store_id, status, created_at);
-- The expiry jobs sweep on these two, and both are time-ordered.
CREATE INDEX ix_intent_deadline ON intent (order_creation_deadline);

-- ── What the restaurant is asking for ───────────────────────────────────
--
-- The SKU is the point: an intent is supplier-specific because it names that
-- supplier's packs. The canonical product rides along so a clone can be re-shopped
-- against another supplier without losing what was wanted.
CREATE TABLE intent_item (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    intent_id           BIGINT       NOT NULL,
    supplier_sku_id     BIGINT       NOT NULL,
    canonical_product_id BIGINT      NOT NULL,
    -- Packs, matching how the rest of this system counts what is bought.
    requested_quantity  DECIMAL(19,4) NOT NULL,
    unit                VARCHAR(32)  NOT NULL,
    notes               VARCHAR(500),
    status              VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_intent_item_intent FOREIGN KEY (intent_id) REFERENCES intent (id),
    CONSTRAINT fk_intent_item_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    CONSTRAINT fk_intent_item_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    CONSTRAINT ck_intent_item_requested CHECK (requested_quantity > 0),
    -- One line per SKU. Two lines for the same pack is two answers to one
    -- question, and the supplier would have to guess which to price.
    CONSTRAINT uk_intent_item_sku UNIQUE (intent_id, supplier_sku_id)
);

CREATE INDEX ix_intent_item_intent ON intent_item (intent_id);

-- ── What the supplier will supply ───────────────────────────────────────
--
-- One row per intent, because one intent is one supplier. Kept as its own table
-- rather than columns on the intent: it is the supplier's commercial statement,
-- it is immutable once submitted, and it expires on its own clock.
CREATE TABLE intent_acceptance (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    intent_id           BIGINT       NOT NULL,
    supplier_store_id   BIGINT       NOT NULL,
    responded_by        BIGINT       NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    -- What the supplier quoted, summed from the lines below. Not money owed:
    -- nothing is owed until an order exists.
    offered_value       DECIMAL(19,4) NOT NULL DEFAULT 0,
    offered_gst         DECIMAL(19,4) NOT NULL DEFAULT 0,
    offered_total       DECIMAL(19,4) NOT NULL DEFAULT 0,
    delivery_fee        DECIMAL(19,4) NULL,
    eta_minutes         INT          NULL,
    delivery_mode       VARCHAR(32)  NULL,
    notes               VARCHAR(1000),
    submitted_at        TIMESTAMP(6) NULL,
    expires_at          TIMESTAMP(6) NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,
    -- One acceptance per intent, enforced here rather than in a service: a
    -- second acceptance is a second commercial proposal for a frozen intent.
    CONSTRAINT uk_intent_acceptance_intent UNIQUE (intent_id),
    CONSTRAINT fk_intent_acceptance_intent FOREIGN KEY (intent_id) REFERENCES intent (id),
    CONSTRAINT fk_intent_acceptance_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_intent_acceptance_user FOREIGN KEY (responded_by) REFERENCES users (id)
);

CREATE INDEX ix_intent_acceptance_store ON intent_acceptance (supplier_store_id, status);
CREATE INDEX ix_intent_acceptance_expiry ON intent_acceptance (expires_at);

-- ── Line by line, what and at what price ────────────────────────────────
CREATE TABLE intent_acceptance_item (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    intent_acceptance_id BIGINT      NOT NULL,
    intent_item_id      BIGINT       NOT NULL,
    -- Zero is a real answer and must be sent explicitly: an omitted line is
    -- unanswered, not declined, and the restaurant needs to tell them apart.
    offered_quantity    DECIMAL(19,4) NOT NULL,
    unit_price          DECIMAL(19,4) NOT NULL,
    gst_rate            DECIMAL(9,4)  NOT NULL,
    line_value          DECIMAL(19,4) NOT NULL,
    line_gst            DECIMAL(19,4) NOT NULL,
    line_total          DECIMAL(19,4) NOT NULL,
    availability        VARCHAR(32)  NOT NULL DEFAULT 'AVAILABLE',
    notes               VARCHAR(500),
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_intent_acceptance_item UNIQUE (intent_acceptance_id, intent_item_id),
    CONSTRAINT fk_intent_acc_item_acceptance FOREIGN KEY (intent_acceptance_id) REFERENCES intent_acceptance (id),
    CONSTRAINT fk_intent_acc_item_item FOREIGN KEY (intent_item_id) REFERENCES intent_item (id),
    CONSTRAINT ck_intent_acc_item_offered CHECK (offered_quantity >= 0)
    -- offered_quantity <= requested_quantity is enforced in IntentAcceptanceService,
    -- where the refusal can name the line and the two numbers. A CHECK cannot
    -- reach across to intent_item.
);

CREATE INDEX ix_intent_acc_item_acceptance ON intent_acceptance_item (intent_acceptance_id);

-- ── Which order came from which intent ──────────────────────────────────
--
-- A link table rather than a column on either side, because it carries the
-- acceptance too: an order traces back not just to what was wanted but to the
-- exact commercial statement it was created against.
--
-- The unique key on intent_id is the one-to-one boundary. A restaurant wanting
-- to buy again clones the intent; it does not order twice from one.
CREATE TABLE intent_order_link (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    intent_id           BIGINT       NOT NULL,
    intent_acceptance_id BIGINT      NOT NULL,
    supplier_order_id   BIGINT       NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_intent_order_link_intent UNIQUE (intent_id),
    CONSTRAINT uk_intent_order_link_order UNIQUE (supplier_order_id),
    CONSTRAINT fk_intent_order_link_intent FOREIGN KEY (intent_id) REFERENCES intent (id),
    CONSTRAINT fk_intent_order_link_acceptance FOREIGN KEY (intent_acceptance_id) REFERENCES intent_acceptance (id),
    CONSTRAINT fk_intent_order_link_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id)
);

-- ── An order no longer needs a procurement ──────────────────────────────
--
-- Orders were created only by submitting a cart, so `procurement_id` could be
-- NOT NULL. An intent-derived order has no cart behind it, and inventing an
-- empty procurement row to satisfy the column would put a lie in the schema to
-- keep a constraint company.
--
-- Historical orders keep their procurement and gain no intent. Nothing is
-- rewritten: an order created before this migration is exactly as valid as one
-- created after it, and the two are told apart by which link they have.
ALTER TABLE supplier_order
    MODIFY COLUMN procurement_id BIGINT NULL;
