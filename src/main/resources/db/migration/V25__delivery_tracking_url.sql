-- V25 — Delivery live tracking URL.
--
-- Stores external carrier tracking URL (e.g. Pidge live tracking link)
-- to expose to consumers and forward via WhatsApp/SMS notifications.

ALTER TABLE delivery
    ADD COLUMN tracking_url VARCHAR(1000) NULL AFTER drop_contact_phone;
