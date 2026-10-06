-- Indexes for queries the database-bottleneck audit found scanning growing tables (D-148,
-- docs/performance/DB_BOTTLENECKS.md). Each serves a named query; none changes behaviour.
-- ALGORITHM=INPLACE LOCK=NONE so a large table is not blocked while the index builds.

-- The hottest catalog lookup: the ACTIVE offer of one SKU (intents, orders, subscriptions, SKU detail). One row per SKU
-- per price change, so history grows daily; ix_offer_sku_history (sku, effective_from) has no status.
CREATE INDEX ix_offer_sku_status ON supplier_offer (supplier_sku_id, status) ALGORITHM=INPLACE LOCK=NONE;

-- Hourly settlement scans COMPLETED orders by updated_at; the admin dashboard counts orders by created_at.
CREATE INDEX ix_supplier_order_status_updated ON supplier_order (status, updated_at) ALGORITHM=INPLACE LOCK=NONE;
CREATE INDEX ix_supplier_order_created ON supplier_order (created_at) ALGORITHM=INPLACE LOCK=NONE;

-- Admin dashboard time-range counts.
CREATE INDEX ix_payment_created ON payment (created_at) ALGORITHM=INPLACE LOCK=NONE;
CREATE INDEX ix_payment_captured ON payment (captured_at) ALGORITHM=INPLACE LOCK=NONE;
CREATE INDEX ix_dispute_created ON dispute (created_at) ALGORITHM=INPLACE LOCK=NONE;
CREATE INDEX ix_dispute_resolved ON dispute (resolved_at) ALGORITHM=INPLACE LOCK=NONE;

-- Refund sweeps that run every minute: open late successes, and recently reversed refunds in id order.
CREATE INDEX ix_refund_late_success ON refund (late_success_at, late_success_resolved_at) ALGORITHM=INPLACE LOCK=NONE;
CREATE INDEX ix_refund_reversed ON refund (status, reversed_at, id) ALGORITHM=INPLACE LOCK=NONE;

-- Outbox claim: status = 'PENDING' ordered by id. ix_outbox_dispatch (status, next_attempt_at, id) cannot serve that
-- order when next_attempt_at is nullable and ranged, so a backlog is filesorted.
CREATE INDEX ix_outbox_status_id ON outbox_event (status, id) ALGORITHM=INPLACE LOCK=NONE;

-- Wallet history reads top-ups by created_at; the existing key leads with credited_at.
CREATE INDEX ix_wallet_top_up_outlet_created ON wallet_top_up (outlet_id, status, created_at) ALGORITHM=INPLACE LOCK=NONE;

-- SKU reviews, newest first per SKU.
CREATE INDEX ix_sku_review_sku_created ON sku_review (supplier_sku_id, moderation_status, created_at) ALGORITHM=INPLACE LOCK=NONE;

-- Browse-all products by status (the category index leads with category_id).
CREATE INDEX ix_canonical_status_name ON canonical_product (status, name) ALGORITHM=INPLACE LOCK=NONE;
