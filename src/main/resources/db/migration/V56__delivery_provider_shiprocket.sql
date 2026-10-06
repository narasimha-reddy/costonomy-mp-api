-- V56 — Shiprocket as a delivery provider alongside Pidge, Borzo, Shadowfax and Porter.
--
-- Seeded disabled (enabled = 0), same as PIDGE, BORZO, SHADOWFAX and PORTER. The adapter bean has
-- its own independent flag (costonomy.mp.shiprocket.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Shiprocket in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('SHIPROCKET', 'Shiprocket', 0, 1, 1, 30, NOW(6), NOW(6));
