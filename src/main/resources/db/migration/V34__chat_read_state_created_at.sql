-- `chat_read_state` extends BaseEntity, which maps `created_at`, and V33 gave
-- the table only `updated_at`. Caught by `ddl-auto=validate` at startup, which
-- is what that setting is for: the mapping and the schema disagreed and the app
-- refused to run rather than failing later on a write.
--
-- A separate migration because V33 has already applied. Editing an applied
-- migration makes a database that was built yesterday and one built today
-- disagree with nothing to say which is right.
ALTER TABLE chat_read_state
    ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER side;
