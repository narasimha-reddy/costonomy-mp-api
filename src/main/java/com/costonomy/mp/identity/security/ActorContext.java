package com.costonomy.mp.identity.security;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.security.web.CostonomyAuthentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Reads the current {@link AuthenticatedActor} out of the security context. */
public final class ActorContext {

    private ActorContext() {
    }

    public static Optional<AuthenticatedActor> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // costonomy-common-api's user id, which is also the local users.id (CommonSync provisions it under it)
        if (!(authentication instanceof CostonomyAuthentication costonomy) || costonomy.getPrincipal().userId() == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedActor(costonomy.getPrincipal().userId()));
    }

    /** The current actor, or {@code UNAUTHENTICATED}. Use on endpoints that require a user. */
    public static AuthenticatedActor require() {
        return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    public static Long requireUserId() {
        return require().userId();
    }
}
