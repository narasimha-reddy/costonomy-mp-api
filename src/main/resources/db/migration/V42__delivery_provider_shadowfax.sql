-- V42 — Shadowfax as a delivery provider alongside Pidge and Borzo.
--
-- Seeded disabled (enabled = 0), same as PIDGE and BORZO. The adapter bean has
-- its own independent flag (costonomy.mp.shadowfax.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Shadowfax in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('SHADOWFAX', 'Shadowfax', 0, 1, 1, 20, NOW(6), NOW(6));
