-- V92 — A/B Testing Attribution & Mutual Exclusion Layers
--
-- Adds unambiguous metric event attribution (direct experiment/variant linkage and exposure reference)
-- and indexes for layer-level deterministic mutual exclusion and exposure filtering.

ALTER TABLE experiment_metric_event
    ADD COLUMN experiment_key VARCHAR(64) NULL,
    ADD COLUMN variant_key VARCHAR(64) NULL,
    ADD COLUMN exposure_id BIGINT NULL;

ALTER TABLE experiment_metric_event
    ADD CONSTRAINT fk_exp_metric_exposure
    FOREIGN KEY (exposure_id) REFERENCES experiment_exposure(id) ON DELETE SET NULL;

CREATE INDEX ix_exp_metric_exp_var ON experiment_metric_event (experiment_key, variant_key);
CREATE INDEX ix_exp_exposure_reason ON experiment_exposure (experiment_key, evaluation_reason, exposed_at);
