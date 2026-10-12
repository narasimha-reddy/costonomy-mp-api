package com.costonomy.mp.identity.common;

import com.costonomy.security.web.CostonomyAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Before each signed-in request: the caller's users row exists under common's id, and every org, outlet and
 * store their MARKETPLACE access lists has an MP row (checked only when common's access version changed).
 */
@Component
@RequiredArgsConstructor
public class CommonSyncInterceptor implements HandlerInterceptor {

    private final CommonSync sync;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof CostonomyAuthentication auth
                && auth.getPrincipal().userId() != null) {
            long userId = auth.getPrincipal().userId();
            sync.ensureUser(userId);
            sync.ensureScopes(userId, auth.access());
        }
        return true;
    }
}
