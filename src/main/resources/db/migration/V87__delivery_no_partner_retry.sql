-- D-185: a delivery nobody could take is retried automatically for a while, and then the supplier is offered
-- delivering it themselves. These columns say when the search started, how often it was retried, and whether the
-- offer has been made, so the job needs no query over the event history.
ALTER TABLE delivery
    ADD COLUMN no_partner_since DATETIME(6) NULL,
    ADD COLUMN auto_retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_retry_at DATETIME(6) NULL,
    ADD COLUMN own_delivery_offered_at DATETIME(6) NULL,
    ADD INDEX ix_delivery_no_partner (status, no_partner_since),
    ALGORITHM=INPLACE, LOCK=NONE;

-- Deliveries already stuck start their clock when they last failed.
UPDATE delivery d
   SET d.no_partner_since = COALESCE(
        (SELECT MAX(e.occurred_at) FROM delivery_event e WHERE e.delivery_id = d.id AND e.status = d.status),
        d.updated_at)
 WHERE d.status IN ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE') AND d.mode = 'COSTONOMY';
