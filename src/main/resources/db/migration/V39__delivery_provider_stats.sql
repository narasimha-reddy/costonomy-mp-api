-- Nightly-aggregated reliability metrics per delivery provider.
--
-- One row per (provider_code, window_date): window_date is the calendar day
-- whose completed deliveries contributed to this row. A nightly job inserts the
-- previous day's row and never updates existing rows, so the table is
-- effectively append-only and safe to query without locking.
--
-- All counts refer only to deliveries where this provider was the active
-- provider at the time of the event, not earlier waterfall attempts.

CREATE TABLE delivery_provider_stats (
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    provider_code           VARCHAR(64)  NOT NULL,

    -- The calendar day (UTC) this row summarises.
    window_date             DATE         NOT NULL,

    -- Deliveries reaching DRIVER_ASSIGNED on this day via this provider.
    total_bookings          INT          NOT NULL DEFAULT 0,

    -- Provider accepted booking but driver cancelled before pickup.
    driver_cancellations    INT          NOT NULL DEFAULT 0,

    -- Driver arrived but could not collect.
    pickup_failures         INT          NOT NULL DEFAULT 0,

    -- Goods collected but delivery ultimately failed.
    delivery_failures       INT          NOT NULL DEFAULT 0,

    -- Delivered but later than the quoted ETA from DRIVER_ASSIGNED.
    eta_overruns            INT          NOT NULL DEFAULT 0,

    -- Deliveries that reached DELIVERED state on this day (denominator for
    -- eta_overruns and avg_actual_eta_minutes).
    completed_deliveries    INT          NOT NULL DEFAULT 0,

    -- Average actual minutes from DRIVER_ASSIGNED → DELIVERED (NULL if none
    -- completed on this day).
    avg_actual_eta_minutes  INT                   DEFAULT NULL,

    -- Average quoted ETA minutes for bookings on this day.
    avg_quoted_eta_minutes  INT                   DEFAULT NULL,

    -- Average price deviation: actual ledger cost minus quoted amount, in INR.
    -- Positive = provider charged more than quoted (surge). NULL if no data.
    avg_price_deviation_inr DECIMAL(10, 4)        DEFAULT NULL,

    created_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uq_provider_window (provider_code, window_date),
    INDEX idx_provider_stats_date (window_date),
    INDEX idx_provider_stats_code (provider_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
