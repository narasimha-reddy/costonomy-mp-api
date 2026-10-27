-- D-208: Marketplace keeps no copies of costonomy-common-api's data. Businesses, outlets, stores, people,
-- devices and roles are common's; the business tables keep their ids (common's ids, unchanged) without
-- foreign keys to copies that no longer exist. Marketplace's own outlet and store fields (preparation time,
-- response time, direct orders, chat, online, delivery instructions) are common's MARKETPLACE outlet settings
-- (common D-85), and supplier verification is common's verification queue.
--
-- Marketplace was never live (V89), so nothing here is copied to common: the rows go with the tables.

-- 1. Foreign keys from Marketplace's own tables onto the copies.
ALTER TABLE analytics_event DROP FOREIGN KEY fk_analytics_event_user;
ALTER TABLE catalog_import DROP FOREIGN KEY fk_import_store, DROP FOREIGN KEY fk_import_uploaded_by;
ALTER TABLE commission_calculation DROP FOREIGN KEY fk_commission_calc_store;
ALTER TABLE commission_configuration DROP FOREIGN KEY fk_commission_config_by;
ALTER TABLE credit_agreement DROP FOREIGN KEY fk_credit_agreement_org, DROP FOREIGN KEY fk_credit_agreement_outlet, DROP FOREIGN KEY fk_credit_agreement_restaurant, DROP FOREIGN KEY fk_credit_agreement_store;
ALTER TABLE credit_digest_log DROP FOREIGN KEY fk_credit_digest_log_store;
ALTER TABLE credit_invoice DROP FOREIGN KEY fk_credit_invoice_outlet, DROP FOREIGN KEY fk_credit_invoice_store;
ALTER TABLE credit_invoice_note DROP FOREIGN KEY fk_credit_invoice_note_by, DROP FOREIGN KEY fk_credit_invoice_note_outlet, DROP FOREIGN KEY fk_credit_invoice_note_store;
ALTER TABLE credit_limit_history DROP FOREIGN KEY fk_credit_history_by;
ALTER TABLE credit_payment DROP FOREIGN KEY fk_credit_payment_by;
ALTER TABLE credit_payment_attempt DROP FOREIGN KEY fk_credit_payment_attempt_created_by, DROP FOREIGN KEY fk_credit_payment_attempt_outlet;
ALTER TABLE credit_payment_attempt_invoice DROP FOREIGN KEY fk_cpai_store;
ALTER TABLE credit_payment_claim DROP FOREIGN KEY fk_credit_claim_outlet, DROP FOREIGN KEY fk_credit_claim_store;
ALTER TABLE credit_payment_reversal DROP FOREIGN KEY fk_credit_reversal_by;
ALTER TABLE credit_refund_due DROP FOREIGN KEY fk_credit_refund_by, DROP FOREIGN KEY fk_credit_refund_outlet, DROP FOREIGN KEY fk_credit_refund_store;
ALTER TABLE credit_repayment DROP FOREIGN KEY fk_credit_repayment_outlet, DROP FOREIGN KEY fk_credit_repayment_store;
ALTER TABLE credit_repayment_payout DROP FOREIGN KEY fk_credit_repayment_payout_store;
ALTER TABLE credit_request DROP FOREIGN KEY fk_credit_request_by, DROP FOREIGN KEY fk_credit_request_outlet, DROP FOREIGN KEY fk_credit_request_store;
ALTER TABLE delivery DROP FOREIGN KEY fk_delivery_outlet, DROP FOREIGN KEY fk_delivery_store;
ALTER TABLE delivery_fee_quote DROP FOREIGN KEY fk_delivery_fee_quote_outlet, DROP FOREIGN KEY fk_delivery_fee_quote_store;
ALTER TABLE delivery_slot DROP FOREIGN KEY fk_delivery_slot_store;
ALTER TABLE dispute DROP FOREIGN KEY fk_dispute_outlet, DROP FOREIGN KEY fk_dispute_raised_by, DROP FOREIGN KEY fk_dispute_store;
ALTER TABLE dispute_evidence DROP FOREIGN KEY fk_dispute_evidence_by;
ALTER TABLE dispute_message DROP FOREIGN KEY fk_dispute_message_author;
ALTER TABLE food_delivery DROP FOREIGN KEY fk_food_delivery_outlet, DROP FOREIGN KEY fk_food_delivery_outlet_restaurant_scope, DROP FOREIGN KEY fk_food_delivery_restaurant;
ALTER TABLE intent DROP FOREIGN KEY fk_intent_outlet, DROP FOREIGN KEY fk_intent_store, DROP FOREIGN KEY fk_intent_user;
ALTER TABLE intent_acceptance DROP FOREIGN KEY fk_intent_acceptance_store, DROP FOREIGN KEY fk_intent_acceptance_user;
ALTER TABLE notification DROP FOREIGN KEY fk_notification_user;
ALTER TABLE notification_delivery DROP FOREIGN KEY fk_notification_delivery_device;
ALTER TABLE notification_preference DROP FOREIGN KEY fk_notification_preference_user;
ALTER TABLE order_adjustment DROP FOREIGN KEY fk_order_adjustment_created_by;
ALTER TABLE payment DROP FOREIGN KEY fk_payment_outlet;
ALTER TABLE procurement DROP FOREIGN KEY fk_procurement_created_by, DROP FOREIGN KEY fk_procurement_outlet;
ALTER TABLE procurement_item DROP FOREIGN KEY fk_procurement_item_store;
ALTER TABLE procurement_policy DROP FOREIGN KEY fk_policy_outlet, DROP FOREIGN KEY fk_policy_restaurant;
ALTER TABLE quickscan_payment DROP FOREIGN KEY fk_quickscan_payment_created_by, DROP FOREIGN KEY fk_quickscan_payment_outlet;
ALTER TABLE rating DROP FOREIGN KEY fk_rating_by, DROP FOREIGN KEY fk_rating_outlet, DROP FOREIGN KEY fk_rating_store;
ALTER TABLE realtime_ticket DROP FOREIGN KEY fk_realtime_ticket_user;
ALTER TABLE receiving DROP FOREIGN KEY fk_receiving_by, DROP FOREIGN KEY fk_receiving_outlet, DROP FOREIGN KEY fk_receiving_store;
ALTER TABLE refund DROP FOREIGN KEY fk_refund_requested_by;
ALTER TABLE requirement DROP FOREIGN KEY fk_requirement_created_by, DROP FOREIGN KEY fk_requirement_outlet;
ALTER TABLE settlement DROP FOREIGN KEY fk_settlement_approved_by, DROP FOREIGN KEY fk_settlement_org, DROP FOREIGN KEY fk_settlement_store;
ALTER TABLE settlement_adjustment DROP FOREIGN KEY fk_settlement_adjustment_by;
ALTER TABLE subscription DROP FOREIGN KEY fk_subscription_outlet, DROP FOREIGN KEY fk_subscription_store;
ALTER TABLE supplier_credit_policy DROP FOREIGN KEY fk_credit_policy_store;
ALTER TABLE supplier_delivery_policy DROP FOREIGN KEY fk_delivery_policy_store;
ALTER TABLE supplier_offer DROP FOREIGN KEY fk_offer_store;
ALTER TABLE supplier_order DROP FOREIGN KEY fk_supplier_order_outlet, DROP FOREIGN KEY fk_supplier_order_store;
ALTER TABLE supplier_sku DROP FOREIGN KEY fk_sku_store;
ALTER TABLE tax_invoice DROP FOREIGN KEY fk_tax_invoice_outlet, DROP FOREIGN KEY fk_tax_invoice_store;
ALTER TABLE wallet DROP FOREIGN KEY fk_wallet_outlet;
ALTER TABLE wallet_entry_invoice DROP FOREIGN KEY fk_wallet_entry_invoice_outlet;
ALTER TABLE wallet_entry_invoice_waiver DROP FOREIGN KEY fk_wallet_entry_invoice_waiver_outlet;
ALTER TABLE wallet_top_up DROP FOREIGN KEY fk_wallet_top_up_created_by, DROP FOREIGN KEY fk_wallet_top_up_outlet;

-- 2. The copies, children first.
DROP TABLE IF EXISTS restaurant_user_outlet;
DROP TABLE IF EXISTS restaurant_user;
DROP TABLE IF EXISTS supplier_user_store;
DROP TABLE IF EXISTS supplier_user;
DROP TABLE IF EXISTS supplier_verification;
DROP TABLE IF EXISTS user_role;
DROP TABLE IF EXISTS role_permission;
DROP TABLE IF EXISTS role;
DROP TABLE IF EXISTS permission;
DROP TABLE IF EXISTS device;
DROP TABLE IF EXISTS outlet;
DROP TABLE IF EXISTS restaurant;
DROP TABLE IF EXISTS supplier_store;
DROP TABLE IF EXISTS supplier_organization;
DROP TABLE IF EXISTS users;
