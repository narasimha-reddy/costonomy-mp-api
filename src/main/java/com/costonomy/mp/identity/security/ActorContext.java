package com.costonomy.mp.identity.security;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Reads the current {@link AuthenticatedActor} out of the security context. */
public final class ActorContext {

    private ActorContext() {
    }

    public static Optional<AuthenticatedActor> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            return Optional.empty();
        }
        return Optional.of(actor);
    }

    /** The current actor, or {@code UNAUTHENTICATED}. Use on endpoints that require a user. */
    public static AuthenticatedActor require() {
        return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    public static Long requireUserId() {
        return require().userId();
    }
}
