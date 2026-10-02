-- V42 — How a top-up was paid, for the wallet history (D-108).
--
-- The wallet history shows where money came from ("Card •1007", "UPI"), which
-- Razorpay knows and, until now, we did not keep. It is set once, at the moment
-- the payment is fetched from Razorpay and the top-up is credited (or returned),
-- from what Razorpay itself reports.
--
-- What is stored is deliberately the least that reads well:
--   payment_method  card | upi | netbanking | wallet | emi ... as Razorpay names it
--   payment_detail  a card's last four digits, or a provider wallet's name; NULL
--                   for UPI and netbanking. Never a full card number, a UPI
--                   address or a bank account: none is needed to say "where from",
--                   and each is something we would then have to protect.
--
-- Both are NULL for every top-up made before this migration and for any payment
-- whose method Razorpay did not report. The history shows those without an
-- instrument rather than guessing one.

ALTER TABLE wallet_top_up
    ADD COLUMN payment_method VARCHAR(32) NULL AFTER razorpay_payment_id,
    ADD COLUMN payment_detail VARCHAR(64) NULL AFTER payment_method;
