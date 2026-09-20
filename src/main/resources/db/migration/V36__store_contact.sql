-- A store needs its own contact. D-097.
--
-- The contact on `supplier_organization` is whoever runs the business. An order
-- is filled by a branch, and the person to ring about it is whoever is on that
-- counter — which on a supplier with three stores is three different people.
-- The columns have existed since V3 and were optional; they are required from
-- here on, for new stores and for any store somebody edits.
--
-- **Backfilled, not constrained.** Per D-089 a backfill and a constraint are
-- separate decisions, and this one stops at the backfill on purpose: a store
-- whose organisation has no contact either has no truthful value to write, and
-- inventing one would put a name and a number in front of a restaurant that
-- nobody can answer. Those rows stay empty until their supplier fills them in,
-- which the app now requires before it will save the store.

UPDATE supplier_store s
  JOIN supplier_organization o ON o.id = s.supplier_organization_id
   SET s.contact_name = o.contact_name
 WHERE (s.contact_name IS NULL OR s.contact_name = '')
   AND o.contact_name IS NOT NULL AND o.contact_name <> '';

UPDATE supplier_store s
  JOIN supplier_organization o ON o.id = s.supplier_organization_id
   SET s.contact_phone = o.contact_phone
 WHERE (s.contact_phone IS NULL OR s.contact_phone = '')
   AND o.contact_phone IS NOT NULL AND o.contact_phone <> '';
