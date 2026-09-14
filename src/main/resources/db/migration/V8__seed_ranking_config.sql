-- V8 — Best Value ranking weights.
--
-- Doc 07 §4 requires the weights to be "versioned"; doc 09 §10 lists ranking
-- weights among the values operations can change without a deploy. So they live
-- in app_config rather than in code, and a change inserts a new
-- `config_version` rather than editing the row — the same effective-dating rule
-- as commission (doc 09 §11).
--
-- Why versioned at all: a recommendation is an explanation shown to a
-- restaurant ("Best value", "Fastest"). Reconstructing why a supplier was
-- recommended last month requires the weights as they were then.
--
-- **Commission is not in this table and must never be added.** Guardrail 9 and
-- doc 07 §4: commission is neither a positive nor a negative ranking factor.
-- RankingWeightsTest asserts the configured keys contain no such term.

INSERT INTO app_config
    (config_key, config_value, value_type, config_version, description,
     effective_from, status, created_at, updated_at)
VALUES
-- Weights are relative, not required to sum to 1. The scorer normalises across
-- whichever components actually have data — see BestValueScorer for why that
-- matters more than the numbers themselves.
('ranking.weight.price', '0.40', 'DECIMAL', 1,
 'Effective commercial cost, normalised across the candidate set',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.weight.eta', '0.20', 'DECIMAL', 1,
 'Estimated time to delivery, normalised across the candidate set',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.weight.availability', '0.15', 'DECIMAL', 1,
 'Whether the supplier can cover the full requested quantity',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

-- The four signals below come from order history and do not exist until orders
-- do (Phase 8+). Until then the scorer redistributes their weight rather than
-- substituting a value — see the cold-start note in BestValueScorer.
('ranking.weight.fillRate', '0.10', 'DECIMAL', 1,
 'Share of accepted quantity actually delivered. Requires order history.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.weight.onTime', '0.08', 'DECIMAL', 1,
 'Share of deliveries arriving by the promised time. Requires order history.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.weight.rating', '0.04', 'DECIMAL', 1,
 'Average restaurant rating. Requires ratings.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.weight.reliability', '0.03', 'DECIMAL', 1,
 'Inverse of acceptance-timeout and cancellation rates. Requires order history.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

-- Thresholds above which an explanation code may be shown. Doc 07 §5 forbids
-- displaying an explanation the calculated data does not support, so a supplier
-- with no history reaches none of these.
('ranking.explain.fillRateThreshold', '0.95', 'DECIMAL', 1,
 'Fill rate at or above which HIGH_FILL_RATE may be shown',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.explain.onTimeThreshold', '0.90', 'DECIMAL', 1,
 'On-time rate at or above which RELIABLE_SUPPLIER may be shown',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('ranking.explain.minOrdersForTrust', '20', 'INTEGER', 1,
 'Completed orders required before performance signals are considered meaningful',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

-- Serviceability and ETA. Doc 41: geography first, with supplier configuration.
('serviceability.defaultRadiusKm', '25', 'DECIMAL', 1,
 'Delivery radius assumed when a store has not configured one',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('eta.averageSpeedKmph', '20', 'DECIMAL', 1,
 'Assumed city transit speed for distance-based ETA, before live provider quotes',
 NOW(6), 'ACTIVE', NOW(6), NOW(6)),

('eta.dispatchOverheadMinutes', '15', 'INTEGER', 1,
 'Fixed allowance for pickup and handover, added to preparation and transit',
 NOW(6), 'ACTIVE', NOW(6), NOW(6));
