# Domain Model & Database — Costonomy MP

## 1. Database

- MySQL 8
- database: `costonomy_mp`
- InnoDB
- UTF-8: `utf8mb4_0900_ai_ci`
- timestamps stored consistently in UTC
- application converts to user/local timezone for presentation

All money must use fixed precision (`DECIMAL`), never floating point.

Recommended money columns: `DECIMAL(19,4)`.
Rates/percentages: `DECIMAL(9,4)`.

## 2. Core bounded domains

1. Identity & access
2. Restaurant/outlet
3. Supplier
4. Catalog
5. Requirements
6. Procurement
7. Orders/fulfillment
8. Payments
9. Credit
10. Delivery
11. Receiving/disputes/ratings
12. Notifications
13. Settlement
14. Audit/operations
15. Analytics

## 3. Required tables

### Identity/access

- `users`
- `otp_verification`
- `refresh_token`
- `device`
- `role`
- `permission`
- `user_role`
- `role_permission`

### Restaurant

- `restaurant`
- `outlet`
- `restaurant_user`
- `restaurant_user_outlet`
- `procurement_policy`

### Supplier

- `supplier_organization`
- `supplier_store`
- `supplier_user`
- `supplier_user_store`
- `supplier_verification`
- `supplier_delivery_policy`
- `supplier_credit_policy`

### Catalog

- `product_category`
- `brand`
- `canonical_product`
- `canonical_product_alias`
- `supplier_sku`
- `supplier_offer`
- `catalog_import`
- `catalog_import_row`

### Procurement

- `requirement`
- `requirement_item`
- `procurement`
- `procurement_item`
- `procurement_supplier_split`

### Orders

- `supplier_order`
- `supplier_order_item`
- `fulfillment`
- `receiving`
- `receiving_item`

### Payments

- `payment`
- `payment_transaction`
- `refund`
- `refund_transaction`
- `payment_webhook_event`

### Credit

- `credit_request`
- `credit_agreement`
- `credit_limit_history`
- `credit_reservation`
- `credit_transaction`
- `credit_invoice`
- `credit_payment`

### Delivery

- `delivery_provider`
- `delivery`
- `delivery_quote`
- `delivery_event`
- `delivery_location`
- `delivery_provider_attempt`

### Trust

- `rating`
- `dispute`
- `dispute_item`
- `dispute_message`
- `dispute_evidence`

### Settlement

- `commission_configuration`
- `commission_calculation`
- `settlement`
- `settlement_adjustment`

### Platform

- `notification`
- `notification_preference`
- `audit_log`
- `idempotency_record`
- `outbox_event`
- `app_config`
- `analytics_event`

## 4. Entity rules

### Restaurant

Owns one or more outlets.

### Outlet

The procurement boundary.

All restaurant purchasing, receiving and procurement policies are evaluated against an outlet.

### Supplier Organization

The legal/commercial supplier entity.

### Supplier Store

The operational purchasing source. Store-specific commercial state is authoritative for availability, price, delivery and credit rules.

### Canonical Product

Platform-owned comparable product identity.

### Supplier SKU

Supplier-owned commercial SKU mapped to a canonical product.

### Supplier Offer

Current purchasable offer for a supplier SKU/store combination. Historical commercial values must not be overwritten when they are referenced by a transaction.

### Requirement

Restaurant demand that may survive supplier failure/partial fulfillment.

### Procurement

A restaurant decision/workflow to source requirement items.

### Supplier Order

Supplier-specific commercial order created from a procurement.

One procurement may produce multiple supplier orders.

### Delivery

A fulfillment transport request. It must not be embedded as fields directly on supplier order in a way that prevents future multiple-provider/reassignment support.

## 5. Historical integrity

Transaction tables must snapshot commercial values required for reconstruction:

- SKU
- canonical product reference
- quantity
- unit price
- GST
- discounts/adjustments
- delivery fee
- commission
- supplier/store
- outlet
- payment method

Never reconstruct historical financial truth from the current catalog.

## 6. State columns

Every stateful aggregate has:

- `status`
- `version`
- `created_at`
- `updated_at`

Optimistic locking is required for concurrency-sensitive aggregates.

## 7. Critical indexes

At minimum:

- users(phone)
- refresh_token(user_id, expires_at)
- restaurant_user(user_id, restaurant_id)
- restaurant_user_outlet(user_id, outlet_id)
- supplier_user(user_id, supplier_id)
- supplier_user_store(user_id, store_id)
- supplier_sku(store_id, status)
- supplier_sku(canonical_product_id, status)
- supplier_offer(store_id, canonical_product_id, status)
- requirement(outlet_id, status, created_at)
- requirement_item(requirement_id, status)
- procurement(outlet_id, status, created_at)
- procurement_item(procurement_id, requirement_item_id)
- supplier_order(supplier_store_id, status, created_at)
- supplier_order(status, acceptance_deadline)
- supplier_order_item(supplier_order_id, status)
- payment(supplier_order_id, status)
- payment_transaction(provider_transaction_id)
- credit_agreement(supplier_store_id, outlet_id, status)
- credit_reservation(credit_agreement_id, status)
- credit_transaction(credit_agreement_id, created_at)
- delivery(status, created_at)
- delivery_event(delivery_id, created_at)
- delivery_location(delivery_id, recorded_at)
- notification(user_id, status, created_at)
- audit_log(entity_type, entity_id, created_at)
- idempotency_record(actor_id, operation, idempotency_key)

Add geospatial indexes/derived search indexes only when supported by the chosen implementation.

## 8. DDL convention

Use Flyway.

Suggested migrations:

