package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Set<RealtimeChannel> channelsFor(Long userId) {
        Set<RealtimeChannel> channels = new LinkedHashSet<>();

        // Outlets: granted directly, or through the restaurant that owns them.
        jdbc.query("""
                select distinct o.id
                  from outlet o
                  join user_role ur on ur.status = 'ACTIVE'
                   and ((ur.scope_type = 'OUTLET' and ur.scope_id = o.id)
                     or (ur.scope_type = 'RESTAURANT' and ur.scope_id = o.restaurant_id))
                 where ur.user_id = ?
                """,
                rs -> {
                    channels.add(RealtimeChannel.outlet(rs.getLong(1)));
                },
                userId);

        // Stores: granted directly, or through the supplier organisation.
        jdbc.query("""
                select distinct s.id
                  from supplier_store s
                  join user_role ur on ur.status = 'ACTIVE'
                   and ((ur.scope_type = 'SUPPLIER_STORE' and ur.scope_id = s.id)
                     or (ur.scope_type = 'SUPPLIER' and ur.scope_id = s.supplier_organization_id))
                 where ur.user_id = ?
                """,
                rs -> {
                    channels.add(RealtimeChannel.supplierStore(rs.getLong(1)));
                },
                userId);

        return channels;
    }

    public List<String> channelNamesFor(Long userId) {
        return channelsFor(userId).stream().map(RealtimeChannel::name).toList();
    }
}
