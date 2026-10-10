-- V58 — Blowhorn as an on-demand intra-city delivery provider alongside Pidge, Borzo, Shadowfax, Porter, Shiprocket, and LoadShare.
--
-- Seeded disabled (enabled = 0), same as other carriers. The adapter bean has
-- its own independent flag (costonomy.mp.blowhorn.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Blowhorn in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('BLOWHORN', 'Blowhorn', 0, 1, 1, 23, NOW(6), NOW(6));
