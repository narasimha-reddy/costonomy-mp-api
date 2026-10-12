package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which channels a user may listen on.
 *
 * <p>Derived from live grants, every time. Doc 46 requires a revoked grant to take
 * effect on the next request, and a socket that outlived its grant would be the
 * longest-lived exception to that in the system — so entitlement is recomputed at
 * every handshake rather than carried in a token.
 *
 * <p><b>Grants expand down the scope hierarchy.</b> A grant on a restaurant covers
 * its outlets and a grant on a supplier organisation covers its stores, because
 * that is what those grants mean everywhere else — {@code ScopeType.satisfyingScopes()}
 * already says so for permission checks, and realtime must not disagree with it.
 *
 * <p><b>Platform grants get nothing here.</b> An operator's channel would be every
 * channel, which is a firehose rather than a feature; operations reads state
 * through its own APIs (Phase 14). Silently subscribing them would also put every
 * tenant's traffic on one connection, which is the opposite of what the channel
 * model is for.
 */
@Service
@RequiredArgsConstructor
public class RealtimeEntitlements {

    private final AccessClient access;

    /** The outlets and stores the user can open in the marketplace, from their costonomy-common-api access. */
    public Set<RealtimeChannel> channelsFor(Long userId) {
        Set<RealtimeChannel> channels = new LinkedHashSet<>();
        AccessSnapshot snapshot = access.get(userId, null).filter(AccessSnapshot::usable).orElse(null);
        if (snapshot == null || snapshot.orgs() == null) {
            return channels;
        }
        for (AccessSnapshot.Org org : snapshot.orgs()) {
            for (AccessSnapshot.Outlet outlet : org.outlets()) {
                channels.add("SUPPLIER".equals(org.orgType())
                        ? RealtimeChannel.supplierStore(outlet.outletId())
                        : RealtimeChannel.outlet(outlet.outletId()));
            }
        }
        return channels;
    }

    public List<String> channelNamesFor(Long userId) {
        return channelsFor(userId).stream().map(RealtimeChannel::name).toList();
    }
}
