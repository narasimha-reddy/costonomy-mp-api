-- V49: Delivery slots and recurring subscriptions.
--
-- Enables supplier slot-based dispatch windows (morning, afternoon, evening)
-- with daily capacity ceilings and cut-offs.
-- Enables restaurant recurring replenishment subscriptions (daily, weekdays, weekly)
-- with skip-date management and daily operational manifest support for suppliers.

CREATE TABLE delivery_slot (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id   BIGINT NOT NULL,
    slot_name           VARCHAR(64) NOT NULL,
    start_time          TIME NOT NULL,
    end_time            TIME NOT NULL,
    order_cutoff_time   TIME NOT NULL,
    max_orders_per_day  INT NOT NULL DEFAULT 50,
    is_active           TINYINT(1) NOT NULL DEFAULT 1,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_delivery_slot_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    INDEX idx_delivery_slot_store (supplier_store_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE subscription (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id           BIGINT NOT NULL,
    supplier_store_id   BIGINT NOT NULL,
    canonical_product_id BIGINT NULL,
    supplier_sku_id     BIGINT NOT NULL,
    quantity            DECIMAL(19,4) NOT NULL,
    unit                VARCHAR(32) NOT NULL,
    frequency           VARCHAR(32) NOT NULL DEFAULT 'DAILY',
    preferred_slot_id   BIGINT NULL,
    delivery_mode       VARCHAR(32) NOT NULL DEFAULT 'SUPPLIER_DELIVERY',
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    start_date          DATE NOT NULL,
    end_date            DATE NULL,
    next_delivery_date  DATE NULL,
    notes               VARCHAR(500) NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_subscription_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_subscription_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_subscription_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    CONSTRAINT fk_subscription_slot FOREIGN KEY (preferred_slot_id) REFERENCES delivery_slot (id),
    INDEX idx_subscription_outlet (outlet_id, status),
    INDEX idx_subscription_store (supplier_store_id, status),
    INDEX idx_subscription_next (status, next_delivery_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE subscription_skip_date (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    subscription_id     BIGINT NOT NULL,
    skip_date           DATE NOT NULL,
    reason              VARCHAR(255) NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_subscription_skip_sub FOREIGN KEY (subscription_id) REFERENCES subscription (id) ON DELETE CASCADE,
    CONSTRAINT uk_subscription_skip_date UNIQUE (subscription_id, skip_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE supplier_order
    ADD COLUMN delivery_slot_id BIGINT NULL AFTER delivery_mode,
    ADD COLUMN scheduled_delivery_date DATE NULL AFTER delivery_slot_id,
    ADD COLUMN is_subscription_order TINYINT(1) NOT NULL DEFAULT 0 AFTER scheduled_delivery_date,
    ADD COLUMN subscription_id BIGINT NULL AFTER is_subscription_order,
    ADD CONSTRAINT fk_order_delivery_slot FOREIGN KEY (delivery_slot_id) REFERENCES delivery_slot (id),
    ADD CONSTRAINT fk_order_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id);

CREATE INDEX idx_order_slot_date ON supplier_order (delivery_slot_id, scheduled_delivery_date);

-- Seed default morning, midday, and evening slots for any existing supplier stores
INSERT INTO delivery_slot (supplier_store_id, slot_name, start_time, end_time, order_cutoff_time, max_orders_per_day, is_active)
SELECT id, 'Morning Slot (06:00 - 09:00)', '06:00:00', '09:00:00', '04:00:00', 30, 1 FROM supplier_store;

INSERT INTO delivery_slot (supplier_store_id, slot_name, start_time, end_time, order_cutoff_time, max_orders_per_day, is_active)
SELECT id, 'Midday Slot (11:00 - 14:00)', '11:00:00', '14:00:00', '09:00:00', 40, 1 FROM supplier_store;

INSERT INTO delivery_slot (supplier_store_id, slot_name, start_time, end_time, order_cutoff_time, max_orders_per_day, is_active)
SELECT id, 'Evening Slot (17:00 - 20:00)', '17:00:00', '20:00:00', '15:00:00', 30, 1 FROM supplier_store;
