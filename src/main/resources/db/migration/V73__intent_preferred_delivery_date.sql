-- When the buyer would like a request delivered (D-140). Null means immediate, as soon as the supplier can.
-- A preference only: the slot is booked when the order is created.
ALTER TABLE intent ADD COLUMN preferred_delivery_date DATE NULL;
