-- V56 — Xpressbees as an on-demand intra-city and express delivery provider.
--
-- Seeded disabled (enabled = 0), same as other carriers. The adapter bean has
-- its own independent flag (costonomy.mp.xpressbees.enabled). Flipping this row
-- to enabled = 1 is the operational act that puts Xpressbees in the auction.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('XPRESSBEES', 'Xpressbees', 0, 1, 1, 25, NOW(6), NOW(6));
