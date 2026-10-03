-- V55 — Delhivery as an on-demand intra-city and express delivery provider.
--
-- Seeded disabled (enabled = 0), same as other carriers. The adapter bean has
-- its own independent flag (costonomy.mp.delhivery.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Delhivery in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('DELHIVERY', 'Delhivery', 0, 1, 1, 24, NOW(6), NOW(6));
