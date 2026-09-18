-- The guard for V26's columns, one migration later, and that separation is the
-- point rather than tidiness.
--
-- V26 adds the columns and backfills the requests already in flight. Adding a
-- validating CHECK in that same migration failed against a database that had
-- any: the constraint was validated without seeing the backfill the statement
-- above it had just written, and Flyway recorded the whole migration as failed
-- and refused to start the application. It applied perfectly against an empty
-- database, which is exactly why the replay used to check migrations did not
-- catch it -- a backfill is a no-op with nothing to backfill.
--
-- Split, each migration does one thing against data the previous one has
-- committed, and neither can fail on the other's account.
ALTER TABLE intent
    ADD CONSTRAINT ck_intent_response_window CHECK (
        (sent_at IS NULL AND response_deadline IS NULL AND response_window_seconds IS NULL)
        OR (sent_at IS NOT NULL AND response_deadline IS NOT NULL
            AND response_window_seconds IS NOT NULL));

-- The unanswered-request sweep runs on this, and it is time-ordered.
CREATE INDEX ix_intent_response_deadline ON intent (status, response_deadline);
