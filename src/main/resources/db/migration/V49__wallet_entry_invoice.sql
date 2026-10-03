-- V49 — The shop's bill, attached to a wallet payment (D-113).
--
-- A restaurant that paid a shop from the wallet (an order, or a QuickScan payment) can keep the shop's
-- bill with that payment: the photo or PDF pages, and what we read from them. One bill per wallet entry.
--
-- Additive only: two new tables, no existing row or column changes.
--
-- V45 to V48 are reserved for the renumbering of the payments migrations, so this is V49.

CREATE TABLE wallet_entry_invoice (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- One bill per wallet entry; a second upload is refused, not merged.
    wallet_transaction_id BIGINT       NOT NULL,
    -- Denormalised from the wallet so the per-day cap and the page links are checked against the
    -- outlet without a join. Always the outlet of the wallet that owns the entry.
    outlet_id             BIGINT       NOT NULL,

    -- READING: stored, not yet read (or read failed and will be tried again).
    -- READ: the reading below is set. UNREADABLE: gave up after the attempts allowed.
    -- No CHECK constraint (D-009): a VARCHAR, like the other statuses.
    status                VARCHAR(16)  NOT NULL DEFAULT 'READING',
    uploaded_by           BIGINT       NULL,

    -- What was read, pulled out of reading_json for the summary on the transaction page.
    vendor_name           VARCHAR(500) NULL,
    invoice_number        VARCHAR(500) NULL,
    -- The date as printed ("04/09/26"), never parsed: bills use every format there is.
    invoice_date_text     VARCHAR(500) NULL,
    subtotal              DECIMAL(19,4) NULL,
    tax                   DECIMAL(19,4) NULL,
    delivery              DECIMAL(19,4) NULL,
    total                 DECIMAL(19,4) NULL,
    currency              VARCHAR(16)  NULL,
    -- Only the plain fields we map (D-113); never the reader's whole answer.
    reading_json          JSON         NULL,

    -- The last reason a reading failed, in our own words; never the reader's.
    error_text            VARCHAR(500) NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    last_attempt_at       TIMESTAMP(6) NULL,

    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_wallet_entry_invoice_entry UNIQUE (wallet_transaction_id),
    CONSTRAINT fk_wallet_entry_invoice_entry FOREIGN KEY (wallet_transaction_id) REFERENCES wallet_transaction (id),
    CONSTRAINT fk_wallet_entry_invoice_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id)
);

-- The per-outlet daily cap, and the retry job's look at what is still being read.
CREATE INDEX ix_wallet_entry_invoice_outlet_created ON wallet_entry_invoice (outlet_id, created_at);
CREATE INDEX ix_wallet_entry_invoice_status ON wallet_entry_invoice (status, last_attempt_at);

CREATE TABLE wallet_entry_invoice_page (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    invoice_id    BIGINT       NOT NULL,
    page_no       INT          NOT NULL,
    -- invoices/<outletId>/<yyyy-MM>/<uuid>-p<page>.<ext>: never the customer's file name.
    storage_key   VARCHAR(255) NOT NULL,
    content_type  VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_wallet_entry_invoice_page UNIQUE (invoice_id, page_no),
    CONSTRAINT fk_wallet_entry_invoice_page_invoice FOREIGN KEY (invoice_id)
        REFERENCES wallet_entry_invoice (id) ON DELETE CASCADE
);
