package com.costonomy.mp.identity.domain;

public enum UserStatus {
    ACTIVE,
    /**
     * Blocked by operations. Authentication is refused, and existing refresh
     * tokens are revoked at the point of suspension rather than left to expire.
     */
    SUSPENDED,
    /**
     * Deactivated at the user's request. Rows are never hard-deleted (doc 02
     * §12) — transactional history must stay reconstructable.
     */
    DEACTIVATED,
}
