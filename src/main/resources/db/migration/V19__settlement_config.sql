-- V19 — The settlement offset, as configuration rather than a constant.
--
-- Doc 01 §17: "Default: T+2. Settlement may be configurable." Doc 09 §10 lists
-- settlement timing among the values operations must be able to change.
--
-- A row rather than only a property because a property cannot be changed without
-- a deploy, and because doc 09 §10 requires configuration changes to be versioned
-- and audited — which `AdminConfigService` does for `app_config` rows and cannot
-- do for a file.
INSERT INTO app_config
    (config_key, config_value, value_type, config_version, description,
     effective_from, status, created_at, updated_at)
VALUES
('settlement.offsetDays', '2', 'INT', 1,
 'Days after the settlement period ends that a supplier is paid. Doc 01 §17: T+2.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6));
