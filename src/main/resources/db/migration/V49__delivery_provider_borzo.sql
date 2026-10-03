-- V49 — Borzo as a second Costonomy-delivery provider, alongside Pidge.
--
-- Seeded disabled (enabled = 0), same as PIDGE was in V37. The adapter bean has
-- its own independent flag (costonomy.mp.borzo.enabled) rather than sharing
-- costonomy.mp.providers.delivery, so Borzo can run alongside Pidge or the
-- mocks instead of replacing whichever one that single-valued switch names.
-- Flipping this row to enabled = 1 is the operational act that actually puts
-- Borzo in the auction — doc 06 §11's registry requires both.
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('BORZO', 'Borzo', 0, 1, 0, 15, NOW(6), NOW(6));
