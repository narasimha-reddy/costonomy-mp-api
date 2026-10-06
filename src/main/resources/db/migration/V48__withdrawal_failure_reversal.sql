-- V48 — A withdrawal part the provider will not send comes back to the wallet (D-110).
--
-- A withdrawal takes the money out of the wallet at once and asks Razorpay to send it
-- back to the card afterwards, one refund per source payment. When Razorpay refuses a
-- part (the payment is unknown to it, was refunded by hand, is past its refund window,
-- or the account's balance is short) the money had left the wallet and gone nowhere.
-- This records what is needed to find out safely whether it really did not leave, to
-- put it back once, and to stop asking a source that will never accept it.
--
-- Additive only. No existing row changes meaning; the new refund statuses (REJECTED,
-- REVERSED) and wallet entry kind (WITHDRAWAL_REVERSAL) are VARCHAR values, and neither
-- column has a CHECK constraint (D-009).
--
-- (V44 to V46 are taken by branches not yet on this stack; V47 is branch 16's.)
--
-- This file has never been applied in any environment but the tests, so it is still edited in place.
-- Before it runs anywhere, check that no provider refund id is held twice (the unique index below would
-- otherwise fail AFTER the ALTERs above it have committed: MySQL does not roll DDL back, and the half-applied
-- migration then cannot be re-run):
--     select provider_refund_id, count(*) from refund
--      where provider_refund_id is not null group by provider_refund_id having count(*) > 1;
-- must return no rows. Any environment that already ran an EARLIER version of this file fails Flyway's
-- checksum check on this one: it needs `flyway repair` (columns already added) or these additions moved to a
-- V45. None is known to.

ALTER TABLE payment
    -- Razorpay will never (or can no longer) take a refund against this payment, so no
    -- withdrawal is drawn from it: its wallet money stays spendable. PAYMENT_UNKNOWN,
    -- NOT_CAPTURED, WINDOW_PASSED, REFUNDED_ELSEWHERE or OPS.
    ADD COLUMN provider_refund_blocked_at     TIMESTAMP(6) NULL,
    ADD COLUMN provider_refund_blocked_reason VARCHAR(64)  NULL;

ALTER TABLE refund
    -- Why the last send failed, in our own words (ProviderFailureKind), never Razorpay's.
    ADD COLUMN failure_kind          VARCHAR(32)  NULL AFTER failure_reason,
    -- The first time the refund was claimed for sending. Once set, every later send is
    -- preceded by a look at Razorpay for a refund of ours (a lost answer may have created one).
    ADD COLUMN sent_at               TIMESTAMP(6) NULL,
    -- The last time it was claimed for sending, on every send and not only the first. The age of an
    -- ambiguous refund is counted from here, not from updated_at, which any approval, verification or
    -- review note also moves (a second person could then never complete a re-credit).
    ADD COLUMN last_sent_at          TIMESTAMP(6) NULL,
    -- The last time Razorpay's list of this payment's refunds was read for this refund, and
    -- what it showed: NONE_OF_OURS or OURS.
    ADD COLUMN verified_at           TIMESTAMP(6) NULL,
    ADD COLUMN verified_result       VARCHAR(16)  NULL,
    -- Razorpay first said this refund failed. It is asked again after an hour before the failure
    -- is believed.
    ADD COLUMN failed_at             TIMESTAMP(6) NULL,
    -- Why the system sent it to a person, when the provider's own words no longer say so: REFUNDED_ANOTHER_WAY
    -- (the payer was, or may have been, refunded outside Mandi; review_ref is that refund's id when the list
    -- shows it), CONTRADICTED_REFUSAL, PAYMENT_GONE or NOT_A_WITHDRAWAL. While it is REFUNDED_ANOTHER_WAY the
    -- refund is never put back in a wallet by anyone: the exits are marking it completed against that refund, or
    -- retrying it. Cleared when the refund goes back to the refund job.
    ADD COLUMN review_cause          VARCHAR(32)  NULL,
    ADD COLUMN review_ref            VARCHAR(200) NULL,
    -- Last time a rejected refund was read and left undecided (the provider did not know its payment and it is
    -- not yet clear whether that is the payment or our keys). The queue reads the longest-unread first, so two
    -- such refunds cannot sit at its head for ever. Not updated_at: that is the clock of the last decision.
    ADD COLUMN settle_held_at        TIMESTAMP(6) NULL,
    ADD COLUMN reversed_at           TIMESTAMP(6) NULL,
    -- The operator who put the money back; null when the system did.
    ADD COLUMN reversed_by           BIGINT       NULL,
    -- A money-moving operator action waiting for a second person above the threshold.
    ADD COLUMN ops_action            VARCHAR(16)  NULL,
    ADD COLUMN ops_action_by         BIGINT       NULL,
    ADD COLUMN ops_action_at         TIMESTAMP(6) NULL,
    -- A refund we put back in a wallet turned up at Razorpay after all: the restaurant was credited
    -- twice. Withdrawals of that outlet stay paused until operations resolves it (late_success_resolved_at).
    ADD COLUMN late_success_at          TIMESTAMP(6) NULL,
    ADD COLUMN late_success_resolved_at TIMESTAMP(6) NULL;

CREATE INDEX ix_refund_status_updated ON refund (status, updated_at);

-- One Razorpay refund completes one refund of ours. Adoption (a lookup that finds "ours" without a receipt,
-- or an operator marking a refund done) checks this too, but two transactions can both check and both write;
-- this is what stops the second. Nullable: a refund not yet sent has none, and MySQL allows many NULLs.
-- V11 held the column with no index, so a duplicate written before this would stop the migration: none is
-- expected (a provider refund id is only ever set from the provider's own answer for one refund).
CREATE UNIQUE INDEX uk_refund_provider_refund ON refund (provider_refund_id);

-- Operations: decide what happens to a refund the provider would not send. Moves money,
-- so it is its own permission (D-046, D-048), not PAYMENT_RECONCILE. Its read side is
-- PAYMENT_INSPECT, which the same roles already hold.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('REFUND_OPERATE', 'Operate stuck refunds', 'INTERNAL',
 'Verify, retry, re-credit or complete a refund the provider did not send, and block or unblock a payment as a refund source',
 NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'REFUND_OPERATE'
 WHERE r.code IN ('OPS_FINANCE', 'OPS_ADMIN');
