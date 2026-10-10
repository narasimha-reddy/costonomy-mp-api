-- Whether the restaurant wants a request delivered or will collect it (D-143), set per supplier's request (draft).
-- DELIVERY is what every request implicitly was.
ALTER TABLE intent ADD COLUMN delivery_preference VARCHAR(16) NOT NULL DEFAULT 'DELIVERY';
