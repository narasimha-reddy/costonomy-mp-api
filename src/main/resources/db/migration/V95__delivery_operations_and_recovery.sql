-- V95 — Delivery operations, failure recovery, and reconciliation (D-201).
--
--   delivery_recovery_task: Durable retry queue for provider cancellations, wallet refunds,
--                           and reconciliation tasks that failed transiently.
--   reconciliation_required: Flag on delivery and food_delivery indicating ambiguous provider
--                            outcomes or exhausted retries requiring operator intervention.

CREATE TABLE delivery_recovery_task (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow                 VARCHAR(16)   NOT NULL, -- 'SUPPLIER' or 'FOOD'
    delivery_id          BIGINT        NOT NULL,
    outlet_id            BIGINT        NULL,
    task_type            VARCHAR(32)   NOT NULL, -- 'PROVIDER_CANCEL', 'WALLET_REFUND', 'PROVIDER_RECONCILE'
    provider_code        VARCHAR(64)   NULL,
    provider_delivery_id VARCHAR(200)  NULL,
    idempotency_ref      VARCHAR(100)  NULL,
    amount               DECIMAL(19,4) NULL,
    reason               VARCHAR(500)  NULL,
    status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'PROCESSING', 'COMPLETED', 'EXHAUSTED', 'FAILED'
    attempts             INT           NOT NULL DEFAULT 0,
    max_attempts         INT           NOT NULL DEFAULT 5,
    next_retry_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error           VARCHAR(1000) NULL,
    created_at           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY ix_delivery_recovery_status_retry (status, next_retry_at),
    KEY ix_delivery_recovery_delivery (flow, delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE delivery
    ADD COLUMN reconciliation_required TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_reason VARCHAR(255) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery
    ADD COLUMN reconciliation_required TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_reason VARCHAR(255) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
