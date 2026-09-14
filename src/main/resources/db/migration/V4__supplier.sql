-- V4 — Supplier organisations and stores.
--
-- Doc 01 §6, doc 02 §3–4. The organisation is the legal/commercial entity; the
-- store is the operational source. Store-level state is authoritative for
-- availability, price, delivery and credit — an organisation with three stores
-- can be active in one city and offline in another.

CREATE TABLE supplier_organization (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    legal_name      VARCHAR(250) NOT NULL,
    display_name    VARCHAR(200) NOT NULL,
    gstin           VARCHAR(32),
    contact_name    VARCHAR(150),
    contact_phone   VARCHAR(32),
    contact_email   VARCHAR(255),
    -- INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE,
    -- plus SUSPENDED and OFFLINE. Doc 03 §2.
    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
    verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SUBMITTED',
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_supplier_org_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    -- One GSTIN, one supplier. Nullable while unverified; MySQL treats NULLs as
    -- distinct in a unique index, so several unverified suppliers coexist.
    CONSTRAINT uk_supplier_org_gstin UNIQUE (gstin),
    KEY ix_supplier_org_lifecycle (lifecycle_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE supplier_store (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_organization_id BIGINT NOT NULL,
    name            VARCHAR(200) NOT NULL,
    address_line1   VARCHAR(250) NOT NULL,
    address_line2   VARCHAR(250),
    city            VARCHAR(120) NOT NULL,
    state           VARCHAR(120) NOT NULL,
    pincode         VARCHAR(16),
    latitude        DECIMAL(10,7),
    longitude       DECIMAL(10,7),
    google_place_id VARCHAR(255),
    contact_name    VARCHAR(150),
    contact_phone   VARCHAR(32),
    -- Weekly opening hours. JSON because the shape is a schedule, not a scalar,
    -- and nothing queries inside it — serviceability reads it per candidate store.
    operating_hours_json JSON    NULL,
    -- ACTIVE / OFFLINE / SUSPENDED. Separate from the organisation's lifecycle:
    -- a verified supplier can still take one store offline for the afternoon.
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    -- Seconds the supplier has to accept an order. 60 by default, configurable
    -- per store — doc 01 §12 rule 9 and doc 13 make this non-negotiable.
    response_sla_seconds INT     NOT NULL DEFAULT 60,
    -- Minutes from acceptance to ready-for-pickup; feeds the ETA shown at ranking.
    preparation_minutes INT      NOT NULL DEFAULT 60,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_supplier_store_org FOREIGN KEY (supplier_organization_id) REFERENCES supplier_organization (id),
    KEY ix_supplier_store_org_status (supplier_organization_id, status),
    KEY ix_supplier_store_geo (latitude, longitude),
    KEY ix_supplier_store_pincode (pincode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Delivery configuration, one row per store. Doc 01 §20, doc 06 §2, doc 41.
--
-- Its own table rather than columns on supplier_store because delivery policy is
-- edited by a different actor at a different time from store identity, and
-- because own-delivery and Costonomy-delivery settings will keep growing.
CREATE TABLE supplier_delivery_policy (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id BIGINT     NOT NULL,
    own_delivery_enabled TINYINT(1) NOT NULL DEFAULT 0,
    costonomy_delivery_enabled TINYINT(1) NOT NULL DEFAULT 1,
    max_delivery_radius_km DECIMAL(9,4) NULL,
    -- A supplier's own minimum *order value* for free/own delivery. This is not
    -- a Mandi minimum order and not a per-line MOQ — doc 01 §7 rules both out.
    own_delivery_min_order_value DECIMAL(19,4) NULL,
    own_delivery_fee DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- Optional override list; serviceability is primarily geographic (doc 41).
    serviceable_pincodes_json JSON NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_delivery_policy_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT uk_delivery_policy_store UNIQUE (supplier_store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Default credit terms a store is willing to offer. Doc 01 §18.
--
-- Defaults only. An actual agreement with a specific restaurant outlet is a
-- separate, negotiated record (credit_agreement, V9) — the supplier may approve
-- different terms per restaurant, and Mandi never funds or guarantees any of it.
CREATE TABLE supplier_credit_policy (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id BIGINT     NOT NULL,
    credit_enabled  TINYINT(1)   NOT NULL DEFAULT 0,
    default_credit_limit DECIMAL(19,4) NULL,
    default_credit_period_days INT NULL,
    default_grace_period_days INT NOT NULL DEFAULT 0,
    max_single_order_credit DECIMAL(19,4) NULL,
    auto_suspend_enabled TINYINT(1) NOT NULL DEFAULT 1,
    max_overdue_amount DECIMAL(19,4) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_credit_policy_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT uk_credit_policy_store UNIQUE (supplier_store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Verification submissions. Doc 09 §8.
--
-- Append-only: each submission is a row, and a rejection followed by a
-- resubmission keeps both. Verification is an audit trail, not a current-value
-- field — "when did this supplier become verified, on what evidence, reviewed by
-- whom" must stay answerable.
CREATE TABLE supplier_verification (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_organization_id BIGINT NOT NULL,
    verification_type VARCHAR(32) NOT NULL DEFAULT 'GST',
    submitted_by    BIGINT       NULL,
    -- What was submitted, as submitted. Kept verbatim so a later dispute can be
    -- settled against what the supplier actually claimed.
    submitted_data_json JSON     NOT NULL,
    -- The provider's normalised answer, or the reviewer's findings.
    result_json     JSON         NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- 'MANUAL' until a GST verification API is integrated. Doc 09 §8 requires the
    -- source to be recorded either way.
    verification_source VARCHAR(64) NOT NULL DEFAULT 'MANUAL',
    provider_reference VARCHAR(200) NULL,
    evidence_url    VARCHAR(1000) NULL,
    reviewed_by     BIGINT       NULL,
    reviewed_at     TIMESTAMP(6) NULL,
    rejection_reason VARCHAR(500) NULL,
    verified_at     TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_verification_org FOREIGN KEY (supplier_organization_id) REFERENCES supplier_organization (id),
    CONSTRAINT fk_verification_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id),
    CONSTRAINT fk_verification_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    KEY ix_verification_org_status (supplier_organization_id, status, created_at),
    KEY ix_verification_queue (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Membership in a supplier organisation. Mirrors restaurant_user: employment
-- state here, capability in user_role.
CREATE TABLE supplier_user (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_organization_id BIGINT NOT NULL,
    user_id         BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    invited_by      BIGINT       NULL,
    invited_at      TIMESTAMP(6) NULL,
    joined_at       TIMESTAMP(6) NULL,
    removed_at      TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_supplier_user_org FOREIGN KEY (supplier_organization_id) REFERENCES supplier_organization (id),
    CONSTRAINT fk_supplier_user_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_supplier_user_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT uk_supplier_user UNIQUE (supplier_organization_id, user_id),
    KEY ix_supplier_user_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE supplier_user_store (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_user_id BIGINT      NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    is_primary      TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_supplier_user_store_member FOREIGN KEY (supplier_user_id) REFERENCES supplier_user (id),
    CONSTRAINT fk_supplier_user_store_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT uk_supplier_user_store UNIQUE (supplier_user_id, supplier_store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
