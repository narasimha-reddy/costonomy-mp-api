-- V3 — Restaurants and outlets.
--
-- Doc 02 §3–4. The outlet is the procurement boundary: all purchasing, receiving
-- and policy evaluation happens against an outlet, never against the restaurant
-- as a whole (doc 01 §4).

CREATE TABLE restaurant (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    legal_name      VARCHAR(250),
    gstin           VARCHAR(32),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_restaurant_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    KEY ix_restaurant_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The procurement boundary.
--
-- Address fields are denormalised here rather than in a shared address table:
-- an outlet's delivery address is a commercial fact that supplier serviceability
-- and delivery quotes are computed against, and doc 02 §5 requires transaction
-- records to stay reconstructable. A shared, mutable address row would silently
-- rewrite where a past order was delivered.
CREATE TABLE outlet (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_id   BIGINT       NOT NULL,
    name            VARCHAR(200) NOT NULL,
    address_line1   VARCHAR(250) NOT NULL,
    address_line2   VARCHAR(250),
    landmark        VARCHAR(250),
    city            VARCHAR(120) NOT NULL,
    state           VARCHAR(120) NOT NULL,
    pincode         VARCHAR(16),
    -- DECIMAL(10,7) gives ~1cm precision and, unlike a float, compares exactly.
    latitude        DECIMAL(10,7),
    longitude       DECIMAL(10,7),
    google_place_id VARCHAR(255),
    formatted_address VARCHAR(500),
    contact_name    VARCHAR(150),
    contact_phone   VARCHAR(32),
    delivery_instructions VARCHAR(1000),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_outlet_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    KEY ix_outlet_restaurant_status (restaurant_id, status),
    KEY ix_outlet_pincode (pincode),
    KEY ix_outlet_geo (latitude, longitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Membership in a restaurant organisation.
--
-- Membership and capability are separate concerns and are stored separately.
-- This table answers "who belongs to this restaurant, and in what employment
-- state" — including a user invited by phone who has never signed in. What a
-- member may *do* is decided entirely by `user_role` (V1). A member with no role
-- grant can see nothing; see AccessControlService.
CREATE TABLE restaurant_user (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_id   BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    invited_by      BIGINT       NULL,
    invited_at      TIMESTAMP(6) NULL,
    joined_at       TIMESTAMP(6) NULL,
    removed_at      TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_restaurant_user_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    CONSTRAINT fk_restaurant_user_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_restaurant_user_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT uk_restaurant_user UNIQUE (restaurant_id, user_id),
    KEY ix_restaurant_user_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Which outlets a member is assigned to.
--
-- Only meaningful for members whose roles are granted at OUTLET scope. A member
-- holding a role at RESTAURANT scope reaches every outlet of that restaurant
-- without a row here — the scope hierarchy handles it.
CREATE TABLE restaurant_user_outlet (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_user_id BIGINT    NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    is_primary      TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rest_user_outlet_member FOREIGN KEY (restaurant_user_id) REFERENCES restaurant_user (id),
    CONSTRAINT fk_rest_user_outlet_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT uk_restaurant_user_outlet UNIQUE (restaurant_user_id, outlet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Approval policy. Doc 01 §5, doc 03 §15, doc 28.
--
-- A rule that decides whether a procurement needs approval before it is placed,
-- and by whom. Conditions are JSON because the axes are open-ended (order value,
-- supplier, category, payment method, requester role, and combinations) and
-- columns-per-axis would need a migration for each new one.
--
-- `policy_version` and `effective_from` exist because an approval decision is a
-- financial control: reconstructing why a ₹40,000 order needed the owner's
-- approval six months ago requires the rule *as it was then*. Rules are never
-- edited in place — a change inserts a new version.
CREATE TABLE procurement_policy (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    restaurant_id   BIGINT       NOT NULL,
    -- NULL means the policy applies to every outlet of the restaurant.
    outlet_id       BIGINT       NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    conditions_json JSON         NOT NULL,
    -- Role codes whose holders may approve, e.g. ["OWNER","ADMIN"].
    approver_roles_json JSON     NOT NULL,
    priority        INT          NOT NULL DEFAULT 100,
    policy_version  INT          NOT NULL DEFAULT 1,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    effective_from  TIMESTAMP(6) NOT NULL,
    effective_to    TIMESTAMP(6) NULL,
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_policy_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    CONSTRAINT fk_policy_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    KEY ix_policy_lookup (restaurant_id, outlet_id, status, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
