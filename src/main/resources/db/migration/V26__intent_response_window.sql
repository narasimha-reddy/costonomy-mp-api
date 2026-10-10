-- The supplier's clock moves to the request.
--
-- It was on the order, and that is now the wrong place. Under the request
-- architecture (D-088) a supplier commits when they answer a request; the order
-- that follows is already agreed, needs no acceptance, and has no countdown. So
-- the store's `response_sla_seconds` -- the supplier's own promise about how
-- quickly they reply -- was governing an order nobody had to answer, while the
-- request they *did* have to answer ran on a global default that ignored their
-- configuration entirely. Exactly backwards.
--
-- `response_deadline` is snapshotted at send time from the store's SLA, for the
-- same reason `order_creation_deadline` is snapshotted at acceptance: a supplier
-- changing their SLA must not move the deadline on a request already in flight,
-- and a deadline recomputed from today's configuration would resurrect expired
-- requests every time somebody widened the setting.
--
-- Both columns are nullable because a DRAFT has neither: nothing is promised
-- until the request is sent.
ALTER TABLE intent
    ADD COLUMN response_window_seconds INT NULL AFTER sent_at,
    ADD COLUMN response_deadline TIMESTAMP(6) NULL AFTER response_window_seconds;

-- Backfill before the constraint, or it refuses to apply: requests sent under the
-- old scheme have a sent_at and no deadline, and MySQL validates a new CHECK
-- against the rows already there. Each gets the window its own store promises,
-- measured from when it was actually sent -- which is the same rule new requests
-- will follow, so nothing is invented and nothing is retroactively shortened.
UPDATE intent i
    JOIN supplier_store s ON s.id = i.supplier_store_id
   SET i.response_window_seconds = GREATEST(s.response_sla_seconds, 300),
       i.response_deadline = i.sent_at
            + INTERVAL GREATEST(s.response_sla_seconds, 300) SECOND
 WHERE i.sent_at IS NOT NULL;
