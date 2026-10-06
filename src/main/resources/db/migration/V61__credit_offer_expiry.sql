-- V61 — When a credit offer was made, so an offer nobody accepts can lapse after 14 India days (D-137).
--
-- credit_agreement.updated_at is bumped by every exposure movement and cannot say when the supplier made the offer,
-- so the offer carries its own timestamp. Set when the supplier approves on different terms or edits an APPROVED
-- offer; cleared when the line is activated, closed, or asked for again. Existing APPROVED offers start from their
-- last update, which is when they were last touched by anybody.
ALTER TABLE credit_agreement ADD COLUMN offer_made_at TIMESTAMP(6) NULL AFTER suspension_source;

UPDATE credit_agreement SET offer_made_at = updated_at WHERE status = 'APPROVED';
