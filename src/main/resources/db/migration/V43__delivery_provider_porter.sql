-- V43 — Porter as a delivery provider alongside Pidge, Borzo and Shadowfax.
--
-- Seeded disabled (enabled = 0), same as PIDGE, BORZO and SHADOWFAX. The adapter bean has
-- its own independent flag (costonomy.mp.porter.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Porter in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('PORTER', 'Porter', 0, 1, 1, 25, NOW(6), NOW(6));
