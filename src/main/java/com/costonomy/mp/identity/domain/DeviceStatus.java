package com.costonomy.mp.identity.domain;

public enum DeviceStatus {
    ACTIVE,
    /** Unregistered on logout, or reassigned to another user. */
    INACTIVE,
}
