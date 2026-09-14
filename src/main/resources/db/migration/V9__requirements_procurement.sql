-- V9 — Requirements and procurement.
--
-- Doc 02 §3–4, doc 03 §3–4, doc 11.
--
-- The hierarchy: Requirement → Procurement → Supplier Order(s). A requirement is
-- what the restaurant needs; a procurement is one attempt to source it; supplier
-- orders are what that attempt produced.
--
-- The reason these are three tables rather than one order is doc 01 §9 and §10:
-- a requirement **survives** a supplier rejecting, timing out or partially
-- accepting. The unmet quantity stays attached to the requirement so the
-- restaurant can source it elsewhere without retyping anything. That is
-- guardrail 14 — never silently drop unmet requirements — and it is structural,
-- not a feature layered on later.

-- What the restaurant needs. Doc 03 §3.
CREATE TABLE requirement (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id       BIGINT       NOT NULL,
    created_by      BIGINT       NOT NULL,
    -- OPEN → SOURCING → PARTIALLY_FULFILLED → FULFILLED, plus CANCELLED/EXPIRED.
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    -- MANUAL or RECOMMENDED. Doc 01 §9: a requirement can come from the
    -- restaurant typing it or from Mandi proposing it, and the two are worth
    -- telling apart when measuring whether recommendations are any good.
    source          VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    notes           VARCHAR(1000),
    -- When the restaurant needs it by. Feeds urgency in the UI (§23A.14); not a
    -- promise and not used for ranking.
    needed_by       TIMESTAMP(6) NULL,
    fulfilled_at    TIMESTAMP(6) NULL,
    cancelled_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_requirement_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_requirement_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    KEY ix_requirement_outlet_status (outlet_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One product a requirement needs.
--
-- `requested_quantity` never changes once set. `fulfilled_quantity` only grows,
-- as supplier orders are accepted. Remaining is the difference, and is derived
-- rather than stored so the two cannot drift apart — the invariant doc 10 §3
-- names (`fulfilledQuantity <= requestedQuantity`) is then a single check rather
-- than a reconciliation.
CREATE TABLE requirement_item (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id  BIGINT       NOT NULL,
    canonical_product_id BIGINT  NOT NULL,
    requested_quantity DECIMAL(19,4) NOT NULL,
    fulfilled_quantity DECIMAL(19,4) NOT NULL DEFAULT 0,
    unit            VARCHAR(32)  NOT NULL,
    notes           VARCHAR(500),
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_requirement_item_requirement FOREIGN KEY (requirement_id) REFERENCES requirement (id),
    CONSTRAINT fk_requirement_item_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    -- Guardrail 14, enforced by the database rather than by discipline.
    CONSTRAINT ck_requirement_item_fulfilled CHECK (fulfilled_quantity <= requested_quantity),
    CONSTRAINT ck_requirement_item_requested CHECK (requested_quantity > 0),
    KEY ix_requirement_item_requirement_status (requirement_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One attempt to source a requirement. Doc 03 §4.
--
-- Also the cart: a DRAFT procurement is what §23A.16 renders. Cart and
-- procurement are the same object at different points in its life, which is why
-- a cart survives app restarts and is visible to an approver without being
-- copied anywhere.
--
-- Money columns are **server-computed only**. Guardrail 3: the client never sends
-- a total, and anything it did send would be ignored. They are recomputed on every
-- validate and again on submit, from the offers live at that moment.
CREATE TABLE procurement (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id       BIGINT       NOT NULL,
    -- Null for a cart built by browsing rather than from a requirement.
    requirement_id  BIGINT       NULL,
    created_by      BIGINT       NOT NULL,
    -- DRAFT → VALIDATING → READY → PENDING_APPROVAL → APPROVED → SUBMITTED.
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    -- NOT_REQUIRED / PENDING / APPROVED / REJECTED. Decided by policy at
    -- validation time, not by the client.
    approval_status VARCHAR(32)  NOT NULL DEFAULT 'NOT_REQUIRED',
    -- PREPAID or CREDIT. Determines what has to happen before suppliers see it.
    payment_method  VARCHAR(32)  NOT NULL DEFAULT 'PREPAID',
    payment_status  VARCHAR(32)  NOT NULL DEFAULT 'NOT_STARTED',
    total_item_value DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_gst       DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_delivery_fee DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_amount    DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- The policy that held this order, and its version at the time. Doc 09 §11:
    -- an approval is a financial control and must stay reconstructable.
    approval_policy_id BIGINT     NULL,
    approval_policy_version INT   NULL,
    approved_by     BIGINT       NULL,
    approved_at     TIMESTAMP(6) NULL,
    rejected_by     BIGINT       NULL,
    rejected_at     TIMESTAMP(6) NULL,
    rejection_reason VARCHAR(500) NULL,
    submitted_at    TIMESTAMP(6) NULL,
    cancelled_at    TIMESTAMP(6) NULL,
    -- When the prices in procurement_item were last confirmed against live offers.
    -- §23A.16 and doc 01 §11: a stale cart is revalidated and a changed price
    -- requires explicit confirmation before checkout can proceed.
    validated_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_procurement_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_procurement_requirement FOREIGN KEY (requirement_id) REFERENCES requirement (id),
    CONSTRAINT fk_procurement_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_procurement_policy FOREIGN KEY (approval_policy_id) REFERENCES procurement_policy (id),
    KEY ix_procurement_outlet_status (outlet_id, status, created_at),
    KEY ix_procurement_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A line: this much of this product, from this supplier, at this price.
--
-- The price is a **snapshot**, taken when the line was added or last revalidated.
-- Doc 02 §5 requires transaction records to carry the values needed to reconstruct
-- them, and §23A.16 requires a price change to be shown rather than absorbed. The
-- snapshot is what a change is detected against: compare it to the live offer, and
-- a difference is a fact to surface, not a number to quietly overwrite.
CREATE TABLE procurement_item (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    procurement_id  BIGINT       NOT NULL,
    -- Null when the cart was built by browsing rather than from a requirement.
    requirement_item_id BIGINT   NULL,
    canonical_product_id BIGINT  NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    supplier_sku_id BIGINT       NOT NULL,
    -- The exact offer row this price came from, so a change is traceable to the
    -- supersession that caused it.
    supplier_offer_id BIGINT     NOT NULL,
    requested_quantity DECIMAL(19,4) NOT NULL,
    unit            VARCHAR(32)  NOT NULL,
    unit_price_snapshot DECIMAL(19,4) NOT NULL,
    gst_rate_snapshot DECIMAL(9,4) NOT NULL,
    line_item_value DECIMAL(19,4) NOT NULL,
    line_gst        DECIMAL(19,4) NOT NULL,
    line_total      DECIMAL(19,4) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_procurement_item_procurement FOREIGN KEY (procurement_id) REFERENCES procurement (id),
    CONSTRAINT fk_procurement_item_requirement_item FOREIGN KEY (requirement_item_id) REFERENCES requirement_item (id),
    CONSTRAINT fk_procurement_item_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    CONSTRAINT fk_procurement_item_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_procurement_item_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    CONSTRAINT fk_procurement_item_offer FOREIGN KEY (supplier_offer_id) REFERENCES supplier_offer (id),
    CONSTRAINT ck_procurement_item_quantity CHECK (requested_quantity > 0),
    -- One line per SKU per cart. Adding the same SKU again increases its quantity
    -- rather than producing two lines the restaurant has to reconcile by eye.
    CONSTRAINT uk_procurement_item_sku UNIQUE (procurement_id, supplier_sku_id),
    KEY ix_procurement_item_store (procurement_id, supplier_store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
