-- Supplier-funded Costonomy delivery (D-203): a supplier may pay for the rider on orders that reach their free-delivery
-- threshold, up to a cap. Opt-in, because it takes money from the supplier's payout.

ALTER TABLE supplier_delivery_policy
    ADD COLUMN fund_costonomy_delivery TINYINT(1)   NOT NULL DEFAULT 0,
    ADD COLUMN funded_delivery_cap     DECIMAL(19,4) NULL,
    -- Switching it on needs the two numbers it depends on, so a half-set policy cannot charge without a bound.
    ADD CONSTRAINT ck_policy_funded_delivery CHECK (
        fund_costonomy_delivery = 0
        OR (funded_delivery_cap IS NOT NULL AND funded_delivery_cap > 0
            AND free_delivery_threshold IS NOT NULL AND free_delivery_threshold > 0));

-- The part of the courier's fee the supplier pays on this order, fixed at checkout so the restaurant's price and the
-- supplier's charge are the same figures the restaurant was shown. The delivery_fee beside it is what the restaurant pays.
ALTER TABLE supplier_order
    ADD COLUMN delivery_funded_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_supplier_order_funded_delivery CHECK (delivery_funded_amount >= 0);

-- A deduction now has two sources: an approved dispute refund (as before) or a funded delivery. Exactly one of the two
-- ids is set, and a delivery can be charged once.
ALTER TABLE supplier_deduction
    MODIFY COLUMN dispute_refund_id BIGINT NULL,
    ADD COLUMN kind        VARCHAR(24) NOT NULL DEFAULT 'DISPUTE_REFUND',
    ADD COLUMN delivery_id BIGINT      NULL,
    ADD CONSTRAINT uk_supplier_deduction_delivery UNIQUE (delivery_id),
    ADD CONSTRAINT fk_supplier_deduction_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    ADD CONSTRAINT ck_supplier_deduction_source CHECK (
        (kind = 'DISPUTE_REFUND' AND dispute_refund_id IS NOT NULL AND delivery_id IS NULL)
        OR (kind = 'DELIVERY_FUNDING' AND delivery_id IS NOT NULL AND dispute_refund_id IS NULL));
