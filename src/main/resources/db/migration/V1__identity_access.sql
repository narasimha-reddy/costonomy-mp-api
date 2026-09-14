-- V1 — Identity and access.
--
-- Doc 02 §3 (Identity/access) and §8. Conventions applied here and in every
-- later migration:
--   * InnoDB, utf8mb4_0900_ai_ci               (doc 02 §1)
--   * TIMESTAMP(6), values stored in UTC        (doc 02 §1)
--   * DECIMAL for money/rates, never float      (doc 02 §1)
--   * every stateful aggregate carries status, version, created_at, updated_at
--                                                (doc 02 §6)
--   * no hard deletes of transactional data      (doc 02 §12)

-- ── Users ────────────────────────────────────────────────────────────────
-- One user row per person. A person may be a restaurant user, a supplier user,
-- an internal operator, or several of those at once — membership lives in the
-- per-domain tables added in V2/V3, not here. That is what lets one phone number
-- sign in and be routed to the right experience by the server (§23A.30).
CREATE TABLE users (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone           VARCHAR(32)  NOT NULL,
    name            VARCHAR(150),
    email           VARCHAR(255),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    phone_verified_at TIMESTAMP(6) NULL,
    last_login_at   TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- E.164, so one number has exactly one row regardless of how it was typed.
    -- Normalisation happens in the service before any lookup or insert.
    CONSTRAINT uk_users_phone UNIQUE (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── OTP ──────────────────────────────────────────────────────────────────
-- Doc 09 §1: expiry, attempt limit, resend cooldown, brute-force protection.
--
-- The OTP itself is stored as a **hash**, never in plain text. A database dump
-- or an over-broad SELECT must not hand someone a live credential, and doc 09
-- §16 forbids OTPs reaching logs for the same reason.
CREATE TABLE otp_verification (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone           VARCHAR(32)  NOT NULL,
    purpose         VARCHAR(32)  NOT NULL,
    otp_hash        VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    verified_at     TIMESTAMP(6) NULL,
    -- Which adapter issued it (MSG91 / MOCK) and the provider's reference, so a
    -- delivery failure can be traced back without re-reading the code itself.
    provider        VARCHAR(32)  NOT NULL,
    provider_reference VARCHAR(200),
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- Finds the live challenge for a phone, and backs the resend-cooldown check.
    KEY ix_otp_phone_purpose_created (phone, purpose, created_at),
    KEY ix_otp_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Refresh tokens ───────────────────────────────────────────────────────
-- Doc 09 §1 requires refresh token rotation and revocation. Rotation means each
-- use issues a new token and retires the old one, so a stolen token is usable at
-- most once; `replaced_by_id` records the chain so a replay of a retired token
-- is detectable rather than merely rejected.
--
-- Stored as a hash, for the same reason as the OTP above.
CREATE TABLE refresh_token (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    revoked_at      TIMESTAMP(6) NULL,
    revoked_reason  VARCHAR(64)  NULL,
    replaced_by_id  BIGINT       NULL,
    device_id       BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id),
    KEY ix_refresh_token_user_expires (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Devices ──────────────────────────────────────────────────────────────
-- Push targets. Doc 02 §3.
CREATE TABLE device (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    platform        VARCHAR(16)  NOT NULL,
    push_token      VARCHAR(512) NULL,
    app_version     VARCHAR(32),
    device_model    VARCHAR(120),
    os_version      VARCHAR(32),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    last_seen_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_device_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- A push token identifies one app install. If it reappears under a different
    -- user (shared handset, reinstall) the old row is reassigned, not duplicated,
    -- or the previous user keeps receiving this user's notifications.
    -- 512 bytes exceeds the InnoDB index limit, so the prefix is indexed.
    KEY ix_device_push_token (push_token(190)),
    KEY ix_device_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE refresh_token
    ADD CONSTRAINT fk_refresh_token_device FOREIGN KEY (device_id) REFERENCES device (id);

-- ── Roles and permissions ────────────────────────────────────────────────
-- Doc 03 §14. The model is Role → Permission → Policy → Approval; this migration
-- covers Role → Permission. Policies and approvals arrive with procurement (V5).
--
-- `scope` records which world a role belongs to (RESTAURANT / SUPPLIER /
-- INTERNAL) so a restaurant role can never be granted on a supplier store.
CREATE TABLE role (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    scope           VARCHAR(32)  NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permission (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    scope           VARCHAR(32)  NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_permission_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
    role_id         BIGINT       NOT NULL,
    permission_id   BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Grants a role to a user, **within a scope**.
--
-- `scope_type` + `scope_id` name the thing the grant applies to — an outlet, a
-- supplier store, a restaurant, or the platform itself for internal roles. This
-- is what doc 03 §16 turns into an enforceable rule: holding ORDER_ACCEPT is
-- meaningless on its own, it is held *for a specific supplier store*. Every
-- authorisation check reads both the permission and the scope.
CREATE TABLE user_role (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    role_id         BIGINT       NOT NULL,
    scope_type      VARCHAR(32)  NOT NULL,
    scope_id        BIGINT       NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    granted_by      BIGINT       NULL,
    granted_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at      TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_user_role_granted_by FOREIGN KEY (granted_by) REFERENCES users (id),
    -- One live grant of a role per user per scope. scope_id is nullable for
    -- platform-wide internal roles; MySQL treats NULLs as distinct in a unique
    -- index, so the service guards that case rather than the constraint.
    CONSTRAINT uk_user_role_scope UNIQUE (user_id, role_id, scope_type, scope_id),
    KEY ix_user_role_user_status (user_id, status),
    KEY ix_user_role_scope (scope_type, scope_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
