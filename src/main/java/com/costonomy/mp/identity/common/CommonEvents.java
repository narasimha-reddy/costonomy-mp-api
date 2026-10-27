package com.costonomy.mp.identity.common;

import com.costonomy.security.access.AccessClient;
import com.costonomy.security.webhook.CommonAccessChanged;
import com.costonomy.security.webhook.CommonEnrollmentChanged;
import com.costonomy.security.webhook.CommonEvent;
import com.costonomy.security.webhook.CommonOrgUpdated;
import com.costonomy.security.webhook.CommonOutletUpdated;
import com.costonomy.security.webhook.CommonUserUpdated;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * costonomy-common-api's change notifications (verified by the starter): the {@link Directory} drops what changed, and
 * a changed grant takes effect on the user's next request, not after the cache expires.
 */
@Component
@RequiredArgsConstructor
public class CommonEvents {

    private final Directory directory;
    private final AccessClient access;

    @EventListener
    public void onUser(CommonUserUpdated e) {
        id(e, "userId", "id").ifPresent(directory::evictUser);
    }

    @EventListener
    public void onOrg(CommonOrgUpdated e) {
        id(e, "orgId", "id").ifPresent(directory::evictOrg);
    }

    /** An outlet changed (including its Marketplace settings); defaults changed: every outlet reads them. */
    @EventListener
    public void onOutlet(CommonOutletUpdated e) {
        if ("outlet.settings_defaults_changed".equals(e.type())) {
            directory.evictAll();
            return;
        }
        id(e, "outletId", "id").ifPresent(directory::evictOutlet);
    }

    /** Activation, suspension and live changes are a business's enrollment. */
    @EventListener
    public void onEnrollment(CommonEnrollmentChanged e) {
        id(e, "orgId", null).ifPresent(directory::evictOrg);
    }

    @EventListener
    public void onAccess(CommonAccessChanged e) {
        id(e, "userId", null).ifPresent(userId -> access.evictUser(userId, null));
    }

    private static Optional<Long> id(CommonEvent e, String field, String fallback) {
        JsonNode data = e.data();
        if (data == null) {
            return Optional.empty();
        }
        JsonNode v = data.get(field);
        if ((v == null || !v.canConvertToLong()) && fallback != null) {
            v = data.get(fallback);
        }
        return v != null && v.canConvertToLong() ? Optional.of(v.asLong()) : Optional.empty();
    }
}
