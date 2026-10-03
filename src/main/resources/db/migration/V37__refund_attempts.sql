-- How many times a refund has been sent to the provider (D-101).
--
-- A refund that fails transiently is retried with the same provider key, which is
-- safe; one that fails every time was retried every thirty seconds for ever, with
-- nobody told. The count lets the job stop after a few tries and hand it to a
-- person (NEEDS_REVIEW), and every claim now changes the row, so two job runs can
-- no longer both believe they claimed the same refund.
ALTER TABLE refund
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status;
