-- V89 — What a courier-selection decision engine needs to see (D-197 addendum).
--
-- Additive and nullable on both flows, so existing code and old rows are untouched. Written going forward only: a row
-- from before this migration simply has no cause, no timing and no decision snapshot, and anything that reads these
-- columns must treat NULL as "not recorded", never as "fine".
--
-- Why each column:
--   ended_cause        why an attempt ended, as a code. A CANCELLED attempt could be the rider, the provider, an
--                      assignment timeout, ops, or us; only free text said which.
--   retryable          the provider exception's own flag, which was thrown away.
--   assigned_at        when this attempt's rider was assigned, so time-to-assign needs no join over events.
--   call_ms            how long the provider's API took to answer. Not delivery duration: a slow or timing-out API
--                      is an early warning that nothing else shows.
--   rank_position      1 = first choice of the couriers considered for this attempt.
--   selection_reason   CHEAPEST, RELIABILITY (ranked on reliability after a failure), PROBE (a recovering courier tried
--                      once), ONLY_OPTION.
--   reliability_score, breaker_state   what the engine believed about this courier when it chose it, so a decision can
--                      be audited and the weights tuned against what happened next.
--   decision           every courier considered for this attempt and why each was ranked or skipped.
--   error_class (quotes)   TIMEOUT, TRANSIENT, REFUSED or UNSERVICEABLE: a timeout and a refusal mean different things.

ALTER TABLE delivery_provider_attempt
    ADD COLUMN ended_cause VARCHAR(40) NULL,
    ADD COLUMN retryable TINYINT(1) NULL,
    ADD COLUMN assigned_at TIMESTAMP(6) NULL,
    ADD COLUMN call_ms INT NULL,
    ADD COLUMN rank_position INT NULL,
    ADD COLUMN selection_reason VARCHAR(32) NULL,
    ADD COLUMN reliability_score DECIMAL(5,4) NULL,
    ADD COLUMN breaker_state VARCHAR(16) NULL,
    ADD COLUMN decision JSON NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery_attempt
    ADD COLUMN ended_cause VARCHAR(40) NULL,
    ADD COLUMN retryable TINYINT(1) NULL,
    ADD COLUMN assigned_at TIMESTAMP(6) NULL,
    ADD COLUMN call_ms INT NULL,
    ADD COLUMN rank_position INT NULL,
    ADD COLUMN selection_reason VARCHAR(32) NULL,
    ADD COLUMN reliability_score DECIMAL(5,4) NULL,
    ADD COLUMN breaker_state VARCHAR(16) NULL,
    ADD COLUMN decision JSON NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE delivery_quote
    ADD COLUMN call_ms INT NULL,
    ADD COLUMN error_class VARCHAR(40) NULL,
    ADD COLUMN retryable TINYINT(1) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

-- Food quotes also lacked the vehicle the supplier ones record.
ALTER TABLE food_delivery_quote
    ADD COLUMN call_ms INT NULL,
    ADD COLUMN error_class VARCHAR(40) NULL,
    ADD COLUMN retryable TINYINT(1) NULL,
    ADD COLUMN vehicle_type VARCHAR(32) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
