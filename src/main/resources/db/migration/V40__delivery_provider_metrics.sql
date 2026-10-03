-- V27__delivery_provider_metrics.sql
-- Table to store per-provider latency and cost metrics aggregated in 2-hour windows

CREATE TABLE delivery_provider_metrics (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code       VARCHAR(64)     NOT NULL,
    window_start        DATETIME(3)     NOT NULL,
    avg_latency_ms      DOUBLE          NOT NULL DEFAULT 0.0,
    p95_latency_ms      DOUBLE          NOT NULL DEFAULT 0.0,
    total_cost_inr      DECIMAL(12, 4)  NOT NULL DEFAULT 0.0000,
    order_count         BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uq_provider_metrics_window (provider_code, window_start),
    INDEX idx_provider_metrics_window (window_start),
    INDEX idx_provider_metrics_code (provider_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
