-- V91 — What booking a food delivery needs that V88 did not carry (D-197).
--
--   drop_city, drop_state, drop_pincode   couriers will not take a booking without the customer's locality, and the
--                                         request has to supply it: it cannot be looked up from a free-text address.
--   max_fee                the most the restaurant agreed to pay, and the amount held from its wallet when the delivery
--                          was created. Only couriers quoting at or below it are asked.
--   fee_refunded_amount    how much of max_fee has been given back. Net held = max_fee - fee_refunded_amount. The held
--                          amount stays until pickup (a dearer replacement courier needs no second debit); the unused
--                          part is refunded then, and all of it if the delivery is cancelled before pickup or fails.
--   round_started_at       when the current round of asking couriers began. Couriers tried in earlier rounds may be
--                          asked again within the retry window, so "already tried" is counted from here.
-- Additive and nullable (fee_refunded_amount defaults to 0), so existing rows are untouched.
ALTER TABLE food_delivery
    ADD COLUMN drop_city VARCHAR(120) NULL,
    ADD COLUMN drop_state VARCHAR(120) NULL,
    ADD COLUMN drop_pincode VARCHAR(16) NULL,
    ADD COLUMN max_fee DECIMAL(19,4) NULL,
    ADD COLUMN fee_refunded_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN round_started_at TIMESTAMP(6) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
