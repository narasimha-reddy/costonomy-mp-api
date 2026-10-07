package com.costonomy.mp.access.service;

import com.costonomy.mp.access.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Role id to role code (D-182). The roles are seeded reference data nothing edits at runtime, but every permission
 * check used to load all of them ({@code roleRepository.findAll()}), and most endpoints check twice.
 *
 * <p><b>This is reference data, not an authorization decision.</b> Who holds which role in which scope is still read
 * live from {@code user_role} on every check, so revoking a grant still applies on the user's next request (the same
 * rule {@link RolePermissionCatalog} states). The snapshot is immutable and swapped whole, and reloaded after
 * {@link #TTL} so a change made by hand in the database is seen without a restart.
 */
@Component
public class RoleCodeCache {

    static final Duration TTL = Duration.ofMinutes(10);

    private record Snapshot(Map<Long, String> codes, Instant loadedAt) {
    }

    private final RoleRepository roles;
    private final Clock clock;
    private volatile Snapshot snapshot;

    @org.springframework.beans.factory.annotation.Autowired
    public RoleCodeCache(RoleRepository roles) {
        this(roles, Clock.systemUTC());
    }

    RoleCodeCache(RoleRepository roles, Clock clock) {
        this.roles = roles;
        this.clock = clock;
    }

    /**
     * The code of one role. A grant naming a role the snapshot doesn't know means a role was added since it was
     * loaded (a grant can't name a role that doesn't exist), so the snapshot is reloaded once rather than the grant
     * being ignored until the TTL runs out.
     */
    public String codeOf(Long roleId) {
        String code = codesById().get(roleId);
        if (code == null && roleId != null) {
            code = load(clock.instant()).get(roleId);
        }
        return code;
    }

    /** An immutable map of role id to code. */
    public Map<Long, String> codesById() {
        Snapshot current = snapshot;
        Instant now = clock.instant();
        if (current == null || current.loadedAt().plus(TTL).isBefore(now)) {
            return load(now);
        }
        return current.codes();
    }

    private Map<Long, String> load(Instant now) {
        var loaded = new HashMap<Long, String>();
        roles.findAll().forEach(role -> loaded.put(role.getId(), role.getCode()));
        // An empty result is not kept: roles are seeded by Flyway, and an empty map cached while a migration was
        // still running would deny everything for ten minutes (the reason RolePermissionCatalog loads lazily).
        if (loaded.isEmpty()) {
            return Map.of();
        }
        var current = new Snapshot(Map.copyOf(loaded), now);
        snapshot = current;
        return current.codes();
    }

    /** Drop the snapshot, for a role change made through the application. */
    public void invalidate() {
        snapshot = null;
    }
}
