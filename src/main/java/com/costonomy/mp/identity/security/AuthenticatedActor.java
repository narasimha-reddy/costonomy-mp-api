package com.costonomy.mp.identity.security;

import java.security.Principal;

/**
 * Who is making the request.
 *
 * <p>Identity only. Permissions are not here, and should not be added: they are
 * granted per scope (this outlet, that supplier store), they change while a token
 * is live, and doc 03 §16 requires the server to check them against the current
 * state. Services resolve authorisation from the database using this actor's id.
 */
public record AuthenticatedActor(Long userId, String phone) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
