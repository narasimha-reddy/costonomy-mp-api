-- V93 — A/B Testing & Rapid Feature Experimentation Platform
--
-- World-class, enterprise-grade experimentation framework for backend & client feature toggling,
-- multi-variant testing, targeted rollouts, and deterministic salted bucketing.
-- Purely additive: existing tables and columns are completely untouched.
--
-- Core capabilities:
--  1. Deterministic Bucketing: MurmurHash3 algorithm over (subject_id + salt + experiment_key)
--     mapped to [0, 9999] for uniform, orthogonal assignment without cross-experiment bias.
--  2. Traffic Rollout: traffic_allocation_pct (0-100%) allows canary rollouts and gradual ramp.
--  3. Dynamic Variants & Config Payloads: Each variant can define custom JSON payloads for
--     feature parameters (pricing multipliers, timeouts, UI variations, algorithm weights).
--  4. Rule-based Audience Targeting: targeting_rules JSON supports user roles, pincodes,
--     numeric bounds, or arbitrary context attributes.
--  5. Subject Overrides: JSON whitelist mapping variants to subjects for instant QA & testing.
--  6. Impression & Conversion Logging: Append-only audit tables for evaluations and conversion events.

CREATE TABLE experiment (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_key          VARCHAR(64)  NOT NULL,
    name                    VARCHAR(128) NOT NULL,
    description             TEXT         NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'DRAFT', -- DRAFT, ACTIVE, PAUSED, ARCHIVED
    subject_type            VARCHAR(32)  NOT NULL DEFAULT 'USER',  -- USER, SUPPLIER, RESTAURANT, DEVICE, SESSION
    traffic_allocation_pct  INT          NOT NULL DEFAULT 100,     -- 0 to 100%
    salt                    VARCHAR(64)  NOT NULL,                 -- Unique per experiment to decorrelate hashing
    layer                   VARCHAR(64)  NULL,                     -- For mutually exclusive experiment groups
    targeting_rules         JSON         NULL,                     -- Attribute evaluation rules
    overrides               JSON         NULL,                     -- Force subject assignments: {"treatment": ["101"]}
    start_time              TIMESTAMP(6) NULL,
    end_time                TIMESTAMP(6) NULL,
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_experiment_key UNIQUE (experiment_key),
    KEY ix_experiment_status (status),
    KEY ix_experiment_layer (layer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE experiment_variant (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_id           BIGINT       NOT NULL,
    variant_key             VARCHAR(64)  NOT NULL,
    name                    VARCHAR(128) NOT NULL,
    description             VARCHAR(255) NULL,
    weight                  INT          NOT NULL DEFAULT 50,      -- Relative weight for traffic split
    is_control              TINYINT(1)   NOT NULL DEFAULT 0,
    payload_json            JSON         NULL,                     -- Configuration payload for the variant
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_exp_variant_experiment FOREIGN KEY (experiment_id) REFERENCES experiment (id) ON DELETE CASCADE,
    CONSTRAINT uk_exp_variant_key UNIQUE (experiment_id, variant_key),
    KEY ix_exp_variant_experiment (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE experiment_exposure (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_key          VARCHAR(64)  NOT NULL,
    variant_key             VARCHAR(64)  NOT NULL,
    subject_id              VARCHAR(128) NOT NULL,
    subject_type            VARCHAR(32)  NOT NULL,
    evaluation_reason       VARCHAR(64)  NOT NULL,
    attributes_snapshot     JSON         NULL,
    exposed_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    KEY ix_exp_exposure_subject (experiment_key, subject_id),
    KEY ix_exp_exposure_time (exposed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE experiment_metric_event (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    subject_id              VARCHAR(128) NOT NULL,
    metric_name             VARCHAR(64)  NOT NULL,
    numeric_value           DECIMAL(19,4) NULL,
    properties              JSON         NULL,
    recorded_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    KEY ix_exp_metric_name_subject (metric_name, subject_id),
    KEY ix_exp_metric_time (recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Seed an active benchmark experiment for courier selection strategy
INSERT INTO experiment (experiment_key, name, description, status, subject_type, traffic_allocation_pct, salt, targeting_rules, overrides)
VALUES (
    'courier_selection_strategy',
    'Courier Selection Strategy Optimization',
    'Tests reliability-weighted courier selection versus cheapest-first courier selection for delivery fulfillment.',
    'ACTIVE',
    'RESTAURANT',
    100,
    'b43a9e88-3c67-4bb9-bf39-4d8bb51d92aa',
    '{"operator":"AND","conditions":[{"attribute":"environment","operator":"IN","values":["production","staging","test"]}]}',
    '{"treatment":["qa_partner_1","test_outlet_99"]}'
);

INSERT INTO experiment_variant (experiment_id, variant_key, name, description, weight, is_control, payload_json)
SELECT id, 'control', 'Cheapest First', 'Default lowest quote selection algorithm', 50, 1, '{"strategy":"CHEAPEST_FIRST","timeoutMs":3000,"allowFallback":true}'
FROM experiment WHERE experiment_key = 'courier_selection_strategy';

INSERT INTO experiment_variant (experiment_id, variant_key, name, description, weight, is_control, payload_json)
SELECT id, 'treatment', 'Reliability Weighted', 'Reliability and SLA weighted ranking score algorithm', 50, 0, '{"strategy":"RELIABILITY_WEIGHTED","timeoutMs":2500,"allowFallback":true,"reliabilityWeight":0.4}'
FROM experiment WHERE experiment_key = 'courier_selection_strategy';