```text
V1__identity_access.sql
V2__restaurant_outlet.sql
V3__supplier.sql
V4__catalog.sql
V5__requirements_procurement.sql
V6__orders_fulfillment.sql
V7__payments.sql
V8__credit.sql
V9__delivery.sql
V10__receiving_disputes_ratings.sql
V11__settlement_commission.sql
V12__notifications_audit_analytics.sql
V13__indexes_constraints.sql
V14__seed_reference_data.sql
```

## 9. Representative DDL

```sql
CREATE DATABASE IF NOT EXISTS costonomy_mp
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(32) NOT NULL,
    name VARCHAR(150),
    email VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_users_phone (phone)
) ENGINE=InnoDB;

CREATE TABLE restaurant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    legal_name VARCHAR(250),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE outlet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    address_line1 VARCHAR(250) NOT NULL,
    address_line2 VARCHAR(250),
    city VARCHAR(120) NOT NULL,
    state VARCHAR(120) NOT NULL,
    pincode VARCHAR(16),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_outlet_restaurant
      FOREIGN KEY (restaurant_id) REFERENCES restaurant(id),
    KEY ix_outlet_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB;

CREATE TABLE supplier_organization (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    legal_name VARCHAR(250) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    gstin VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_supplier_gstin (gstin)
) ENGINE=InnoDB;

CREATE TABLE supplier_store (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_organization_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    address_line1 VARCHAR(250) NOT NULL,
    city VARCHAR(120) NOT NULL,
    state VARCHAR(120) NOT NULL,
    pincode VARCHAR(16),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    status VARCHAR(32) NOT NULL,
    response_sla_seconds INT NOT NULL DEFAULT 60,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_store_supplier
      FOREIGN KEY (supplier_organization_id) REFERENCES supplier_organization(id),
    KEY ix_store_supplier_status (supplier_organization_id, status)
) ENGINE=InnoDB;

CREATE TABLE canonical_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT,
    name VARCHAR(250) NOT NULL,
    normalized_name VARCHAR(250) NOT NULL,
    description TEXT,
    base_unit VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    image_url VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY ix_canonical_product_normalized_name (normalized_name)
) ENGINE=InnoDB;

CREATE TABLE supplier_sku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id BIGINT NOT NULL,
    canonical_product_id BIGINT NOT NULL,
    sku_code VARCHAR(120),
    brand_name VARCHAR(200),
    pack_value DECIMAL(19,4),
    pack_unit VARCHAR(32),
    price DECIMAL(19,4) NOT NULL,
    gst_rate DECIMAL(9,4) NOT NULL,
    availability VARCHAR(32) NOT NULL,
    image_url VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sku_store
      FOREIGN KEY (supplier_store_id) REFERENCES supplier_store(id),
    CONSTRAINT fk_sku_product
      FOREIGN KEY (canonical_product_id) REFERENCES canonical_product(id),
    KEY ix_sku_store_status (supplier_store_id, status),
    KEY ix_sku_product_status (canonical_product_id, status)
) ENGINE=InnoDB;

CREATE TABLE requirement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_requirement_outlet
      FOREIGN KEY (outlet_id) REFERENCES outlet(id),
    KEY ix_requirement_outlet_status_created
      (outlet_id, status, created_at)
) ENGINE=InnoDB;

CREATE TABLE requirement_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id BIGINT NOT NULL,
    canonical_product_id BIGINT NOT NULL,
    requested_quantity DECIMAL(19,4) NOT NULL,
    fulfilled_quantity DECIMAL(19,4) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_requirement_item_requirement
      FOREIGN KEY (requirement_id) REFERENCES requirement(id),
    CONSTRAINT fk_requirement_item_product
      FOREIGN KEY (canonical_product_id) REFERENCES canonical_product(id),
    KEY ix_requirement_item_requirement_status
      (requirement_id, status)
) ENGINE=InnoDB;

CREATE TABLE supplier_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    procurement_id BIGINT NOT NULL,
    supplier_store_id BIGINT NOT NULL,
    outlet_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    acceptance_deadline TIMESTAMP(6),
    subtotal DECIMAL(19,4) NOT NULL DEFAULT 0,
    gst_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    delivery_fee DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_store
      FOREIGN KEY (supplier_store_id) REFERENCES supplier_store(id),
    CONSTRAINT fk_order_outlet
      FOREIGN KEY (outlet_id) REFERENCES outlet(id),
    KEY ix_order_store_status_deadline
      (supplier_store_id, status, acceptance_deadline),
    KEY ix_order_status_created (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_payment_id VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    authorized_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    captured_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    refunded_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payment_order
      FOREIGN KEY (supplier_order_id) REFERENCES supplier_order(id),
    KEY ix_payment_provider_payment (provider, provider_payment_id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(150) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT,
    response_body JSON,
    state VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_idempotency_actor_operation_key
      (actor_id, operation, idempotency_key)
) ENGINE=InnoDB;
```

The implementation must expand this representative DDL into complete DDL for every table listed above, including foreign keys, unique constraints, indexes, check constraints where appropriate, audit columns, versioning and migration-safe ordering.

## 10. Outbox

All important domain events should be persisted transactionally in `outbox_event` before asynchronous publication.

Consumers must be idempotent.

## 11. Views

Create operational views for:

- active supplier offers
- restaurant requirement fulfillment
- open supplier acceptance queue
- credit exposure
- delivery operational status
- settlement payable
- marketplace funnel

Views are projections only; transactional truth remains in base tables.

## 12. Data retention

Financial, audit, dispute and order records must remain reconstructable.

Never hard-delete transactional records.

Use status/archive mechanisms for user-facing removal requirements.
