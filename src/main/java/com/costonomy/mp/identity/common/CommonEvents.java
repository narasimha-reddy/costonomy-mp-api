package com.costonomy.mp.identity.common;

import com.costonomy.security.access.AccessClient;
import com.costonomy.security.webhook.CommonAccessChanged;
import com.costonomy.security.webhook.CommonEvent;
import com.costonomy.security.webhook.CommonOrgUpdated;
import com.costonomy.security.webhook.CommonOutletUpdated;
import com.costonomy.security.webhook.CommonUserUpdated;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** costonomy-common-api webhooks (verified by the starter) keep MP's copies current between requests. */
@Component
@RequiredArgsConstructor
public class CommonEvents {

    private final CommonSync sync;
    private final AccessClient access;

    @EventListener
    public void onUser(CommonUserUpdated e) {
        id(e, "userId", "id").ifPresent(sync::syncUser);
    }

    @EventListener
    public void onOrg(CommonOrgUpdated e) {
        id(e, "orgId", "id").ifPresent(sync::syncOrg);
    }

    @EventListener
    public void onOutlet(CommonOutletUpdated e) {
        id(e, "outletId", "id").ifPresent(sync::syncOutlet);
    }

    /** A changed or removed grant takes effect on the user's next request, not after the cache expires. */
    @EventListener
    public void onAccess(CommonAccessChanged e) {
        id(e, "userId", null).ifPresent(userId -> {
            access.evictUser(userId, null);
            sync.invalidate(userId);
        });
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
