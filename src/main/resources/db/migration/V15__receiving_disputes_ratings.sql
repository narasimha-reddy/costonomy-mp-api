-- V15 — Receiving, disputes and ratings.
--
-- Doc 01 §22–24, doc 02 §3, doc 03 §11–12, doc 04 §15–17, doc 09 §9.
--
-- The three things that happen after goods arrive, and they are deliberately
-- independent of each other and of the order.
--
-- **Receiving does not rewrite the order.** Doc 03 §11 says so explicitly: the
-- accepted quantities are what the supplier committed to and what was paid for,
-- and a shortfall at the door is a new fact recorded alongside them, not a
-- correction applied to them. Erasing the committed quantity would destroy the
-- evidence a dispute is argued from.
--
-- **A dispute is separate from order status.** Doc 01 §22 and doc 03 §12. An order
-- stays DELIVERED while a dispute runs; §23A.26 requires the app to say so. An
-- order status that moved on a complaint would mean the restaurant's own record of
-- what arrived depends on whether they complained about it.
--
-- **A rating is one per order and public.** Doc 01 §24: public to the marketplace
-- subject to moderation — moderation that removes, not moderation that gates, or
-- no rating would appear until somebody reviewed it.

-- What actually arrived. Doc 03 §11: PENDING → RECEIVED.
CREATE TABLE receiving (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- True when any line came up short, damaged or missing. Denormalised because
    -- every list of past orders wants it and nobody wants to sum line items to
    -- find out whether there was a problem.
    has_discrepancy TINYINT(1)   NOT NULL DEFAULT 0,
    -- Totals across lines, for the same reason.
    total_accepted_quantity DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_received_quantity DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_damaged_quantity  DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_missing_quantity  DECIMAL(19,4) NOT NULL DEFAULT 0,
    notes           VARCHAR(1000) NULL,
    received_by     BIGINT       NULL,
    received_at     TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_receiving_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_receiving_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_receiving_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_receiving_by FOREIGN KEY (received_by) REFERENCES users (id),
    -- One receiving per order. Receiving twice would double the delivered
    -- quantities every fill-rate calculation reads.
    CONSTRAINT uk_receiving_order UNIQUE (supplier_order_id),
    KEY ix_receiving_store (supplier_store_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Per line, because §23A.22 forbids a blind "Complete" button.
--
-- The three quantities partition what was accepted:
--
--     received + damaged + missing = accepted
--
-- `received` is usable stock, `damaged` arrived unusable, `missing` never arrived.
-- Keeping them distinct is what lets a dispute say which problem it is about —
-- collapsing damaged into missing would make "you sent me broken eggs" and "you
-- sent me no eggs" the same complaint.
CREATE TABLE receiving_item (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    receiving_id    BIGINT       NOT NULL,
    supplier_order_item_id BIGINT NOT NULL,
    -- Snapshotted from the order line. Doc 02 §4: a historical commercial value
    -- must survive, and the line may be re-read years later in a dispute.
    accepted_quantity DECIMAL(19,4) NOT NULL,
    received_quantity DECIMAL(19,4) NOT NULL DEFAULT 0,
    damaged_quantity  DECIMAL(19,4) NOT NULL DEFAULT 0,
    missing_quantity  DECIMAL(19,4) NOT NULL DEFAULT 0,
    unit            VARCHAR(32)  NULL,
    note            VARCHAR(500) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_receiving_item_receiving FOREIGN KEY (receiving_id) REFERENCES receiving (id),
    CONSTRAINT fk_receiving_item_order_item FOREIGN KEY (supplier_order_item_id)
        REFERENCES supplier_order_item (id),
    CONSTRAINT uk_receiving_item_line UNIQUE (receiving_id, supplier_order_item_id),
    CONSTRAINT ck_receiving_item_quantities CHECK (
        received_quantity >= 0 AND damaged_quantity >= 0 AND missing_quantity >= 0),
    KEY ix_receiving_item_receiving (receiving_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A restaurant's complaint about an order. Doc 01 §23, doc 03 §12.
--
-- OPEN → UNDER_REVIEW → RESPONDED → RESOLVED, or REJECTED from either of the
-- first two. Never touches the supplier order.
CREATE TABLE dispute (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispute_number  VARCHAR(40)  NOT NULL,
    supplier_order_id BIGINT     NOT NULL,
    receiving_id    BIGINT       NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    -- WRONG_PRODUCT, SHORT_QUANTITY, DAMAGED, EXPIRED, QUALITY,
    -- INCORRECT_INVOICE, OTHER. Doc 04 §16.
    category        VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    description     VARCHAR(2000) NOT NULL,
    -- What the restaurant believes they are out of pocket. Their claim, not a
    -- credit note: Mandi records disputes for intelligence and audit (doc 01 §23)
    -- and does not adjudicate money between the two parties.
    claimed_amount  DECIMAL(19,4) NULL,
    resolution      VARCHAR(2000) NULL,
    -- REPLACEMENT, REFUND, CREDIT_NOTE, NO_ACTION — what the two sides agreed,
    -- recorded rather than executed.
    resolution_type VARCHAR(32)  NULL,
    raised_by       BIGINT       NOT NULL,
    resolved_by     BIGINT       NULL,
    responded_at    TIMESTAMP(6) NULL,
    resolved_at     TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_dispute_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_dispute_receiving FOREIGN KEY (receiving_id) REFERENCES receiving (id),
    CONSTRAINT fk_dispute_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_dispute_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_dispute_raised_by FOREIGN KEY (raised_by) REFERENCES users (id),
    CONSTRAINT uk_dispute_number UNIQUE (dispute_number),
    -- Deliberately NOT unique per order: doc 01 §23 lists seven categories, and a
    -- delivery can be both short and damaged. One dispute per order would force a
    -- restaurant to pick which problem to report.
    KEY ix_dispute_order (supplier_order_id),
    KEY ix_dispute_store_status (supplier_store_id, status),
    KEY ix_dispute_outlet_status (outlet_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The lines a dispute is about. Append-only.
CREATE TABLE dispute_item (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispute_id      BIGINT       NOT NULL,
    supplier_order_item_id BIGINT NOT NULL,
    disputed_quantity DECIMAL(19,4) NOT NULL,
    reason          VARCHAR(500) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_dispute_item_dispute FOREIGN KEY (dispute_id) REFERENCES dispute (id),
    CONSTRAINT fk_dispute_item_order_item FOREIGN KEY (supplier_order_item_id)
        REFERENCES supplier_order_item (id),
    CONSTRAINT uk_dispute_item_line UNIQUE (dispute_id, supplier_order_item_id),
    KEY ix_dispute_item_dispute (dispute_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The conversation. Append-only.
--
-- `internal` marks a note between operators that neither party sees — §23A.32's
-- "never expose internal moderation notes unnecessarily". One table rather than
-- two so the moderator reads one thread in order; the flag is what the API filters
-- on, and filtering it is not optional.
CREATE TABLE dispute_message (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispute_id      BIGINT       NOT NULL,
    -- RESTAURANT, SUPPLIER or OPERATIONS.
    author_side     VARCHAR(32)  NOT NULL,
    author_id       BIGINT       NULL,
    message         VARCHAR(2000) NOT NULL,
    internal        TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_dispute_message_dispute FOREIGN KEY (dispute_id) REFERENCES dispute (id),
    CONSTRAINT fk_dispute_message_author FOREIGN KEY (author_id) REFERENCES users (id),
    KEY ix_dispute_message_dispute (dispute_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Photographs and documents. Append-only.
--
-- A reference, not the bytes: file storage is not in this version's scope, and a
-- blob column would be a storage decision made by accident.
CREATE TABLE dispute_evidence (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispute_id      BIGINT       NOT NULL,
    dispute_message_id BIGINT    NULL,
    -- IMAGE, DOCUMENT, VIDEO.
    evidence_type   VARCHAR(32)  NOT NULL,
    reference       VARCHAR(1000) NOT NULL,
    caption         VARCHAR(500) NULL,
    uploaded_by     BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_dispute_evidence_dispute FOREIGN KEY (dispute_id) REFERENCES dispute (id),
    CONSTRAINT fk_dispute_evidence_message FOREIGN KEY (dispute_message_id)
        REFERENCES dispute_message (id),
    CONSTRAINT fk_dispute_evidence_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    KEY ix_dispute_evidence_dispute (dispute_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One rating per completed order. Doc 01 §24, §23A.23.
CREATE TABLE rating (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    -- 1–5. Overall is required; the four dimensions are optional, because a
    -- restaurant in a hurry should be able to leave a rating rather than abandon
    -- a five-field form.
    -- INT rather than TINYINT: the range is enforced by the CHECK below, and a
    -- narrower column would only buy three bytes a row while forcing every entity
    -- field to carry a columnDefinition to satisfy ddl-auto=validate (D-009's
    -- cousin).
    overall_rating  INT          NOT NULL,
    product_quality_rating INT   NULL,
    quantity_accuracy_rating INT NULL,
    packaging_rating INT         NULL,
    delivery_rating INT          NULL,
    comment         VARCHAR(2000) NULL,
    -- PUBLISHED or HIDDEN. Doc 01 §24: public subject to moderation — moderation
    -- that removes, not moderation that gates. Pre-moderation would mean no
    -- rating appears until someone reviews it, and a marketplace whose ratings
    -- lag by a working day has no ratings.
    moderation_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    moderation_reason VARCHAR(500) NULL,
    moderated_by    BIGINT       NULL,
    moderated_at    TIMESTAMP(6) NULL,
    rated_by        BIGINT       NOT NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_rating_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_rating_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_rating_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_rating_by FOREIGN KEY (rated_by) REFERENCES users (id),
    -- §23A.23: prevent duplicate rating submission. Enforced here rather than by
    -- the client, because a double tap is the normal way this happens.
    CONSTRAINT uk_rating_order UNIQUE (supplier_order_id),
    CONSTRAINT ck_rating_overall CHECK (overall_rating between 1 and 5),
    KEY ix_rating_store (supplier_store_id, moderation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Dispute numbering, mirroring order and invoice numbering.
CREATE TABLE dispute_number_sequence (
    sequence_date   DATE         NOT NULL,
    next_value      BIGINT       NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Permissions this phase needs ────────────────────────────────────────
--
-- Doc 04 §16 requires `POST /disputes/{id}/response`, and doc 09 §9 requires
-- rating moderation — but doc 03 §14's permission list has neither. Adding them
-- rather than reusing a neighbour: gating a write behind a read permission
-- (`ORDER_VIEW`) is the shortcut that makes an authorization model impossible to
-- reason about later, and `DISPUTE_MODERATE` is an internal permission a supplier
-- must never hold.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('DISPUTE_RESPOND', 'Respond to disputes', 'SUPPLIER',
 'Answer a dispute raised against this store''s order', NOW(6), NOW(6)),
('RATING_MODERATE', 'Moderate ratings', 'INTERNAL',
 'Hide or restore a published rating', NOW(6), NOW(6));

-- Supplier roles that own orders can answer for them.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'DISPUTE_RESPOND'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN', 'SUP_STORE_MANAGER', 'SUP_SALESPERSON');

-- Moderation belongs to the roles that already moderate.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'RATING_MODERATE'
 WHERE r.code IN ('OPS_MODERATION', 'OPS_ADMIN');
