-- V52 — 'No bill needed' on a wallet payment (D-116).
--
-- A payment that needs a bill (an order or a QuickScan payment from the wallet) shows "Bill pending" in the
-- History until a bill is added. A tea or a small payment may never have one: the user can mark it
-- 'No bill needed', which clears the chip and the count, and can undo it. One row per wallet entry, removed on
-- undo (the audit log keeps who and when for both), and removed in the same transaction when a bill is added.
--
-- Additive only: one new table, nothing existing changes. V45 to V48 stay reserved; V49 to V51 are used.

CREATE TABLE wallet_entry_invoice_waiver (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- One waiver per wallet entry; a second request is answered as already done.
    wallet_transaction_id BIGINT       NOT NULL,
    -- Always the outlet of the wallet that owns the entry, as on wallet_entry_invoice.
    outlet_id             BIGINT       NOT NULL,
    waived_by             BIGINT       NULL,
    waived_at             TIMESTAMP(6) NOT NULL,

    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_wallet_entry_invoice_waiver_entry UNIQUE (wallet_transaction_id),
    CONSTRAINT fk_wallet_entry_invoice_waiver_entry FOREIGN KEY (wallet_transaction_id)
        REFERENCES wallet_transaction (id),
    CONSTRAINT fk_wallet_entry_invoice_waiver_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id)
);
