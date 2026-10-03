-- Direct ordering: a store that keeps stock does not need to be asked first.
--
-- The request/intent round trip exists to find out whether a supplier has the
-- goods. A meat or vegetable wholesaler with a short list of lines and real
-- inventory already knows, and making a kitchen wait sixty seconds to be told
-- so is a delay that buys nothing.
--
-- Default FALSE, which is exactly today's behaviour: every existing store keeps
-- requiring a request, and nothing about an in-flight request changes. Opting
-- in is a supplier's decision (or an operator's on their behalf) and never a
-- migration's — D-089 splits a backfill from a constraint, and here there is no
-- backfill to do because "off" is the honest value for a store that has not
-- been asked.
alter table supplier_store
    add column direct_orders_enabled boolean not null default false;
