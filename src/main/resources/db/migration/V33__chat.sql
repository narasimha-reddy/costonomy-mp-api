-- Chat between an outlet and a supplier store. D-095.
--
-- One thread per pair, not per order. The alternative was a thread on every
-- order, which gives perfect context and a kitchen with nine threads to the same
-- supplier and nowhere to ask a general question. Orders and requests are shared
-- *into* the thread instead, as links — which is also why an attachment is a
-- typed reference rather than a pasted URL nobody can validate.
--
-- Deliberately not a general messaging system: both ends are organisations, the
-- pair is unique, and a thread can only exist where the two have already traded.

CREATE TABLE chat_thread (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id         BIGINT       NOT NULL,
    supplier_store_id BIGINT       NOT NULL,
    -- Denormalised for the inbox. Every list of threads wants the last line and
    -- when it landed, and reading it from chat_message per row is the query that
    -- makes an inbox slow the moment it is useful.
    last_message_at      TIMESTAMP(6) NULL,
    last_message_preview VARCHAR(200) NULL,
    last_message_side    VARCHAR(16)  NULL,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    version           BIGINT       NOT NULL DEFAULT 0,
    -- The pair is the thread. Two rows for one pair would split a conversation
    -- in half with no way to tell which half anybody is reading.
    CONSTRAINT uk_chat_thread_pair UNIQUE (outlet_id, supplier_store_id),
    KEY ix_chat_thread_outlet (outlet_id, last_message_at),
    KEY ix_chat_thread_store (supplier_store_id, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    chat_thread_id BIGINT        NOT NULL,
    -- Which side, and which person on it. The side decides what a reader sees;
    -- the user is who to hold to it.
    sender_side    VARCHAR(16)   NOT NULL,
    sender_user_id BIGINT        NOT NULL,
    body           VARCHAR(2000) NULL,
    -- A shared request or order. Typed rather than a URL: the server can then
    -- check the thing actually belongs to this pair before showing a link to it.
    attachment_type      VARCHAR(32) NULL,
    attachment_id        BIGINT      NULL,
    attachment_reference VARCHAR(64) NULL,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),
    version        BIGINT        NOT NULL DEFAULT 0,
    KEY ix_chat_message_thread (chat_thread_id, id),
    CONSTRAINT fk_chat_message_thread FOREIGN KEY (chat_thread_id)
        REFERENCES chat_thread (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- How far each side has read. Per side rather than per user: a store is answered
-- by whoever is on the counter, and three people each carrying their own unread
-- badge for the same conversation is three people ignoring it.
CREATE TABLE chat_read_state (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    chat_thread_id       BIGINT       NOT NULL,
    side                 VARCHAR(16)  NOT NULL,
    last_read_message_id BIGINT       NOT NULL DEFAULT 0,
    updated_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                         ON UPDATE CURRENT_TIMESTAMP(6),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_chat_read_state UNIQUE (chat_thread_id, side),
    CONSTRAINT fk_chat_read_state_thread FOREIGN KEY (chat_thread_id)
        REFERENCES chat_thread (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Both ends can be switched off, and both default on.
--
-- On by default because the feature is there to let two parties sort out a
-- delivery between themselves, and off-by-default would mean it never got used.
-- Switching it off is an operations decision: the party whose account is
-- disabled is told to contact support rather than left with a control that
-- silently does nothing.
ALTER TABLE outlet
    ADD COLUMN chat_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE supplier_store
    ADD COLUMN chat_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Reading and sending are separate, so support can be given the whole read
-- surface and none of the writes — D-046, the same split every other pair of
-- permissions here has.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('CHAT_VIEW', 'Read chat', 'RESTAURANT',
 'Read conversations with suppliers this outlet trades with', NOW(6), NOW(6)),
('CHAT_SEND', 'Send chat messages', 'RESTAURANT',
 'Write in a conversation with a supplier', NOW(6), NOW(6)),
('CHAT_VIEW_SUPPLIER', 'Read chat', 'SUPPLIER',
 'Read conversations with restaurants this store trades with', NOW(6), NOW(6)),
('CHAT_SEND_SUPPLIER', 'Send chat messages', 'SUPPLIER',
 'Write in a conversation with a restaurant', NOW(6), NOW(6));

-- Everyone who deals with an order can talk about one. Finance staff read but
-- do not write: they are reconciling, not arranging a delivery.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'CHAT_VIEW'
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN', 'REST_PURCHASE_MANAGER',
                  'REST_STORE_MANAGER', 'REST_PROCUREMENT_STAFF',
                  'REST_RECEIVING_STAFF', 'REST_FINANCE_STAFF');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'CHAT_SEND'
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN', 'REST_PURCHASE_MANAGER',
                  'REST_STORE_MANAGER', 'REST_PROCUREMENT_STAFF',
                  'REST_RECEIVING_STAFF');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'CHAT_VIEW_SUPPLIER'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN', 'SUP_STORE_MANAGER',
                  'SUP_SALESPERSON', 'SUP_OPERATIONS_STAFF', 'SUP_FINANCE_STAFF');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'CHAT_SEND_SUPPLIER'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN', 'SUP_STORE_MANAGER',
                  'SUP_SALESPERSON', 'SUP_OPERATIONS_STAFF');
