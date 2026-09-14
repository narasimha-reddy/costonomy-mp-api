package com.costonomy.mp.common.error;

import java.util.Map;

/**
 * A resource does not exist, or is outside the actor's scope.
 *
 * <p>Deliberately conflates the two. Doc 09 §3 requires that a user cannot probe
 * for another tenant's records by changing an ID; returning 403 for "exists but
 * not yours" and 404 for "does not exist" leaks exactly that. So a scoped lookup
 * that finds nothing returns this, and the audit log — not the response — records
 * which case it was.
 *
 * <p>{@code entity} and {@code id} are for logging and audit only. They are not
 * echoed to the client.
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String entity, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(),
                Map.of());
        this.entity = entity;
        this.id = String.valueOf(id);
    }

    private final String entity;
    private final String id;

    public String entity() {
        return entity;
    }

    public String id() {
        return id;
    }
}
