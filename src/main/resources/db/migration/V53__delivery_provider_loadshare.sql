-- V53 — LoadShare Networks as a delivery provider alongside Pidge, Borzo, Shadowfax, Porter and Shiprocket.
--
-- Seeded disabled (enabled = 0), same as other carriers. The adapter bean has
-- its own independent flag (costonomy.mp.loadshare.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts LoadShare in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('LOADSHARE', 'LoadShare', 0, 1, 1, 22, NOW(6), NOW(6));
