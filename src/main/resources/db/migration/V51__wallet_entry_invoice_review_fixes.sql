-- V51 — Review fixes for the shop's bill (D-115).
--
-- Additive only: seven new columns, no existing row changes.
--
--  * extract_calls: how many times the cost app's extraction was called for this bill. Counted before the call,
--    in its own transaction, so a reading whose result cannot be written still counts. Capped by max-attempts:
--    one bill can never cause more extraction calls than that.
--  * unavailable_count / next_try_at: the cost app could not be used (sign-in refused, 403, 408, 429, 5xx). Not
--    the bill's fault, so no attempt is used; the next try waits, doubling up to 30 minutes.
--  * review_history_json: the earlier reviews' totals, newest last, at most 20 ({at, by, total, version}).
--  * review_idem_*: the last Idempotency-Key a review was saved with, the hash of what it carried and the version
--    it produced, so a retried save whose answer was lost is answered again instead of being refused as stale.

ALTER TABLE wallet_entry_invoice
    ADD COLUMN extract_calls        INT          NOT NULL DEFAULT 0,
    ADD COLUMN unavailable_count    INT          NOT NULL DEFAULT 0,
    ADD COLUMN next_try_at          TIMESTAMP(6) NULL,
    ADD COLUMN review_history_json  JSON         NULL,
    ADD COLUMN review_idem_key      VARCHAR(128) NULL,
    ADD COLUMN review_idem_hash     CHAR(64)     NULL,
    ADD COLUMN review_idem_version  BIGINT       NULL;
